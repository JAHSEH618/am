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

log()  { printf '\033[0;36m[deploy]\033[0m %s\n' "$*"; }
err()  { printf '\033[0;31m[deploy:ERROR]\033[0m %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

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

log "部署完成，$SERVICE 健康（$OLD_SHA → $NEW_SHA）。"
log "查看日志：docker compose logs -f $SERVICE"
