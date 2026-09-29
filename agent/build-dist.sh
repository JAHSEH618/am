#!/usr/bin/env bash
# 编译 4 平台 aiwatchd 二进制 + 安装脚本 + manifest.json 到 agent/dist/install/
#
# 这个目录就是运维要 rsync 到生产 /srv/aiwatch/install/ 的"分发包"。
# server 端通过 aiwatch.install.dir 指向这里（dev profile 默认就指本地 dist/install）。
#
# manifest.json （v2.5 起）：
#   员工通过 `aiwatchd update` 自更新时拉取该文件比对版本号 + sha256。
#   产物形如 {"version":"2.0.1","artifacts":{"darwin-arm64":{"filename":"...","sha256":"...","size":12345}, ...}}
#
# 用法：
#   bash agent/build-dist.sh                    # 默认版本 dev，全量重编 4 平台
#   VERSION=2.0.0 bash agent/build-dist.sh      # 指定版本
#   bash agent/build-dist.sh --scripts-only     # 仅刷脚本（install.sh / install.ps1），不重编 go（秒级）
#   ROLLOUT_PERCENT=10 VERSION=2.0.0 bash agent/build-dist.sh   # 灰度：只有 ~10% 的 agent 会自动升级到它
#
# 灰度发布（manifest.json 的 rollout_percent，0-100，缺省 100）：
#   客户端 updater 用 agent_id 的稳定哈希决定自己在不在灰度比例内（见 internal/updater/autoupdate.go），
#   比例只增不减时升级的机器集合单调扩大。放量不需要重编：把已发布目录里 manifest.json 的 rollout_percent
#   改大即可，或者用同一 VERSION 重跑本脚本的 --scripts-only 并带新的 ROLLOUT_PERCENT
#   （注意：--scripts-only 会重新生成整份 manifest，务必带上 VERSION，否则版本号会变回 dev）。
#   手动 `aiwatchd update` 不受灰度限制；新装（install.sh / install.ps1）也不受灰度限制。
#
# 设计取舍：
#   - go install 用 -trimpath + -ldflags "-s -w"，单二进制 ~12MB（带符号 ~17MB）
#   - 不上传到 OSS / GitHub Release 这种渠道；公司内网部署只需 rsync 到服务器
#   - aiwatchd-windows-arm64.exe 暂不构建（市场份额低，需要时加一行即可）

set -euo pipefail

cd "$(dirname "$0")"

SCRIPTS_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --scripts-only) SCRIPTS_ONLY=1 ;;
        -h|--help)
            sed -n '1,25p' "$0"; exit 0 ;;
        *) echo "unknown arg: $arg" >&2; exit 2 ;;
    esac
done

VERSION="${VERSION:-dev}"
ROLLOUT_PERCENT="${ROLLOUT_PERCENT:-100}"
case "$ROLLOUT_PERCENT" in
    ''|*[!0-9]*) echo "ROLLOUT_PERCENT must be an integer in 0..100, got: '$ROLLOUT_PERCENT'" >&2; exit 2 ;;
esac
if [ "$ROLLOUT_PERCENT" -gt 100 ]; then
    echo "ROLLOUT_PERCENT must be an integer in 0..100, got: '$ROLLOUT_PERCENT'" >&2; exit 2
fi
OUT_DIR="dist/install"
mkdir -p "$OUT_DIR"

# sha256 跨平台兼容：mac 用 shasum -a 256，linux 用 sha256sum
sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        echo "neither shasum nor sha256sum found" >&2; exit 3
    fi
}

# 构建一个平台并把 sha256 注入到二进制内部（main.BinaryHash）。
# 做法：先计算"基线 sha256"（用占位 hash 编一遍）→ 实际 hash 不需要回灌进二进制（自描述会引发 chicken-and-egg）；
# 我们采取的策略：BinaryHash 留空（向后兼容旧上报字段），manifest.json 是真相源。
build() {
    local goos=$1 goarch=$2 ext=${3:-}
    local out="$OUT_DIR/aiwatchd-${goos}-${goarch}${ext}"
    echo "[build-dist] $goos/$goarch -> $out"
    GOOS="$goos" GOARCH="$goarch" CGO_ENABLED=0 \
        go build -trimpath -ldflags "-X main.Version=${VERSION} -s -w" -o "$out" ./cmd/agent
}

if [ "$SCRIPTS_ONLY" -eq 0 ]; then
    build darwin  arm64
    build darwin  amd64
    build linux   amd64
    build windows amd64 .exe
fi

cp scripts/install.sh  "$OUT_DIR/aiwatchd.sh"
cp scripts/install.ps1 "$OUT_DIR/aiwatchd.ps1"
chmod +x "$OUT_DIR/aiwatchd.sh"

# 生成 manifest.json（每次构建都重新生成，scripts-only 时也刷新文件大小 / hash）
manifest_artifact() {
    local key=$1 file=$2
    local path="$OUT_DIR/$file"
    if [ ! -f "$path" ]; then
        return
    fi
    local hash size
    hash=$(sha256_of "$path")
    size=$(wc -c < "$path" | tr -d ' ')
    printf '    "%s": {"filename": "%s", "sha256": "%s", "size": %s}' \
        "$key" "$file" "$hash" "$size"
}

{
    echo "{"
    echo "  \"version\": \"$VERSION\","
    echo "  \"generated_at\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"rollout_percent\": $ROLLOUT_PERCENT,"
    echo "  \"artifacts\": {"
    first=1
    for entry in \
        "darwin-arm64:aiwatchd-darwin-arm64" \
        "darwin-amd64:aiwatchd-darwin-amd64" \
        "linux-amd64:aiwatchd-linux-amd64" \
        "windows-amd64:aiwatchd-windows-amd64.exe"; do
        key=${entry%%:*}; file=${entry#*:}
        chunk=$(manifest_artifact "$key" "$file" || true)
        [ -z "$chunk" ] && continue
        if [ "$first" -eq 1 ]; then first=0; else echo ","; fi
        printf '%s' "$chunk"
    done
    echo
    echo "  }"
    echo "}"
} > "$OUT_DIR/manifest.json"

echo
if [ "$SCRIPTS_ONLY" -eq 1 ]; then
    echo "[build-dist] scripts + manifest refreshed (binaries untouched)"
else
    echo "[build-dist] done. version=$VERSION rollout_percent=$ROLLOUT_PERCENT"
fi
ls -lh "$OUT_DIR"
echo
echo "[manifest] $OUT_DIR/manifest.json:"
cat "$OUT_DIR/manifest.json"
