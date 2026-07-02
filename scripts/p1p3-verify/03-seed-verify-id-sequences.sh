#!/usr/bin/env bash
# =============================================================================
# id_sequences 种子校验 + 缺失/偏低补种 (第二关 · P3-3b seed-before-serve)
#
# 用途：校验 5 张 @TableGenerator 高写表的 id_sequences 种子，若种子行缺失、或
#       next_val <= MAX(id)（seed-before-serve 被违反后的危险态）则安全修正。
# 语义：只会**抬高** next_val 到 MAX(id)+1000，绝不降低 → 永不重发已用主键。
#       修正在行锁 (SELECT ... FOR UPDATE) 内进行，与 app 的 pooled-lo 分配串行化。
#       健康态 (next_val > MAX(id)) 一律不动，交给运行中的 app。
# 安全：会写库（仅 id_sequences 一表）。默认交互确认；自动化用 ASSUME_YES=1。
#       只想看不想改 → DRY_RUN=1（等价于只读校验）。
#
# 环境：同 01 脚本 (DB_HOST/PORT/USER/PASS/NAME)。CentOS docker 需 mysql 客户端。
# 建议：修正类操作尽量在低峰执行（app 也在写 id_sequences）。
# =============================================================================
set -euo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-${MYSQL_PWD:-}}"
DB_NAME="${DB_NAME:-am}"
DRY_RUN="${DRY_RUN:-0}"
ASSUME_YES="${ASSUME_YES:-0}"
BUFFER=1000   # 与 schema.sql / IdSequenceSeedSchemaPatches 种子缓冲一致

command -v mysql >/dev/null 2>&1 || { echo "ERROR: 找不到 mysql 客户端 (yum install -y mysql 或 mariadb)"; exit 2; }

CNF="$(mktemp)"; trap 'rm -f "$CNF"' EXIT
cat >"$CNF" <<EOF
[client]
host=$DB_HOST
port=$DB_PORT
user=$DB_USER
password=$DB_PASS
EOF
mysql_q()  { mysql --defaults-extra-file="$CNF" -N -B "$DB_NAME" -e "$1"; }
mysql_run(){ mysql --defaults-extra-file="$CNF"       "$DB_NAME" -e "$1"; }

if [ -t 1 ]; then G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; B=$'\033[1m'; N=$'\033[0m'; else G=; R=; Y=; B=; N=; fi
mysql_q "SELECT 1" >/dev/null 2>&1 || { echo "ERROR: 连不上 ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"; exit 2; }
printf '%s目标库%s %s@%s:%s/%s   模式: %s\n' "$B" "$N" "$DB_USER" "$DB_HOST" "$DB_PORT" "$DB_NAME" \
  "$([ "$DRY_RUN" = 1 ] && echo '只读校验 (DRY_RUN)' || echo '校验+补种')"

TABLES="ai_session_event ai_session_message ai_session_audit git_commit git_commit_file"

# 幂等确保表存在（与 boot patch 同 DDL），不动已有数据
if [ "$DRY_RUN" != 1 ]; then
  mysql_run "CREATE TABLE IF NOT EXISTS id_sequences (seq_name VARCHAR(64) NOT NULL, next_val BIGINT NOT NULL, PRIMARY KEY (seq_name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT 'Hibernate 表生成器 id 预分配';"
fi

# 1) 现状快照 -----------------------------------------------------------------
printf '\n%s== 当前状态 ==%s\n' "$B" "$N"
NEED_FIX=0
declare -a PLAN=()
for t in $TABLES; do
  row="$(mysql_q "SELECT COALESCE(s.next_val,-1), COALESCE((SELECT MAX(id) FROM \`$t\`),0) FROM (SELECT 1) d LEFT JOIN id_sequences s ON s.seq_name='$t';")"
  nv="$(printf '%s' "$row" | cut -f1)"; mx="$(printf '%s' "$row" | cut -f2)"
  target=$((mx + BUFFER))
  if [ "$nv" = "-1" ]; then
    printf '  %s✗%s %-20s 种子缺失            MAX(id)=%s  → 将播种 next_val=%s\n' "$R" "$N" "$t" "$mx" "$target"
    PLAN+=("$t:$target"); NEED_FIX=1
  elif [ "$nv" -le "$mx" ]; then
    printf '  %s✗%s %-20s next_val=%s <= MAX(id)=%s  危险 → 将抬升到 %s\n' "$R" "$N" "$t" "$nv" "$mx" "$target"
    PLAN+=("$t:$target"); NEED_FIX=1
  else
    printf '  %s✓%s %-20s next_val=%s > MAX(id)=%s  健康 (缓冲 %s)\n' "$G" "$N" "$t" "$nv" "$mx" "$((nv-mx))"
  fi
done

if [ "$NEED_FIX" -eq 0 ]; then
  printf '\n结果: %sPASS%s — 5 张表种子全部健康，无需补种。\n' "$G" "$N"; exit 0
fi

if [ "$DRY_RUN" = 1 ]; then
  printf '\n结果: %sNEEDS-FIX%s — 有 %d 张表需补种/修正（DRY_RUN 未改动，去掉 DRY_RUN=1 执行修正）。\n' "$Y" "$N" "${#PLAN[@]}"; exit 1
fi

# 2) 确认 ---------------------------------------------------------------------
if [ "$ASSUME_YES" != 1 ]; then
  if [ -t 0 ]; then
    printf '\n%s将对上述 %d 张表 INSERT/抬升 next_val（只升不降）。继续? [y/N] %s' "$Y" "${#PLAN[@]}" "$N"
    read -r ans; case "$ans" in y|Y|yes|YES) ;; *) echo "已取消。"; exit 1;; esac
  else
    echo "ERROR: 非交互环境需显式 ASSUME_YES=1 才会写库。已中止。"; exit 1
  fi
fi

# 3) 修正（每表单连接事务 + FOR UPDATE 行锁，与 app 分配串行化）----------------
printf '\n%s== 执行补种/修正 ==%s\n' "$B" "$N"
for t in $TABLES; do
  # INSERT IGNORE 建种子；UPDATE 仅在 next_val<=MAX(id) 时抬升 → 幂等且只升不降
  mysql_run "
    START TRANSACTION;
    SET @mx := (SELECT COALESCE(MAX(id),0) FROM \`$t\`);
    INSERT IGNORE INTO id_sequences (seq_name, next_val) VALUES ('$t', @mx + $BUFFER);
    SELECT next_val FROM id_sequences WHERE seq_name='$t' FOR UPDATE;
    UPDATE id_sequences SET next_val = @mx + $BUFFER WHERE seq_name='$t' AND next_val <= @mx;
    COMMIT;" >/dev/null
  row="$(mysql_q "SELECT s.next_val, COALESCE((SELECT MAX(id) FROM \`$t\`),0) FROM id_sequences s WHERE s.seq_name='$t';")"
  nv="$(printf '%s' "$row" | cut -f1)"; mx="$(printf '%s' "$row" | cut -f2)"
  if [ "$nv" -gt "$mx" ]; then printf '  %s✓%s %-20s next_val=%s > MAX(id)=%s\n' "$G" "$N" "$t" "$nv" "$mx"
  else printf '  %s✗%s %-20s 修正后仍 next_val=%s <= MAX(id)=%s（并发写?重试或停写后再跑）\n' "$R" "$N" "$t" "$nv" "$mx"; fi
done
printf '\n完成。建议随后跑 01-verify-live-db.sh 复核。\n'
