#!/usr/bin/env bash
#
# AIWatch 一键更新部署：拉最新代码 → 重建镜像 → 重启 docker 服务 → 健康检查
#
# 在部署机上（任意目录都可，脚本会自动定位到仓库根）：
#   ./scripts/deploy.sh                 # 拉当前分支最新代码，重建 server 并重启
#   BRANCH=am ./scripts/deploy.sh       # 指定要拉取的分支
#   NO_BUILD=1 ./scripts/deploy.sh      # 仅重启不重建（只在改了 .env / compose 时用；改了代码必须重建）
#   SERVICE=server ./scripts/deploy.sh  # 只重建/重启指定服务（默认 server；mysql 一般无需重建）
#   HEALTH_TIMEOUT=240 ./scripts/deploy.sh  # 覆盖健康检查最长等待秒数（默认 180）
#   MIN_FREE_GB=8 ./scripts/deploy.sh   # 构建前要求 Docker 存储盘的最小可用空间（默认 5 GiB）
#   PRUNE=0 ./scripts/deploy.sh         # 跳过部署成功后的旧镜像/构建缓存自动清理（默认开启）
#
# 说明：
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
HEALTH_URL="${HEALTH_URL:-http://localhost:9527/actuator/health}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"
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

# ---- 健康检查 ----
log "等待 $SERVICE 健康（最长 ${HEALTH_TIMEOUT}s，探测 $HEALTH_URL）..."
deadline=$(( SECONDS + HEALTH_TIMEOUT ))
healthy=0
while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
        healthy=1
        break
    fi
    sleep 5
done

echo
docker compose ps

if [ "$healthy" != "1" ]; then
    err "健康检查未在 ${HEALTH_TIMEOUT}s 内通过。近 50 行日志："
    docker compose logs --tail=50 "$SERVICE" || true
    err "如需回滚：git checkout $OLD_SHA && ./scripts/deploy.sh"
    exit 1
fi

if [ "$PRUNE" = "1" ] && [ "${NO_BUILD:-0}" != "1" ]; then
    log "清理旧版本镜像与多余构建缓存（保留 2GB 缓存；PRUNE=0 可跳过）"
    docker_cleanup 0
fi

log "部署完成，$SERVICE 健康（$OLD_SHA → $NEW_SHA）。"
log "查看日志：docker compose logs -f $SERVICE"
