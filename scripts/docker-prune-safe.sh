#!/usr/bin/env bash
# 安全回收 Docker 磁盘占用：只清无用镜像层与构建缓存，绝不删除 volume（MySQL 数据安全）。
#
# 用法：
#   bash scripts/docker-prune-safe.sh              # 默认：dangling 镜像 + 构建缓存 + 停用容器/网络
#   bash scripts/docker-prune-safe.sh --old-tags   # 额外：删除未被当前容器使用的 aiwatch-server:* 旧 tag
#   bash scripts/docker-prune-safe.sh --dry-run    # 只打印将执行的命令
#
# 不要使用：docker system prune -a --volumes  （会清数据卷）

set -euo pipefail

DRY_RUN=0
OLD_TAGS=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --old-tags) OLD_TAGS=1 ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *)
      echo "unknown option: $arg" >&2
      exit 1
      ;;
  esac
done

run() {
  echo "+ $*"
  if [[ "$DRY_RUN" -eq 0 ]]; then
    "$@"
  fi
}

echo "=== before ==="
docker system df 2>/dev/null || true

# 1) 悬空镜像（同 tag 重建后的 <none>）
run docker image prune -f

# 2) 构建缓存（多阶段 Gradle/Go 构建最占盘的部分）
if docker builder du &>/dev/null; then
  run docker builder prune -f
else
  echo "(skip builder prune: BuildKit builder not available)"
fi

# 3) 已停止容器、无用网络（不含 volume、不含仍被引用的镜像）
run docker system prune -f

# 4) 可选：清理历史 aiwatch-server 版本 tag（保留正在运行容器所用的镜像）
if [[ "$OLD_TAGS" -eq 1 ]]; then
  echo "=== prune unused aiwatch-server tags ==="
  # 正在使用的 image id
  in_use="$(docker ps -a --filter name=aiwatch-server --format '{{.Image}}' 2>/dev/null || true)"
  while read -r repo_tag; do
    [[ -z "$repo_tag" ]] && continue
    # 若容器引用的是该 tag 或同名 image，跳过
    if docker ps -a --format '{{.Image}}' | grep -qxF "$repo_tag"; then
      echo "keep (in use by container): $repo_tag"
      continue
    fi
    # 也匹配 compose 可能显示的短名
    if [[ -n "$in_use" ]] && echo "$in_use" | grep -qxF "$repo_tag"; then
      echo "keep (in use): $repo_tag"
      continue
    fi
    run docker rmi "$repo_tag" || echo "skip (still referenced?): $repo_tag"
  done < <(docker images --format '{{.Repository}}:{{.Tag}}' | grep -E '^aiwatch-server:' | grep -v '<none>' || true)
fi

echo "=== after ==="
docker system df 2>/dev/null || true
echo "done. volumes were NOT touched (MySQL data safe)."
