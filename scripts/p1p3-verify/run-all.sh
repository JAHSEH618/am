#!/usr/bin/env bash
# =============================================================================
# P1-P3 验证一键编排 (run-all)
#
# 默认（安全）：只做**线上库只读**两步 —— 01 只读自检 + 03 种子校验(DRY_RUN 只报不改)。
# 开关放开写/测试：
#   FIX_SEED=1     03 进入修正模式（缺失/偏低就补种，只升不降；写 id_sequences 一表）
#   RUN_TESTS=1    追加跑 02 完整测试套件；**必须**用独立 CI 库(CI_DB_*)，绝不复用线上 DB_*
#
# 线上库(01/03)： DB_HOST/DB_PORT/DB_USER/DB_PASS/DB_NAME   (默认 127.0.0.1:3306 root / am)
# CI 库  (02)  ： CI_DB_HOST/CI_DB_PORT/CI_DB_USER/CI_DB_PASS/CI_DB_NAME
# 其它    ：ASSUME_YES=1 免交互确认；P1P3_ONLY / WITH_FRONTEND 透传给 02。
#
# 例：
#   # 已部署重启后，最小安全自检（纯只读）
#   DB_HOST=10.0.0.5 DB_USER=am DB_PASS=*** ./run-all.sh
#   # 自检 + 发现坏种子就修
#   DB_HOST=10.0.0.5 DB_USER=am DB_PASS=*** FIX_SEED=1 ./run-all.sh
#   # 全套：线上自检 + 独立 CI 库跑测试
#   DB_HOST=10.0.0.5 DB_USER=am DB_PASS=*** \
#     RUN_TESTS=1 CI_DB_HOST=ci-mysql CI_DB_USER=root CI_DB_PASS=*** ASSUME_YES=1 ./run-all.sh
# =============================================================================
set -uo pipefail   # 不用 -e：逐阶段自行捕获退出码，全部跑完再汇总

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIX_SEED="${FIX_SEED:-0}"
RUN_TESTS="${RUN_TESTS:-0}"
ASSUME_YES="${ASSUME_YES:-0}"

if [ -t 1 ]; then G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; B=$'\033[1m'; N=$'\033[0m'; else G=; R=; Y=; B=; N=; fi
banner() { printf '\n%s========== %s ==========%s\n' "$B" "$1" "$N"; }

# 结果记账
declare -a NAMES=() RCS=()
record() { NAMES+=("$1"); RCS+=("$2"); }

# --- 计划回显 ----------------------------------------------------------------
printf '%s运行计划%s\n' "$B" "$N"
printf '  1) 01-verify-live-db.sh      线上库只读自检           [always]\n'
printf '  2) 03-seed-verify...         种子%s   [%s]\n' \
  "$([ "$FIX_SEED" = 1 ] && echo '校验+补种(写库)' || echo '校验(DRY_RUN只读)')" \
  "$([ "$FIX_SEED" = 1 ] && echo 'FIX_SEED=1' || echo 'default')"
printf '  3) 02-run-ci-tests.sh        独立CI库跑JUnit全套      [%s]\n' \
  "$([ "$RUN_TESTS" = 1 ] && echo 'RUN_TESTS=1' || echo 'skipped')"

# --- 阶段 1：线上只读自检 -----------------------------------------------------
banner "1/3 · 01 线上只读自检"
"$SCRIPT_DIR/01-verify-live-db.sh"; rc=$?    # 必须紧跟：$? 会被后续任何命令/子shell覆盖
record "01 线上自检" "$rc"

# --- 阶段 2：种子校验（默认 DRY_RUN，FIX_SEED=1 才写）------------------------
if [ "$FIX_SEED" = 1 ]; then
  banner "2/3 · 03 id_sequences 种子校验+补种"
  ASSUME_YES="$ASSUME_YES" "$SCRIPT_DIR/03-seed-verify-id-sequences.sh"; rc=$?
  record "03 种子(修正)" "$rc"
else
  banner "2/3 · 03 id_sequences 种子校验(只读 DRY_RUN)"
  DRY_RUN=1 "$SCRIPT_DIR/03-seed-verify-id-sequences.sh"; rc=$?
  record "03 种子(只读)" "$rc"
fi

# --- 阶段 3：完整测试套件（独立 CI 库；默认跳过）----------------------------
banner "3/3 · 02 完整测试套件"
if [ "$RUN_TESTS" != 1 ]; then
  printf '  %s跳过%s（设 RUN_TESTS=1 + CI_DB_* 启用）\n' "$Y" "$N"
  record "02 测试套件" "skip"
elif [ -z "${CI_DB_HOST:-}" ] || [ -z "${CI_DB_USER:-}" ]; then
  printf '  %s拒绝运行%s：RUN_TESTS=1 但未提供 CI_DB_HOST/CI_DB_USER —— 拒绝拿线上 DB_* 跑破坏性测试。\n' "$R" "$N"
  record "02 测试套件" "1"
else
  DB_HOST="$CI_DB_HOST" \
  DB_PORT="${CI_DB_PORT:-3306}" \
  DB_USER="$CI_DB_USER" \
  DB_PASS="${CI_DB_PASS:-}" \
  DB_NAME="${CI_DB_NAME:-am}" \
  ASSUME_YES="$ASSUME_YES" \
  P1P3_ONLY="${P1P3_ONLY:-0}" \
  WITH_FRONTEND="${WITH_FRONTEND:-0}" \
    "$SCRIPT_DIR/02-run-ci-tests.sh"
  record "02 测试套件" "$?"
fi

# --- 汇总 --------------------------------------------------------------------
banner "汇总"
overall=0
for i in "${!NAMES[@]}"; do
  n="${NAMES[$i]}"; rc="${RCS[$i]}"
  if [ "$rc" = "skip" ]; then printf '  %s—%s %-16s SKIP\n' "$Y" "$N" "$n"
  elif [ "$rc" = "0" ];  then printf '  %s✓%s %-16s PASS\n' "$G" "$N" "$n"
  else printf '  %s✗%s %-16s FAIL (rc=%s)\n' "$R" "$N" "$n" "$rc"; overall=1; fi
done
printf '\n总体: %s\n' "$([ "$overall" = 0 ] && printf '%sPASS%s' "$G" "$N" || printf '%sFAIL%s' "$R" "$N")"
exit "$overall"
