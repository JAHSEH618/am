#!/usr/bin/env bash
# =============================================================================
# P1-P3 真机 MySQL 全套测试 runner (第一关 · CI 完整套件)
#
# 用途：对一个 **一次性 / staging** MySQL 跑完整 JUnit 套件（含 @SpringBootTest 连真库的
#       并发/乐观锁/聚合/source_ref/表生成器批量测试），验证 P1-P3 的 DB 语义与并发正确性。
# 前提：docker 内需有 **JDK 17** + 本仓库源码（本脚本随仓库走）。走仓库自带 ./gradlew (Gradle 8.7)，
#       勿用全局 gradle 9.2.x。前端构建默认跳过（-x frontendBuild）→ 无需 node/pnpm/外网。
#
# ⚠️ 不要指向线上库！测试会写/删数据。默认交互确认，自动化用 ASSUME_YES=1。
#
# 环境：
#   DB_HOST(127.0.0.1) DB_PORT(3306) DB_USER(root) DB_PASS(必填) DB_NAME(am)
#   WITH_FRONTEND=1   一并构建前端（需 node/pnpm/外网；默认 0 跳过）
#   P1P3_ONLY=1       只跑 P1-P3 相关测试类（默认 0 = 全套）
#   ASSUME_YES=1      跳过“非线上库”确认
# =============================================================================
set -euo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-${MYSQL_PWD:-}}"
DB_NAME="${DB_NAME:-am}"
WITH_FRONTEND="${WITH_FRONTEND:-0}"
P1P3_ONLY="${P1P3_ONLY:-0}"
ASSUME_YES="${ASSUME_YES:-0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
SERVER="$REPO/server"
[ -x "$SERVER/gradlew" ] || { echo "ERROR: 找不到 $SERVER/gradlew（本脚本须随仓库运行）"; exit 2; }

# --- 前置检查 -----------------------------------------------------------------
command -v java >/dev/null 2>&1 || { echo "ERROR: 未装 JDK（需 JDK 17）"; exit 2; }
JV="$(java -version 2>&1 | head -1)"
echo "$JV" | grep -qE '"17\.' || echo "WARN: JDK 似乎不是 17 — $JV（build 可能失败）"
command -v mysql >/dev/null 2>&1 || { echo "ERROR: 找不到 mysql 客户端（用于建库/健康探测）"; exit 2; }

CNF="$(mktemp)"; trap 'rm -f "$CNF"' EXIT
cat >"$CNF" <<EOF
[client]
host=$DB_HOST
port=$DB_PORT
user=$DB_USER
password=$DB_PASS
EOF

# --- 防误伤线上库 -------------------------------------------------------------
echo "目标测试库: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "⚠️  测试会写入/删除该库数据 —— 必须是一次性/staging 库，不能是线上库。"
if [ "$ASSUME_YES" != 1 ]; then
  if [ -t 0 ]; then
    printf '确认该库非线上、可被测试污染? [y/N] '
    read -r ans; case "$ans" in y|Y|yes|YES) ;; *) echo "已取消。"; exit 1;; esac
  else
    echo "ERROR: 非交互环境需 ASSUME_YES=1 确认非线上库。已中止。"; exit 1
  fi
fi

# --- 等待 MySQL 就绪 ----------------------------------------------------------
echo "等待 MySQL 就绪..."
for i in $(seq 1 60); do
  if mysql --defaults-extra-file="$CNF" -N -B -e "SELECT 1" >/dev/null 2>&1; then echo "MySQL OK"; break; fi
  [ "$i" = 60 ] && { echo "ERROR: 60 次探测后 MySQL 仍不可用"; exit 2; }
  sleep 2
done

# --- 建库（空库即可，schema.sql 由测试启动时 spring.sql.init 幂等导入）--------
mysql --defaults-extra-file="$CNF" -e \
  "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "数据库 $DB_NAME 就绪"

# --- 覆盖测试数据源（Spring relaxed binding 覆盖 application-test.yml）--------
export SPRING_DATASOURCE_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
export SPRING_DATASOURCE_USERNAME="$DB_USER"
export SPRING_DATASOURCE_PASSWORD="$DB_PASS"

# --- 组装 gradle 参数 ---------------------------------------------------------
ARGS=(test)
[ "$WITH_FRONTEND" = 1 ] || ARGS+=(-x frontendBuild -x frontendInstall)  # 跳过前端 → 免 node/pnpm/外网

if [ "$P1P3_ONLY" = 1 ]; then
  for c in \
    com.am.server.config.JdbcBatchingConfigTest \
    com.am.server.aggregator.DailySummaryAggregatorIncrementalTest \
    com.am.server.aggregator.DailySummaryAggregatorSelfProxyTest \
    com.am.server.agent.ingest.AbstractAiSessionIngestServiceSourceRefTest \
    com.am.server.agent.ingest.AbstractAiSessionIngestServiceOptimisticRetryTest \
    com.am.server.domain.BatchInsertIdStrategyTest \
    com.am.server.domain.ai.AiSessionEventSourceRefFieldTest \
    com.am.server.domain.ai.AiSessionVersionTest \
    com.am.server.notify.OfflineDeviceAlerterTest ; do
    ARGS+=(--tests "$c")
  done
fi

echo "运行: ./gradlew ${ARGS[*]}"
cd "$SERVER"
set +e
./gradlew "${ARGS[@]}"
rc=$?
set -e

REPORT="$SERVER/build/reports/tests/test/index.html"
echo
if [ $rc -eq 0 ]; then echo "结果: PASS — 报告: $REPORT"
else echo "结果: FAIL (gradle rc=$rc) — 报告: $REPORT"; fi
exit $rc
