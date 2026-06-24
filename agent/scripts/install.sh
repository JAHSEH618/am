#!/usr/bin/env bash
# AIWatch 一键安装脚本（macOS / Linux）
#
# 推荐调用（前端登录页拼出来的同款命令，参数走命令行，员工免环境变量）：
#   curl -fsSL https://aiwatch.example.com/install/aiwatchd.sh \
#     | bash -s -- --user-code alice --user-name 张三 --department 研发部 \
#                  --server-url https://aiwatch.example.com
#
# 流程：
#   1. 解析 --user-code / --user-name / --department / --server-url
#      （缺省回退到 AM_USER_CODE / AM_USER_NAME / AM_DEPARTMENT / AM_SERVER_URL）
#   2. 探测 OS（darwin / linux）+ ARCH（arm64 / amd64）
#   3. 从 <server>/install/aiwatchd-<os>-<arch> 下载二进制
#   4. 安装到 ~/.local/bin/aiwatchd，chmod +x；mac 上做 ad-hoc codesign
#   5. 跑 aiwatchd init（写 config + 调 /api/v1/agent/register）
#   6. 注册开机自启：
#        macOS  →  ~/Library/LaunchAgents/com.aiwatch.aiwatchd.plist + launchctl
#        Linux  →  ~/.config/systemd/user/aiwatchd.service + systemctl --user
#   7. 立刻拉起一次，并跑 aiwatchd status 给员工看一眼"我装好了"
#
# --clean（可选）：安装前先完全卸载——停服务、删 plist/systemd、本机数据目录、
#   gitlog 游标、日志与 ~/.local/bin/aiwatchd。等价于「重装」而不用手动清缓存。
#
# 设计取舍：
#   - 全部走 user-level 安装，免 sudo —— 公司大部分员工没有 root，且 aiwatchd
#     只读自己的会话日志，本来就不需要系统级权限
#   - 失败时打印明确错误信息后 exit 1；幂等：脚本可以重复执行（plist / service 会被重写）
#
# 由 server 通过 GET /install/aiwatchd.sh 分发，员工不需要直接接触此文件。

set -euo pipefail

err() { printf '\033[31m[aiwatchd-install] %s\033[0m\n' "$*" >&2; }
log() { printf '\033[36m[aiwatchd-install]\033[0m %s\n' "$*"; }

# ---------- 参数解析 ----------
# 优先读命令行参数（前端弹框拼出来的标准形式），其次回退到环境变量（兼容 IT 写脚本批量推 / 旧文档）
usage() {
    cat <<USAGE
Usage: aiwatchd.sh [--clean] [--user-code CODE] [--user-name NAME] [--department DEPT]
                   [--server-url URL]

可同时通过环境变量提供：AM_USER_CODE / AM_USER_NAME / AM_DEPARTMENT / AM_SERVER_URL。
命令行参数优先级高于环境变量。

--clean   安装前卸载旧版（含本地游标与配置），与「完全重装」等效。

--user-name / --department 用于"员工自助注册"：服务端 employee 表无此 user_code 时
按这两个字段创建一条 ACTIVE 记录；员工已存在则忽略，不会覆盖 HR 已录入的姓名 / 部门。
USAGE
}

ARG_USER_CODE=""
ARG_USER_NAME=""
ARG_DEPARTMENT=""
ARG_SERVER_URL=""
ARG_INSTALL_TOKEN=""
ARG_CLEAN=0
while [ $# -gt 0 ]; do
    case "$1" in
        --clean) ARG_CLEAN=1; shift ;;
        --user-code)   ARG_USER_CODE="${2:-}"; shift 2 ;;
        --user-code=*) ARG_USER_CODE="${1#*=}"; shift ;;
        --user-name)    ARG_USER_NAME="${2:-}"; shift 2 ;;
        --user-name=*)  ARG_USER_NAME="${1#*=}"; shift ;;
        --department)   ARG_DEPARTMENT="${2:-}"; shift 2 ;;
        --department=*) ARG_DEPARTMENT="${1#*=}"; shift ;;
        --server-url)   ARG_SERVER_URL="${2:-}"; shift 2 ;;
        --server-url=*) ARG_SERVER_URL="${1#*=}"; shift ;;
        --install-token)   ARG_INSTALL_TOKEN="${2:-}"; shift 2 ;;
        --install-token=*) ARG_INSTALL_TOKEN="${1#*=}"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) err "unknown argument: $1"; usage; exit 2 ;;
    esac
done

# 命令行 > 环境变量
AM_USER_CODE="${ARG_USER_CODE:-${AM_USER_CODE:-}}"
AM_USER_NAME="${ARG_USER_NAME:-${AM_USER_NAME:-}}"
AM_DEPARTMENT="${ARG_DEPARTMENT:-${AM_DEPARTMENT:-}}"
AM_SERVER_URL="${ARG_SERVER_URL:-${AM_SERVER_URL:-}}"
# 安装端点令牌：服务端 install.token 非空时，/install/** 需带 ?t=<token>（管理员下发命令时附带）
AM_INSTALL_TOKEN="${ARG_INSTALL_TOKEN:-${AM_INSTALL_TOKEN:-}}"

install_launchd() {
    local plist_dir="${HOME}/Library/LaunchAgents"
    local plist="${plist_dir}/com.aiwatch.aiwatchd.plist"
    local log_dir="${HOME}/Library/Logs/aiwatchd"
    mkdir -p "$plist_dir" "$log_dir"

    cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>          <string>com.aiwatch.aiwatchd</string>
  <key>ProgramArguments</key>
  <array>
    <string>${install_path}</string>
    <string>start</string>
  </array>
  <key>RunAtLoad</key>      <true/>
  <key>KeepAlive</key>      <true/>
  <key>WorkingDirectory</key><string>${HOME}</string>
  <key>StandardOutPath</key><string>${log_dir}/aiwatchd.log</string>
  <key>StandardErrorPath</key><string>${log_dir}/aiwatchd.log</string>
  <key>ProcessType</key>    <string>Background</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>         <string>/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${HOME}/.local/bin</string>
  </dict>
</dict>
</plist>
PLIST

    # 旧版本可能已注册，先卸再装确保幂等
    launchctl unload "$plist" >/dev/null 2>&1 || true
    launchctl load "$plist"
    log "launchd registered: $plist (运行日志: ${log_dir}/aiwatchd.log)"
}

install_systemd() {
    local unit_dir="${HOME}/.config/systemd/user"
    local unit="${unit_dir}/aiwatchd.service"
    mkdir -p "$unit_dir"

    cat > "$unit" <<UNIT
[Unit]
Description=AIWatch agent (aiwatchd)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=${install_path} start
Restart=on-failure
RestartSec=10
Environment=PATH=/usr/local/bin:/usr/bin:/bin:%h/.local/bin

[Install]
WantedBy=default.target
UNIT

    if ! command -v systemctl >/dev/null 2>&1; then
        err "systemctl not found; please run '$install_path start' manually"
        return
    fi
    systemctl --user daemon-reload
    systemctl --user enable --now aiwatchd.service

    # 让员工登出后服务依旧保活；不强制（部分发行版禁止 lingering）
    if command -v loginctl >/dev/null 2>&1; then
        loginctl enable-linger "$USER" >/dev/null 2>&1 || true
    fi
    log "systemd registered: $unit (logs: journalctl --user -u aiwatchd)"
}

# 与 aiwatchd uninstall --yes / Windows 安装脚本 -Clean 路径约定一致。
purge_prior_install_unix() {
    # 优先调用已安装二进制（停服务 + 删注册项 + 清数据），失败不致命，脚本继续兜底删目录。
    local legacy_bin="${HOME}/.local/bin/aiwatchd"
    if [ -x "$legacy_bin" ]; then
        "$legacy_bin" uninstall --yes >/dev/null 2>&1 || true
    fi

    case "${os}" in
        darwin)
            local plist="${HOME}/Library/LaunchAgents/com.aiwatch.aiwatchd.plist"
            launchctl unload "$plist" >/dev/null 2>&1 || true
            rm -f "$plist"
            ;;
        linux)
            if command -v systemctl >/dev/null 2>&1; then
                systemctl --user stop aiwatchd.service >/dev/null 2>&1 || true
                systemctl --user disable aiwatchd.service >/dev/null 2>&1 || true
            fi
            rm -f "${HOME}/.config/systemd/user/aiwatchd.service"
            if command -v systemctl >/dev/null 2>&1; then
                systemctl --user daemon-reload >/dev/null 2>&1 || true
            fi
            ;;
    esac

    # 可能仍有前台 aiwatchd 进程（或未注册为服务的环境）
    pkill -x aiwatchd >/dev/null 2>&1 || true
    sleep 1

    rm -rf "${HOME}/Library/Application Support/aiwatchd" \
        "${HOME}/Library/Application Support/ai-work-agent" \
        "${HOME}/.config/aiwatchd" \
        "${HOME}/.config/ai-work-agent" \
        "${HOME}/.cache/aiwatchd" \
        "${HOME}/.local/state/aiwatchd" \
        "${HOME}/Library/Logs/aiwatchd" \
        "$legacy_bin"
}

# ---------- 1. 入参校验 ----------
if [ -z "$AM_SERVER_URL" ]; then
    err 'server URL not provided. Pass --server-url or set AM_SERVER_URL.'
    usage; exit 2
fi
if [ -z "$AM_USER_CODE" ]; then
    err 'user code not provided. Pass --user-code or set AM_USER_CODE.'
    usage; exit 2
fi

AM_SERVER_URL="${AM_SERVER_URL%/}"

# ---------- 2. OS / ARCH 探测 ----------
uname_s="$(uname -s)"
uname_m="$(uname -m)"
case "$uname_s" in
    Darwin) os="darwin" ;;
    Linux)  os="linux"  ;;
    *) err "unsupported OS: $uname_s (this script only supports darwin / linux)"; exit 1 ;;
esac
case "$uname_m" in
    arm64|aarch64) arch="arm64" ;;
    x86_64|amd64)  arch="amd64" ;;
    *) err "unsupported arch: $uname_m"; exit 1 ;;
esac

# linux/arm64 暂不分发，避免下到 404 HTML 后才报错
if [ "$os" = "linux" ] && [ "$arch" = "arm64" ]; then
    err "linux/arm64 binary not yet provided; ask ops to add it to install dir"
    exit 1
fi

if [ "$ARG_CLEAN" -eq 1 ]; then
    log "clean: removing prior install (service, data, gitlog cursor, default binary)..."
    purge_prior_install_unix
fi

binary_name="aiwatchd-${os}-${arch}"
# 安装端点令牌：服务端 install.token 非空时需带 ?t=；空则普通 URL（向后兼容）
tokq=""
[ -n "$AM_INSTALL_TOKEN" ] && tokq="?t=${AM_INSTALL_TOKEN}"
download_url="${AM_SERVER_URL}/install/${binary_name}${tokq}"

# ---------- 3. 下载 ----------
install_dir="${HOME}/.local/bin"
install_path="${install_dir}/aiwatchd"
mkdir -p "$install_dir"

log "downloading ${AM_SERVER_URL}/install/${binary_name}"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
if ! curl -fsSL --retry 2 --connect-timeout 10 -o "$tmp" "$download_url"; then
    err "download failed; check AM_SERVER_URL or contact ops"
    exit 1
fi

size=$(wc -c < "$tmp" | tr -d ' ')
if [ "$size" -lt 1048576 ]; then
    err "downloaded file too small (${size} bytes); likely 404 / proxy error / install-token 缺失"
    head -c 256 "$tmp" >&2 || true
    exit 1
fi

# ---------- 3.4 sha256 防篡改校验 ----------
# 从 manifest.json 取该平台 sha256，与下载文件比对；不匹配立即中止（可能被 MITM 篡改）。
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
    else echo ""; fi
}
manifest="$(curl -fsSL --retry 2 --connect-timeout 10 "${AM_SERVER_URL}/install/manifest.json${tokq}" 2>/dev/null || true)"
expected_sha="$(printf '%s' "$manifest" | tr -d ' \n' \
    | sed -n "s/.*\"${os}-${arch}\":{[^}]*\"sha256\":\"\([0-9a-fA-F]\{64\}\)\".*/\1/p" | head -n1)"
actual_sha="$(sha256_of "$tmp")"
if [ -z "$expected_sha" ]; then
    err "无法从 manifest.json 获取 ${os}-${arch} 的 sha256；为安全起见中止安装（联系运维确认 manifest 可访问）"
    exit 1
fi
if [ -z "$actual_sha" ] || [ "$actual_sha" != "$expected_sha" ]; then
    err "二进制 sha256 校验失败（期望 ${expected_sha}，实际 ${actual_sha:-空}）；可能被篡改，已中止"
    exit 1
fi
log "sha256 校验通过"

mv "$tmp" "$install_path"
trap - EXIT
chmod +x "$install_path"
log "installed: $install_path"

# ---------- 3.5 mac ad-hoc 签名 ----------
# arm64 mac 启动未签名二进制会被 kernel 拦"killed: 9"，跑一次 ad-hoc sign 即可
if [ "$os" = "darwin" ]; then
    if command -v codesign >/dev/null 2>&1; then
        codesign --force --sign - "$install_path" >/dev/null 2>&1 || true
    fi
fi

# ---------- 4. PATH 提示 ----------
case ":$PATH:" in
    *":$install_dir:"*) ;;
    *) log "tip: add \"$install_dir\" to PATH in your shell rc to use 'aiwatchd' directly" ;;
esac

# ---------- 5. init + 注册 ----------
export AM_SERVER_URL AM_USER_CODE AM_USER_NAME AM_DEPARTMENT
if [ -n "$AM_USER_NAME" ] || [ -n "$AM_DEPARTMENT" ]; then
    log "registering as user_code=$AM_USER_CODE user_name=$AM_USER_NAME department=$AM_DEPARTMENT"
else
    log "registering as user_code=$AM_USER_CODE"
fi
"$install_path" init

# ---------- 6. 开机自启 ----------
case "$os" in
    darwin) install_launchd ;;
    linux)  install_systemd ;;
esac

# ---------- 7. 状态自检 ----------
# 静默执行一次 status，捕获 exit code 但不向员工输出 JSON 详情（信息冗长，员工看不懂）。
# 失败时回退打印一行简短提示，方便复制给运维。
if "$install_path" status >/dev/null 2>&1; then
    log "It's OK!"
else
    err "self-check failed; please run '$install_path status' to inspect"
fi
