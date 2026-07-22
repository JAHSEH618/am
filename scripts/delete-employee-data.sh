#!/usr/bin/env bash
#
# AIWatch 删除指定员工的全部服务端数据（docker compose 部署，走 docker exec 进 MySQL 容器）
#
#   ./scripts/delete-employee-data.sh <user_code>          # 预览：只统计各表行数，不删
#   ./scripts/delete-employee-data.sh <user_code> --yes    # 真删（单事务，删完复核归零）
#
#   CONTAINER=aiwatch-mysql-dev ./scripts/delete-employee-data.sh ...   # 覆盖 MySQL 容器名
#   FORCE=1 ./scripts/delete-employee-data.sh <user_code> --yes         # 跳过"客户端仍在心跳"拦截
#
# 前置：先在员工机器上执行 `aiwatchd uninstall --yes` 停掉客户端。
#       否则下一次 register 会自动重建 employee/agent_device，重装后 bootstrap 还会回灌 30 天历史。
#
# 删不干净的两处（脚本会打印涉及范围，需人工决定）：
#   - 团队级 analysis_report 正文可能提到该员工，SQL 无法精准擦除，只能整份删除；
#   - git_commit 全局按 (repo_url, commit_hash) 唯一，删除后这些提交也从项目视图消失。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

log() { printf '\033[0;36m[del-emp]\033[0m %s\n' "$*"; }
err() { printf '\033[0;31m[del-emp:ERROR]\033[0m %s\n' "$*" >&2; }
die() { err "$*"; exit 1; }

USER_CODE="${1:-}"
CONFIRM="${2:-}"
CONTAINER="${CONTAINER:-aiwatch-mysql}"

[ -n "$USER_CODE" ] || die "用法：$0 <user_code> [--yes]"
# 只允许安全字符，杜绝 SQL 注入（user_code 本身就是账号格式）
[[ "$USER_CODE" =~ ^[A-Za-z0-9._@-]+$ ]] || die "user_code 含非法字符（仅允许字母数字 . _ @ -）：$USER_CODE"

# ---- 读 .env 里的库凭据（与 docker-compose.yml 同源）----
[ -f .env ] || die "缺少 .env（部署机上应已存在，参考 docker-compose.yml 头部说明）"
DB_USERNAME="$(grep -E '^DB_USERNAME=' .env | tail -1 | cut -d= -f2-)"
DB_PASSWORD="$(grep -E '^DB_PASSWORD=' .env | tail -1 | cut -d= -f2-)"
[ -n "$DB_USERNAME" ] && [ -n "$DB_PASSWORD" ] || die ".env 里缺 DB_USERNAME / DB_PASSWORD"

docker ps --format '{{.Names}}' | grep -qxF "$CONTAINER" \
    || die "MySQL 容器 $CONTAINER 未在运行（docker compose ps 查看；可用 CONTAINER= 覆盖容器名）"

# 密码走环境变量传进容器，不落命令行
mysql_q() { docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$CONTAINER" \
    mysql -u"$DB_USERNAME" --default-character-set=utf8mb4 -N -B am; }

UC="'$USER_CODE'"

# ---- 存在性 + 各表行数预览 ----
exists="$(echo "SELECT COUNT(*) FROM employee WHERE user_code = $UC;" | mysql_q)"
[ "$exists" != "0" ] || log "employee 表里没有 $USER_CODE（可能已删过或从未注册），继续统计其余表残留 ..."

log "员工 $USER_CODE 各表数据量："
mysql_q <<SQL
SELECT 'employee',                    COUNT(*) FROM employee                WHERE user_code = $UC
UNION ALL SELECT 'agent_device',      COUNT(*) FROM agent_device            WHERE user_code = $UC
UNION ALL SELECT 'agent_nonce',       COUNT(*) FROM agent_nonce n JOIN agent_device d ON d.agent_id = n.agent_id WHERE d.user_code = $UC
UNION ALL SELECT 'agent_heartbeat',   COUNT(*) FROM agent_heartbeat         WHERE user_code = $UC
UNION ALL SELECT 'agent_alert',       COUNT(*) FROM agent_alert             WHERE user_code = $UC
UNION ALL SELECT 'work_session',      COUNT(*) FROM work_session            WHERE user_code = $UC
UNION ALL SELECT 'ai_session',        COUNT(*) FROM ai_session              WHERE user_code = $UC
UNION ALL SELECT 'ai_session_event',  COUNT(*) FROM ai_session_event        WHERE user_code = $UC
UNION ALL SELECT 'ai_session_message',COUNT(*) FROM ai_session_message      WHERE user_code = $UC
UNION ALL SELECT 'blob_link',         COUNT(*) FROM ai_session_message_blob_link l JOIN ai_session_message m ON m.id = l.message_id WHERE m.user_code = $UC
UNION ALL SELECT 'ai_session_audit',  COUNT(*) FROM ai_session_audit        WHERE user_code = $UC
UNION ALL SELECT 'git_commit',        COUNT(*) FROM git_commit              WHERE user_code = $UC
UNION ALL SELECT 'git_commit_file',   COUNT(*) FROM git_commit_file f JOIN git_commit c ON c.id = f.commit_id WHERE c.user_code = $UC
UNION ALL SELECT 'git_commit_attribution', COUNT(*) FROM git_commit_attribution WHERE user_code = $UC
UNION ALL SELECT 'daily_summary',     COUNT(*) FROM daily_summary           WHERE user_code = $UC
UNION ALL SELECT 'capability_daily',  COUNT(*) FROM capability_daily        WHERE user_code = $UC
UNION ALL SELECT 'usage_report',      COUNT(*) FROM usage_report            WHERE user_code = $UC
UNION ALL SELECT 'analysis_report_user', COUNT(*) FROM analysis_report_user WHERE user_code = $UC;
SQL

# ---- 涉及该员工的团队级报告（正文无法 SQL 擦除，只能整份人工处置）----
reports="$(echo "SELECT DISTINCT report_id FROM analysis_report_user WHERE user_code = $UC ORDER BY report_id;" | mysql_q | paste -sd, -)"
if [ -n "$reports" ]; then
    log "注意：以下 analysis_report 的正文可能提到该员工（需人工决定是否整份删除）：report_id = $reports"
fi

if [ "$CONFIRM" != "--yes" ]; then
    log "（预览模式，未删任何数据。确认无误后加 --yes 执行删除）"
    exit 0
fi

# ---- 客户端仍在上报则拦截：先卸载客户端，否则数据会被重建 ----
recent="$(echo "SELECT COUNT(*) FROM agent_heartbeat WHERE user_code = $UC AND event_time > NOW() - INTERVAL 10 MINUTE;" | mysql_q)"
if [ "$recent" != "0" ] && [ "${FORCE:-0}" != "1" ]; then
    die "该员工的 aiwatchd 最近 10 分钟内仍有心跳——先在其机器上 'aiwatchd uninstall --yes' 再删，否则数据会被自动重建（确要强删用 FORCE=1）"
fi

log "开始删除（单事务）..."
mysql_q <<SQL
START TRANSACTION;

DELETE l FROM ai_session_message_blob_link l
  JOIN ai_session_message m ON m.id = l.message_id WHERE m.user_code = $UC;
DELETE FROM ai_session_message WHERE user_code = $UC;
-- blob 跨用户按 sha256 去重，只清成为孤儿的
DELETE b FROM ai_session_message_blob b
  LEFT JOIN ai_session_message_blob_link l ON l.blob_id = b.id WHERE l.id IS NULL;

DELETE FROM ai_session_event WHERE user_code = $UC;
DELETE FROM ai_session_audit WHERE user_code = $UC;
DELETE FROM ai_session       WHERE user_code = $UC;

DELETE f FROM git_commit_file f
  JOIN git_commit c ON c.id = f.commit_id WHERE c.user_code = $UC;
DELETE FROM git_commit_attribution WHERE user_code = $UC;
DELETE FROM git_commit             WHERE user_code = $UC;

DELETE FROM daily_summary        WHERE user_code = $UC;
DELETE FROM capability_daily     WHERE user_code = $UC;
DELETE FROM work_session         WHERE user_code = $UC;
DELETE FROM usage_report         WHERE user_code = $UC;
DELETE FROM analysis_report_user WHERE user_code = $UC;

DELETE FROM agent_alert     WHERE user_code = $UC;
DELETE FROM agent_heartbeat WHERE user_code = $UC;
DELETE n FROM agent_nonce n
  JOIN agent_device d ON d.agent_id = n.agent_id WHERE d.user_code = $UC;
DELETE FROM agent_device WHERE user_code = $UC;
DELETE FROM employee     WHERE user_code = $UC;

COMMIT;
SQL

# ---- 复核：所有表应归零 ----
leftover="$(mysql_q <<SQL
SELECT SUM(c) FROM (
          SELECT COUNT(*) c FROM employee                WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM agent_device            WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM agent_heartbeat         WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM agent_alert             WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM work_session            WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM ai_session              WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM ai_session_event        WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM ai_session_message      WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM ai_session_audit        WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM git_commit              WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM git_commit_attribution  WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM daily_summary           WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM capability_daily        WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM usage_report            WHERE user_code = $UC
UNION ALL SELECT COUNT(*)   FROM analysis_report_user    WHERE user_code = $UC
) t;
SQL
)"
[ "$leftover" = "0" ] || die "删除后仍有 $leftover 行残留，请人工排查"

log "员工 $USER_CODE 的服务端数据已全部删除并复核归零。"
if [ -n "$reports" ]; then
    log "别忘了人工处置正文可能涉及该员工的报告：report_id = $reports（控制台 /analysis 或 DELETE /api/v1/admin/analysis/{reportId}）"
fi
