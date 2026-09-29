#!/usr/bin/env bash
#
# AIWatch 一键更新部署：拉最新代码 → 重建镜像 → 重启 docker 服务 → 健康检查
#
# 在部署机上（任意目录都可，脚本会自动定位到仓库根）：
#   ./scripts/deploy.sh                 # 拉当前分支最新代码，重建 server 并重启
#   BRANCH=am ./scripts/deploy.sh       # 指定要拉取的分支
#   NO_BUILD=1 ./scripts/deploy.sh      # 仅重启不重建（只在改了 .env / compose 时用；改了代码必须重建）
#   SERVICE=server ./scripts/deploy.sh  # 只重建/重启指定服务（默认 server；mysql 一般无需重建）
#   READY_TIMEOUT=900 ./scripts/deploy.sh   # 覆盖"等待就绪"的最长秒数（默认 300；旧名 HEALTH_TIMEOUT 仍可用）
#                                           # 大库首次升级、启动期回填很长时调大；就绪 = /actuator/health/readiness 返回 200
#   MIN_FREE_GB=8 ./scripts/deploy.sh   # 构建前要求 Docker 存储盘的最小可用空间（默认 5 GiB）
#   PRUNE=0 ./scripts/deploy.sh         # 跳过部署成功后的旧镜像/构建缓存自动清理（默认开启）
#
# 说明：
#   - 版本号由仓库决定（server/build.gradle），脚本会自动把部署机 .env 的 AIWATCH_VERSION 对齐，
#     发版后无需手工改 .env；要临时指定版本就 AIWATCH_VERSION=x.y.z ./scripts/deploy.sh。
#   - 更新代码后 server 启动会自动跑 Java *SchemaPatches 补列/建索引与各回填补丁，无需手工改库。
#   - MySQL 表结构仅在数据卷首次初始化时导入；本脚本不动库结构、不删数据卷。
#   - 部署后如需真库自检 / id_sequences 种子校验，见 scripts/p1p3-verify/。
#
set -euo pipefail

# ---- 定位仓库根（脚本在 scripts/ 下）----
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BRANCH="${BRANCH:-$(git rev-parse --abbrev-ref HEAD)}"
SERVICE="${SERVICE:-server}"
# 等的是 readiness 而不是 /actuator/health：Spring Boot 要等所有 ApplicationRunner（启动期 SchemaPatches / 回填）
# 跑完才把 readiness 置为 UP，所以 200 才代表"已经能接流量"；而 /actuator/health 在应用刚起来、补丁还在跑时
# 就可能已经是 UP，会让脚本过早宣告成功。READY_URL / READY_TIMEOUT 的旧名 HEALTH_URL / HEALTH_TIMEOUT 仍兼容。
READY_URL="${READY_URL:-${HEALTH_URL:-http://localhost:9527/actuator/health/readiness}}"
READY_TIMEOUT="${READY_TIMEOUT:-${HEALTH_TIMEOUT:-300}}"
MIN_FREE_GB="${MIN_FREE_GB:-5}"
PRUNE="${PRUNE:-1}"

log()  { printf '\033[0;36m[deploy]\033[0m %s\n' "$*"; }
err()  { printf '\033[0;31m[deploy:ERROR]\033[0m %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

# ---- Docker 磁盘空间：预检 + 清理 ----
# 多阶段构建（JDK/Node/Go）每次重建都产生数 GB 快照，且版本升级换 tag 后旧 aiwatch-server
# 镜像不会自动消失；不清理迟早把 /var/lib/docker(/containerd) 撑爆，报
# "failed to create prepare snapshot dir ... no space left on device"。

docker_data_dirs() {
    local d
    d="$(docker info -f '{{ .DockerRootDir }}' 2>/dev/null || true)"
    printf '%s\n' "${d:-/var/lib/docker}"
    # containerd-snapshotter 模式下快照实际落在 containerd 目录（本次报错正是它满了）
    [ -d /var/lib/containerd ] && printf '%s\n' /var/lib/containerd
    return 0
}

docker_free_gb() {
    # 取各数据目录所在文件系统的最小可用空间（GiB，向下取整）
    local min="" kb dir
    while IFS= read -r dir; do
        kb="$(df -Pk "$dir" 2>/dev/null | awk 'NR==2 {print $4}')" || continue
        [ -n "$kb" ] || continue
        if [ -z "$min" ] || [ "$kb" -lt "$min" ]; then min="$kb"; fi
    done < <(docker_data_dirs)
    echo $(( ${min:-0} / 1024 / 1024 ))
}

docker_cleanup() {
    # $1=aggressive：1 = 清空全部未用构建缓存（磁盘告急时）；0 = 保留 2GB 缓存加速下次构建。
    # 只删旧版本 aiwatch-server 镜像 / dangling 镜像 / 构建缓存，绝不动容器、MySQL 数据卷。
    local aggressive="${1:-0}" current
    current="$(docker compose config --images 2>/dev/null | grep '^aiwatch-server:' | head -1 || true)"
    if [ -n "$current" ]; then
        docker images --format '{{.Repository}}:{{.Tag}}' aiwatch-server 2>/dev/null \
            | grep -vxF "$current" \
            | xargs -r -n1 docker rmi >/dev/null 2>&1 || true
    fi
    docker image prune -f >/dev/null 2>&1 || true
    if [ "$aggressive" = "1" ]; then
        docker builder prune -af >/dev/null 2>&1 || true
    else
        docker builder prune -f --keep-storage 2GB >/dev/null 2>&1 \
            || docker builder prune -f >/dev/null 2>&1 || true
    fi
}

# ---- 前置检查 ----
command -v git >/dev/null 2>&1 || die "未找到 git"
docker compose version >/dev/null 2>&1 || die "未找到 'docker compose'（需 Docker Engine 20+ 与 compose v2 插件）"
[ -f "$REPO_ROOT/docker-compose.yml" ] || die "$REPO_ROOT 下缺少 docker-compose.yml"
[ -f "$REPO_ROOT/.env" ] || die "缺少 .env（先 cp .env.example .env 并填库密码，详见 docs/guides/docker-deploy-centos.md）"

# ---- 拉新代码（ff-only，避免在部署机上产生合并提交）----
OLD_SHA="$(git rev-parse --short HEAD)"
log "当前分支 $BRANCH，当前提交 $OLD_SHA，拉取 origin/$BRANCH ..."
git fetch origin "$BRANCH"
git pull --ff-only origin "$BRANCH"
NEW_SHA="$(git rev-parse --short HEAD)"

if [ "$OLD_SHA" = "$NEW_SHA" ]; then
    log "代码已是最新（$NEW_SHA），无新提交。"
else
    log "代码更新：$OLD_SHA → $NEW_SHA"
fi

# ---- 版本对齐：仓库是唯一版本源，自动写回 .env ----
# 镜像 tag、jar 版本、agent 分发包 manifest 三者都来自 compose 的 ${AIWATCH_VERSION}，
# 而部署机 .env 里的值会盖掉 compose 的默认值。发版后只 push、不改 .env 的话：
# 新代码会打成旧 tag 的镜像，agent 分发包也仍标旧版本号——自动更新按 manifest 版本号
# 字符串相等比对，同号一律判「已是最新」，新客户端一台都装不上。
# 所以这里在 pull 之后、build 之前，把 .env 对齐到仓库版本（显式导出 AIWATCH_VERSION 时以其为准）。
repo_version() {
    sed -nE "s/^version *= *findProperty\('aiwatchVersion'\) *\?: *'([^']+)'.*/\1/p" \
        server/build.gradle | head -1
}
if [ -n "${AIWATCH_VERSION:-}" ]; then
    log "版本取自环境变量 AIWATCH_VERSION=$AIWATCH_VERSION（不改 .env）"
else
    REPO_VERSION="$(repo_version)"
    if [ -z "$REPO_VERSION" ]; then
        log "警告：未能从 server/build.gradle 解析版本号，沿用 .env 现值"
    else
        ENV_VERSION="$(sed -nE 's/^AIWATCH_VERSION=[[:space:]]*(.*)$/\1/p' .env | head -1)"
        if [ "$ENV_VERSION" = "$REPO_VERSION" ]; then
            log "版本 $REPO_VERSION（.env 已对齐）"
        else
            if [ -n "$ENV_VERSION" ]; then
                sed -i.bak -E "s|^AIWATCH_VERSION=.*|AIWATCH_VERSION=$REPO_VERSION|" .env
                rm -f .env.bak
            else
                printf 'AIWATCH_VERSION=%s\n' "$REPO_VERSION" >> .env
            fi
            log ".env AIWATCH_VERSION: ${ENV_VERSION:-<未设置>} → $REPO_VERSION"
        fi
    fi
fi

# ---- 重建 + 重启 ----
if [ "${NO_BUILD:-0}" = "1" ]; then
    log "NO_BUILD=1：跳过镜像重建，仅重启 $SERVICE"
else
    free_gb="$(docker_free_gb)"
    if [ "$free_gb" -lt "$MIN_FREE_GB" ]; then
        log "Docker 存储盘可用空间仅 ${free_gb}GiB（< ${MIN_FREE_GB}GiB），先清理旧镜像与全部构建缓存 ..."
        docker_cleanup 1
        free_gb="$(docker_free_gb)"
        log "清理后可用空间 ${free_gb}GiB"
        if [ "$free_gb" -lt "$MIN_FREE_GB" ]; then
            err "空间仍不足，构建会再次报 'no space left on device'。请先人工排查："
            err "  docker system df                     # 看镜像/缓存/卷各占多少"
            err "  docker system prune -af              # 删所有未被容器使用的镜像+缓存（不动数据卷）"
            err "  journalctl --vacuum-size=200M        # 系统日志瘦身"
            err "  df -h /var/lib/docker /var/lib/containerd   # 确认是哪个分区满，必要时扩容"
            die "Docker 存储盘可用空间不足 ${MIN_FREE_GB}GiB"
        fi
    fi
    log "重建镜像：$SERVICE（首次或依赖变更时较久）"
    docker compose build "$SERVICE"
fi

log "启动/更新服务（up -d，含依赖）"
docker compose up -d

# ---- 等待就绪（readiness）----
# "就绪"= Spring Boot 已跑完全部 ApplicationRunner（启动期 SchemaPatches / 大表回填）并置 ACCEPTING_TRAFFIC，
# /actuator/health/readiness 返回 200。启动补丁 / 回填在大库上可能要很久，这期间返回 503 是正常的，不是故障。
container_id() { docker compose ps -q "$SERVICE" 2>/dev/null | head -1; }

# 返回容器当前状态：running / restarting / exited ...（取不到时返回 unknown）
container_status() {
    local id
    id="$(container_id)"
    [ -n "$id" ] || { echo unknown; return 0; }
    docker inspect -f '{{.State.Status}}' "$id" 2>/dev/null || echo unknown
}

ready_http_code() {
    # 连接被拒 / 超时时 curl 会输出 000
    curl -s -o /dev/null -m 5 -w '%{http_code}' "$READY_URL" 2>/dev/null || true
}

log "等待 $SERVICE 就绪（最长 ${READY_TIMEOUT}s，探测 $READY_URL；启动期补丁/回填未跑完时返回 503 属正常）..."
wait_start=$SECONDS
deadline=$(( SECONDS + READY_TIMEOUT ))
next_note=$(( SECONDS + 30 ))
ready=0
crashed=0
code=000
while [ "$SECONDS" -lt "$deadline" ]; do
    code="$(ready_http_code)"
    if [ "$code" = "200" ]; then
        ready=1
        break
    fi
    status="$(container_status)"
    # 容器已退出 / 反复重启：不必再干等到超时（常见原因：启动失败、OOM 被 ExitOnOutOfMemoryError 终止）
    if [ "$status" = "exited" ] || [ "$status" = "dead" ] || [ "$status" = "restarting" ]; then
        crashed=1
        break
    fi
    if [ "$SECONDS" -ge "$next_note" ]; then
        log "  ...仍未就绪（HTTP $code，容器 $status，已等 $(( SECONDS - wait_start ))s）"
        next_note=$(( SECONDS + 30 ))
    fi
    sleep 5
done

echo
docker compose ps

if [ "$ready" != "1" ]; then
    if [ "$crashed" = "1" ]; then
        err "$SERVICE 容器已退出或在反复重启（状态：$(container_status)），未能就绪。近 80 行日志："
    else
        err "$SERVICE 在 ${READY_TIMEOUT}s 内未就绪（最后一次探测 HTTP $code，容器 $(container_status)）。近 80 行日志："
    fi
    docker compose logs --tail=80 "$SERVICE" || true
    if [ "$crashed" != "1" ]; then
        err "这不一定是故障：大库升级时启动期回填可能要很久（readiness 会一直 503，直到回填结束）。判断方法："
        err "  docker compose logs -f $SERVICE          # 日志还在滚动（回填 / SchemaPatches 进度）就再等等"
        err "  curl -s -o /dev/null -w '%{http_code}\n' $READY_URL   # 200 即已就绪"
        err "  READY_TIMEOUT=900 NO_BUILD=1 ./scripts/deploy.sh   # 只重新等待更久（代码已最新且不重建，容器不受影响）"
        err "日志停止滚动、且始终 503 / 连接被拒 = 卡死，可 docker compose restart $SERVICE。"
    fi
    err "如需回滚：git checkout $OLD_SHA && ./scripts/deploy.sh"
    exit 1
fi

if [ "$PRUNE" = "1" ] && [ "${NO_BUILD:-0}" != "1" ]; then
    log "清理旧版本镜像与多余构建缓存（保留 2GB 缓存；PRUNE=0 可跳过）"
    docker_cleanup 0
fi

log "部署完成，$SERVICE 已就绪（$OLD_SHA → $NEW_SHA）。"
log "查看日志：docker compose logs -f $SERVICE"
