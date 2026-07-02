#!/usr/bin/env bash
# =============================================================================
# P1-P3 线上库只读自检 (第一关 · DB 语义半)
#
# 用途：服务已拉新代码重启后，只读校验 P1-P3 的 schema/索引/@Version/source_ref/
#       id_sequences 种子是否真正生效，以及有无 seed-before-serve 遗留的主键碰撞风险。
# 安全：**纯只读**（只跑 SELECT / information_schema），可直接对线上库运行，不写任何数据。
#
# 环境（CentOS docker 内需有 mysql 客户端：yum install -y mysql 或 mariadb）：
#   DB_HOST (默认 127.0.0.1)  DB_PORT (默认 3306)
#   DB_USER (默认 root)       DB_PASS (必填，或用 MYSQL_PWD)
#   DB_NAME (默认 am)
#
# 例：DB_HOST=10.0.0.5 DB_USER=am DB_PASS=xxx DB_NAME=am ./01-verify-live-db.sh
# 退出码：0 全通过 / 非 0 有硬失败（缺列、缺索引、种子缺失或 next_val<=MAX(id)）。
# =============================================================================
set -euo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-${MYSQL_PWD:-}}"
DB_NAME="${DB_NAME:-am}"

command -v mysql >/dev/null 2>&1 || { echo "ERROR: 找不到 mysql 客户端 (yum install -y mysql 或 mariadb)"; exit 2; }

# 密码走临时 defaults 文件，避免出现在 ps / 触发 "password on command line" 告警
CNF="$(mktemp)"; trap 'rm -f "$CNF"' EXIT
cat >"$CNF" <<EOF
[client]
host=$DB_HOST
port=$DB_PORT
user=$DB_USER
password=$DB_PASS
EOF

mysql_q() { mysql --defaults-extra-file="$CNF" -N -B "$DB_NAME" -e "$1"; }

if [ -t 1 ]; then G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; B=$'\033[1m'; N=$'\033[0m'; else G=; R=; Y=; B=; N=; fi
PASS=0; FAIL=0; WARN=0
ok()   { printf '  %s✓%s %s\n' "$G" "$N" "$1"; PASS=$((PASS+1)); }
bad()  { printf '  %s✗%s %s\n' "$R" "$N" "$1"; FAIL=$((FAIL+1)); }
warn() { printf '  %s⚠%s %s\n' "$Y" "$N" "$1"; WARN=$((WARN+1)); }
head() { printf '\n%s== %s ==%s\n' "$B" "$1" "$N"; }

# 连通性
if ! mysql_q "SELECT 1" >/dev/null 2>&1; then
  echo "ERROR: 连不上 ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}（检查 DB_* 变量与账号权限）"; exit 2
fi
printf '%s目标库%s %s@%s:%s/%s\n' "$B" "$N" "$DB_USER" "$DB_HOST" "$DB_PORT" "$DB_NAME"

col_exists() { [ "$(mysql_q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='$1' AND column_name='$2';")" = "1" ]; }
idx_count()  { mysql_q "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='$1' AND index_name='$2';"; }

# --- P1 / P3-2 / P3-3a：新增列 ------------------------------------------------
head "新增列 (P3-2 @Version / P3-3a source_ref)"
if col_exists ai_session version;            then ok "ai_session.version 存在"; else bad "ai_session.version 缺失 (AiSessionVersionSchemaPatches 未生效)"; fi
if col_exists ai_session_event source_ref;   then ok "ai_session_event.source_ref 存在"; else bad "ai_session_event.source_ref 缺失 (AiSessionEventSourceRefSchemaPatches 未生效)"; fi

# --- P1 / P3-3a：热查询索引 ---------------------------------------------------
head "索引 (P1 PerformanceIndex + P3-3a source_ref)"
check_idx() { if [ "$(idx_count "$1" "$2")" -ge 1 ]; then ok "$1.$2"; else bad "$1.$2 缺失"; fi; }
check_idx ai_session          idx_target_status
check_idx ai_session          idx_project_last
check_idx ai_session          idx_target_last_invalid
check_idx ai_session_event    idx_target_event_time
check_idx ai_session_event    idx_session_sourceref
check_idx ai_session_message  idx_target_message_time

# --- P3-3b：id_sequences 种子安全（seed-before-serve 关键校验）----------------
head "id_sequences 种子安全 (P3-3b — next_val 必须 > MAX(id))"
if [ "$(mysql_q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='id_sequences';")" != "1" ]; then
  bad "id_sequences 表不存在 (IdSequenceSeedSchemaPatches 未生效)"
else
  for t in ai_session_event ai_session_message ai_session_audit git_commit git_commit_file; do
    row="$(mysql_q "SELECT COALESCE(s.next_val,-1), COALESCE((SELECT MAX(id) FROM \`$t\`),0) FROM (SELECT 1) d LEFT JOIN id_sequences s ON s.seq_name='$t';")"
    nv="$(printf '%s' "$row" | cut -f1)"; mx="$(printf '%s' "$row" | cut -f2)"
    if [ "$nv" = "-1" ]; then
      bad "$t: 种子行缺失 (MAX(id)=$mx) — 首次 INSERT 将惰性建行从 1 分配 → 撞主键"
    elif [ "$nv" -le "$mx" ]; then
      bad "$t: next_val=$nv <= MAX(id)=$mx — 危险！下次分配会撞已用主键 (跑 03 脚本修正)"
    else
      ok "$t: next_val=$nv > MAX(id)=$mx (缓冲 $((nv-mx)))"
    fi
  done
fi

# --- P3-3a：source_ref 回填进度（非致命，ingest COALESCE 兜底）----------------
head "source_ref 回填进度 (P3-3a — 尽力回填，残留非致命)"
if col_exists ai_session_event source_ref; then
  remain="$(mysql_q "SELECT COUNT(*) FROM ai_session_event WHERE source_ref IS NULL AND extra_json IS NOT NULL AND JSON_EXTRACT(extra_json,'\$.source_ref') IS NOT NULL;")"
  tot="$(mysql_q "SELECT COUNT(*) FROM ai_session_event;")"
  mat="$(mysql_q "SELECT COALESCE(SUM(source_ref IS NOT NULL),0) FROM ai_session_event;")"
  printf '  行数 total=%s, source_ref 已物化=%s\n' "$tot" "$mat"
  if [ "$remain" -eq 0 ]; then ok "无待回填行 (extra_json 里的 source_ref 已全部落列)"; else warn "$remain 行仍待回填 (extra_json 有 ref、列为 NULL) — 非致命，重启会续跑分块回填"; fi
else
  warn "source_ref 列不存在，跳过回填检查"
fi

# --- 汇总 --------------------------------------------------------------------
printf '\n%s—— 汇总 ——%s  通过 %s%d%s  失败 %s%d%s  告警 %s%d%s\n' "$B" "$N" "$G" "$PASS" "$N" "$R" "$FAIL" "$N" "$Y" "$WARN" "$N"
if [ "$FAIL" -gt 0 ]; then echo "结果: ${R}FAIL${N} — 有硬失败，见上方 ✗"; exit 1; fi
if [ "$WARN" -gt 0 ]; then echo "结果: ${G}PASS${N}（有 $WARN 条告警，可容忍）"; else echo "结果: ${G}PASS${N}"; fi
