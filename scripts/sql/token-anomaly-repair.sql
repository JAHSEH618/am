-- =====================================================================================================
-- Token 数据异常 · 修复脚本（默认全部处于注释状态 —— 直接整份执行不会改任何数据）
--
-- 适用：Dashboard「Token 走势」出现坏值峰值（例如 2026-09-21 的 2.07 万亿 input / 102 亿 output），
--       诊断脚本 scripts/sql/token-anomaly-diagnose.sql 已确认是「会话累计值越界」而非真实用量。
--
-- ★ 先看这两条，能不动库就不动库 ★
--   方案 A（推荐先做，零写库）：部署带护栏的服务端（TokenSanitySupport / DailySummaryAggregator 跳过异常会话），
--          然后对受影响日期调用管理端重算接口（见步骤 6）。汇总层会跳过越界会话并打 ERROR 日志，趋势图立刻恢复；
--          ai_session 里的坏值原样保留（作为取证证据）。
--   方案 B（本脚本）：把坏会话的累计值改回可信值，再重算 daily_summary。仅当你还想让 ai_session /
--          员工数据 / 会话详情里的数字也回到正常时才做。
--
-- 执行顺序（不能乱）：
--   ① 先部署新服务端（护栏会拒绝旧客户端继续上报的越界值，否则仍在 48h 回看窗口内的旧客户端会把坏值重新写回来）；
--   ② 再执行本脚本（步骤 0 → 8，逐步、人工确认）；
--   ③ 最后强制客户端升级到带护栏的版本（诊断脚本 5b 可看谁还在 < 1.3.3）。
--
-- 使用方式：每个步骤是一个 "/* >>> STEP n …" 到 "<<< */" 的注释块。要执行某步：先读完该步说明，确认前一步的结果，
--           再把该块首尾两行（以 "/* >>>" 开头的那行与 "<<< */" 那行）删掉，然后单独执行该步。
--           **不要一次性去掉所有注释。** 块内以 "--" 开头的行始终是说明，不是要执行的语句。
--
-- 安全约定：
--   * 先备份（步骤 1 的备份表）→ 先算出新值只写备份表（步骤 2）→ 人工复核（步骤 3）→ 再分批应用（步骤 4）。
--   * 分批按主键：驱动表是备份表（STRAIGHT_JOIN 强制），逐行按 ai_session 主键等值定位，只加行锁，不做范围 / 全表 UPDATE
--     （REPEATABLE READ 下整表 UPDATE…JOIN 会把 ai_session 的间隙锁满，ingest 的 INSERT 等锁 50s 后整包失败，
--      2026-09 出过事故，见根 CLAUDE.md「启动期回填必须一次性 + 分批」）。
--   * 每行 UPDATE 带条件「当前值仍等于备份时的旧值」：期间被客户端重新上报过的行不会被覆盖。
--   * 同时 version = version + 1：正在进行的 ingest 事务（乐观锁）会因 version 变化重试，不会用旧值把修复写回去。
--   * 本脚本不读取 / 不修改任何消息正文；只碰 token 数值列。
--
-- 依赖 MySQL 8.0。生产库名 am。请在维护低峰执行。
-- =====================================================================================================

SET @from      = '2026-09-19 00:00:00';   -- 修复范围：ai_session.last_activity ∈ [@from, @to)。先只修峰值日附近，别一上来全库。
SET @to        = '2026-09-24 00:00:00';
SET @sess_cap  = 2000000000;              -- 单会话 input/output 上限（20 亿）
SET @cache_cap = 10000000000;             -- 单会话 cache_read/cache_create 上限（100 亿）
SET @delta_cap = 500000000;               -- 单条 TOKEN_DELTA 上限（5 亿）

-- -----------------------------------------------------------------------------------------------------
-- 步骤 0（只读，可直接执行）：候选会话数 —— 必须与诊断脚本第 8 段在同一窗口内的 bad_sessions 合计一致
-- -----------------------------------------------------------------------------------------------------
SELECT COUNT(*) AS candidate_sessions,
       SUM(input_tokens) AS candidate_input_sum, SUM(output_tokens) AS candidate_output_sum
FROM ai_session
WHERE last_activity >= @from AND last_activity < @to
  AND (input_tokens > @sess_cap OR output_tokens > @sess_cap OR input_tokens < 0 OR output_tokens < 0
       OR cache_read_tokens > @cache_cap OR cache_create_tokens > @cache_cap
       OR cache_read_tokens < 0 OR cache_create_tokens < 0);

/* >>> STEP 1 — 建备份 / 工作表并登记候选（只写新表，不动业务表）
-- 备份表保留至少 7 天，确认无误后才 DROP（步骤 9）。
CREATE TABLE ai_session_token_fix_20260929 (
    ai_session_id           BIGINT       NOT NULL,
    user_code               VARCHAR(64)  NOT NULL,
    target_type             VARCHAR(32)  NOT NULL,
    agent_id                VARCHAR(64)  NOT NULL,
    last_activity           DATETIME     NOT NULL,
    old_input_tokens        BIGINT       NOT NULL,
    old_output_tokens       BIGINT       NOT NULL,
    old_cache_create_tokens BIGINT       NOT NULL,
    old_cache_read_tokens   BIGINT       NOT NULL,
    new_input_tokens        BIGINT       DEFAULT NULL,
    new_output_tokens       BIGINT       DEFAULT NULL,
    new_cache_create_tokens BIGINT       DEFAULT NULL,
    new_cache_read_tokens   BIGINT       DEFAULT NULL,
    basis                   VARCHAR(16)  DEFAULT NULL COMMENT 'delta_sum / message_sum / zero',
    applied_at              DATETIME     DEFAULT NULL,
    backed_up_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (ai_session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'Token 坏值会话备份与修复计划（2026-09-29）';

INSERT INTO ai_session_token_fix_20260929
    (ai_session_id, user_code, target_type, agent_id, last_activity,
     old_input_tokens, old_output_tokens, old_cache_create_tokens, old_cache_read_tokens)
SELECT id, user_code, target_type, agent_id, last_activity,
       input_tokens, output_tokens, cache_create_tokens, cache_read_tokens
FROM ai_session
WHERE last_activity >= @from AND last_activity < @to
  AND (input_tokens > @sess_cap OR output_tokens > @sess_cap OR input_tokens < 0 OR output_tokens < 0
       OR cache_read_tokens > @cache_cap OR cache_create_tokens > @cache_cap
       OR cache_read_tokens < 0 OR cache_create_tokens < 0);

-- 复核：行数必须等于步骤 0 的 candidate_sessions
SELECT COUNT(*) AS backed_up FROM ai_session_token_fix_20260929;
<<< */

/* >>> STEP 2 — 计算修复值，只写备份表（仍不动 ai_session）
-- 逐列取值（input / output 各自判断，互不牵连）：
--   0) keep        原值本身合理（0 ≤ 值 ≤ @sess_cap）就保留（例如只有 cache 列越界的会话，input/output 不动）。
--   1) delta_sum   原值越界时，取该会话所有「合理」TOKEN_DELTA（0 ≤ 值 ≤ @delta_cap）之和，且 ≤ @sess_cap。
--                  旧客户端的子 agent 归并 bug 只把累计值算大、事件流是干净的，所以这一档命中最多；
--                  代价是子 agent 自己的 token 本来就没有 delta，修复后会略偏低（可接受：偏低好过虚高万倍）。
--   2) message_sum ai_session_message 里逐条 token 之和（同样要求合理）。多数 provider 消息级 token 为 0，命中较少。
--   3) zero        以上都不可用则置 0。
-- cache 两列：原值合理则保留，越界则置 0。
-- basis 列只是给人看的摘要（keep / delta_sum / message_sum / zero），以 new_* 为准。
UPDATE ai_session_token_fix_20260929 f
LEFT JOIN (SELECT ev.ai_session_id,
                  SUM(ev.input_tokens_delta) AS di, SUM(ev.output_tokens_delta) AS do_
           FROM ai_session_event ev
           JOIN ai_session_token_fix_20260929 x ON x.ai_session_id = ev.ai_session_id
           WHERE ev.event_type = 'TOKEN_DELTA'
             AND ev.input_tokens_delta  BETWEEN 0 AND @delta_cap
             AND ev.output_tokens_delta BETWEEN 0 AND @delta_cap
           GROUP BY ev.ai_session_id) e ON e.ai_session_id = f.ai_session_id
LEFT JOIN (SELECT ms.ai_session_id,
                  SUM(ms.input_tokens) AS mi, SUM(ms.output_tokens) AS mo
           FROM ai_session_message ms
           JOIN ai_session_token_fix_20260929 x ON x.ai_session_id = ms.ai_session_id
           WHERE ms.input_tokens >= 0 AND ms.output_tokens >= 0
           GROUP BY ms.ai_session_id) m ON m.ai_session_id = f.ai_session_id
SET f.new_input_tokens = CASE
        WHEN f.old_input_tokens BETWEEN 0 AND @sess_cap THEN f.old_input_tokens
        WHEN e.di BETWEEN 0 AND @sess_cap THEN e.di
        WHEN m.mi BETWEEN 0 AND @sess_cap THEN m.mi
        ELSE 0 END,
    f.new_output_tokens = CASE
        WHEN f.old_output_tokens BETWEEN 0 AND @sess_cap THEN f.old_output_tokens
        WHEN e.do_ BETWEEN 0 AND @sess_cap THEN e.do_
        WHEN m.mo BETWEEN 0 AND @sess_cap THEN m.mo
        ELSE 0 END,
    f.new_cache_create_tokens = CASE WHEN f.old_cache_create_tokens BETWEEN 0 AND @cache_cap THEN f.old_cache_create_tokens ELSE 0 END,
    f.new_cache_read_tokens   = CASE WHEN f.old_cache_read_tokens   BETWEEN 0 AND @cache_cap THEN f.old_cache_read_tokens   ELSE 0 END,
    f.basis = CASE
        WHEN f.old_input_tokens BETWEEN 0 AND @sess_cap AND f.old_output_tokens BETWEEN 0 AND @sess_cap THEN 'keep'
        WHEN e.di BETWEEN 0 AND @sess_cap OR e.do_ BETWEEN 0 AND @sess_cap THEN 'delta_sum'
        WHEN m.mi BETWEEN 0 AND @sess_cap OR m.mo BETWEEN 0 AND @sess_cap THEN 'message_sum'
        ELSE 'zero' END
WHERE f.applied_at IS NULL;
<<< */

/* >>> STEP 3 — 人工复核（只读）
-- 看清楚再进步骤 4：
--   * new_* 应当远小于 old_*；basis 分布是否符合预期（旧客户端 bug ⇒ 多数 delta_sum）；
--   * 有没有 new_* 全为 0 且 basis=zero 的「真·大会话」需要单独处理（说明它的事件流 / 消息都不可用）。
SELECT basis, COUNT(*) AS sessions, SUM(old_input_tokens) AS old_input_sum, SUM(new_input_tokens) AS new_input_sum,
       SUM(old_output_tokens) AS old_output_sum, SUM(new_output_tokens) AS new_output_sum
FROM ai_session_token_fix_20260929 GROUP BY basis;

SELECT ai_session_id, user_code, target_type, agent_id, last_activity, basis,
       old_input_tokens, new_input_tokens, old_output_tokens, new_output_tokens,
       old_cache_read_tokens, new_cache_read_tokens
FROM ai_session_token_fix_20260929
ORDER BY old_input_tokens DESC
LIMIT 100;
<<< */

/* >>> STEP 4 — 分批应用到 ai_session（每次执行处理一批；重复执行本块，直到 remaining = 0）
-- 驱动表是备份表（STRAIGHT_JOIN 强制），按 ai_session 主键等值定位；每批之间停 1~2 秒再执行下一批。
-- 条件「当前值仍等于旧值」保证：期间被重新上报过 / 已被别人改过的行不会被覆盖（这类行会留在 remaining 里，
-- 需要人工看一眼：客户端已经上报了新值，多半已经自愈）。
SET @lo = (SELECT MIN(ai_session_id) FROM ai_session_token_fix_20260929 WHERE applied_at IS NULL);
SET @hi = (SELECT MAX(t.ai_session_id) FROM (SELECT ai_session_id FROM ai_session_token_fix_20260929
                                             WHERE applied_at IS NULL ORDER BY ai_session_id LIMIT 200) t);

UPDATE ai_session_token_fix_20260929 f
STRAIGHT_JOIN ai_session s ON s.id = f.ai_session_id
SET s.input_tokens        = f.new_input_tokens,
    s.output_tokens       = f.new_output_tokens,
    s.cache_create_tokens = f.new_cache_create_tokens,
    s.cache_read_tokens   = f.new_cache_read_tokens,
    s.version             = s.version + 1,
    f.applied_at          = NOW()
WHERE f.applied_at IS NULL
  AND f.new_input_tokens IS NOT NULL
  AND f.ai_session_id BETWEEN @lo AND @hi
  AND s.input_tokens        = f.old_input_tokens
  AND s.output_tokens       = f.old_output_tokens
  AND s.cache_create_tokens = f.old_cache_create_tokens
  AND s.cache_read_tokens   = f.old_cache_read_tokens;

-- changed_rows 同时统计 ai_session 与备份表两边被改的行，约为本批会话数的 2 倍。
SELECT ROW_COUNT() AS changed_rows,
       (SELECT COUNT(*) FROM ai_session_token_fix_20260929 WHERE applied_at IS NULL) AS remaining;
<<< */

/* >>> STEP 5 — （可选）修复事件流里越界 / 负向的 TOKEN_DELTA
-- 仅当诊断脚本 4b 显示事件流本身也被污染（over_cap_events / negative_events > 0）才做；旧客户端子 agent 归并 bug
-- 造成的坏值只在会话累计里，事件流是干净的，通常不需要这一步。
-- 先把要改的事件备份，再置 0（置 0 而不是删除：保住 source_ref 去重锚点，客户端重发时不会被当成新事件再写一遍）。
CREATE TABLE ai_session_event_token_fix_20260929 (
    id                      BIGINT   NOT NULL,
    ai_session_id           BIGINT   NOT NULL,
    old_input_tokens_delta  BIGINT   DEFAULT NULL,
    old_output_tokens_delta BIGINT   DEFAULT NULL,
    old_tokens_delta        BIGINT   DEFAULT NULL,
    applied_at              DATETIME DEFAULT NULL,
    backed_up_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session (ai_session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'TOKEN_DELTA 坏事件备份（2026-09-29）';

-- 5a 备份：每次取 20 个尚未备份过的会话（按 ai_session_id 走 idx_session_time），重复执行直到 ROW_COUNT() 与 remaining_sessions 都为 0
INSERT IGNORE INTO ai_session_event_token_fix_20260929
    (id, ai_session_id, old_input_tokens_delta, old_output_tokens_delta, old_tokens_delta)
SELECT e.id, e.ai_session_id, e.input_tokens_delta, e.output_tokens_delta, e.tokens_delta
FROM ai_session_event e
JOIN (SELECT f.ai_session_id FROM ai_session_token_fix_20260929 f
      WHERE NOT EXISTS (SELECT 1 FROM ai_session_event_token_fix_20260929 b WHERE b.ai_session_id = f.ai_session_id)
      ORDER BY f.ai_session_id LIMIT 20) t ON t.ai_session_id = e.ai_session_id
WHERE e.event_type = 'TOKEN_DELTA'
  AND (e.input_tokens_delta > @delta_cap OR e.output_tokens_delta > @delta_cap
       OR e.input_tokens_delta < 0 OR e.output_tokens_delta < 0);

-- 5b 置 0：只改已备份的事件（按事件主键等值定位），每批 200 行；重复执行直到 remaining = 0。
SET @elo = (SELECT MIN(id) FROM ai_session_event_token_fix_20260929 WHERE applied_at IS NULL);
SET @ehi = (SELECT MAX(t.id) FROM (SELECT id FROM ai_session_event_token_fix_20260929
                                   WHERE applied_at IS NULL ORDER BY id LIMIT 200) t);

UPDATE ai_session_event_token_fix_20260929 b
STRAIGHT_JOIN ai_session_event e ON e.id = b.id
SET e.input_tokens_delta = 0, e.output_tokens_delta = 0, e.tokens_delta = 0,
    b.applied_at = NOW()
WHERE b.applied_at IS NULL
  AND b.id BETWEEN @elo AND @ehi
  AND e.input_tokens_delta  = b.old_input_tokens_delta
  AND e.output_tokens_delta = b.old_output_tokens_delta;

SELECT ROW_COUNT() AS changed_rows,
       (SELECT COUNT(*) FROM ai_session_event_token_fix_20260929 WHERE applied_at IS NULL) AS remaining;
<<< */

/* >>> STEP 6 — 重算受影响日期的 daily_summary（走管理端现成接口，幂等；不需要新增任何端点）
-- 1) 先列出受影响日期（只读）：
SELECT DISTINCT DATE(last_activity) AS work_date FROM ai_session_token_fix_20260929 ORDER BY work_date;

-- 2) 对每个日期调用一次（在 shell 里执行，不是 SQL；X-Admin-Token 或已登录会话均可）：
--      BASE=https://<服务地址>          # 生产 9527 端口 / 反代域名
--      for d in 2026-09-21; do
--        curl -sS -X POST -H "X-Admin-Token: $AIWATCH_ADMIN_TOKEN" "$BASE/api/v1/admin/aggregate-daily?date=$d"
--      done
--    或一次跑一段：
--      curl -sS -X POST -H "X-Admin-Token: $AIWATCH_ADMIN_TOKEN" "$BASE/api/v1/admin/aggregate-range?from=2026-09-19&to=2026-09-23"
--    接口内部走 DailySummaryAggregator#aggregate(date)：全员发现 → 按用户重算，按 (user_code, work_date) upsert。
--    返回里 users_touched 应 > 0；服务端日志会出现 "admin aggregate-daily date=…"。
--    若服务端已带护栏而此时 ai_session 里仍有坏值，日志里会有 ERROR "daily_summary token anomaly"（说明步骤 4 没改干净）。
-- 3) 控制台部分页面有 45s 级短 TTL 缓存：刷新前等一分钟。
<<< */

/* >>> STEP 7 — 验证（只读）
-- 期望：无越界会话；峰值日 daily_summary 与 ai_session 复算一致且量级正常。
SELECT COUNT(*) AS remaining_bad_sessions
FROM ai_session
WHERE last_activity >= @from AND last_activity < @to
  AND (input_tokens > @sess_cap OR output_tokens > @sess_cap OR input_tokens < 0 OR output_tokens < 0
       OR cache_read_tokens > @cache_cap OR cache_create_tokens > @cache_cap);

SELECT work_date, COUNT(*) AS users, SUM(total_input_tokens) AS input_tokens, SUM(total_output_tokens) AS output_tokens,
       MAX(updated_time) AS last_recomputed
FROM daily_summary
WHERE work_date >= DATE(@from) AND work_date < DATE(@to)
GROUP BY work_date ORDER BY work_date;
<<< */

/* >>> STEP 8 — 回滚（仅在步骤 4 之后发现修错了才用；每次一批，重复执行直到 remaining_applied = 0）
-- 同样按备份表逐行、带「当前值仍等于新值」的条件。回滚后要重新执行步骤 6。
SET @lo = (SELECT MIN(ai_session_id) FROM ai_session_token_fix_20260929 WHERE applied_at IS NOT NULL);
SET @hi = (SELECT MAX(t.ai_session_id) FROM (SELECT ai_session_id FROM ai_session_token_fix_20260929
                                             WHERE applied_at IS NOT NULL ORDER BY ai_session_id LIMIT 200) t);

UPDATE ai_session_token_fix_20260929 f
STRAIGHT_JOIN ai_session s ON s.id = f.ai_session_id
SET s.input_tokens        = f.old_input_tokens,
    s.output_tokens       = f.old_output_tokens,
    s.cache_create_tokens = f.old_cache_create_tokens,
    s.cache_read_tokens   = f.old_cache_read_tokens,
    s.version             = s.version + 1,
    f.applied_at          = NULL
WHERE f.applied_at IS NOT NULL
  AND f.ai_session_id BETWEEN @lo AND @hi
  AND s.input_tokens = f.new_input_tokens AND s.output_tokens = f.new_output_tokens;

SELECT ROW_COUNT() AS changed_rows,
       (SELECT COUNT(*) FROM ai_session_token_fix_20260929 WHERE applied_at IS NOT NULL) AS remaining_applied;

-- 事件流回滚（仅做过步骤 5 时；同样分批执行）：
-- SET @elo = (SELECT MIN(id) FROM ai_session_event_token_fix_20260929 WHERE applied_at IS NOT NULL);
-- SET @ehi = (SELECT MAX(t.id) FROM (SELECT id FROM ai_session_event_token_fix_20260929 WHERE applied_at IS NOT NULL ORDER BY id LIMIT 200) t);
-- UPDATE ai_session_event_token_fix_20260929 b STRAIGHT_JOIN ai_session_event e ON e.id = b.id
-- SET e.input_tokens_delta = b.old_input_tokens_delta, e.output_tokens_delta = b.old_output_tokens_delta,
--     e.tokens_delta = b.old_tokens_delta, b.applied_at = NULL
-- WHERE b.applied_at IS NOT NULL AND b.id BETWEEN @elo AND @ehi;
<<< */

/* >>> STEP 9 — 收尾（确认 ≥ 7 天无异常后）：删除备份 / 工作表
DROP TABLE IF EXISTS ai_session_event_token_fix_20260929;
DROP TABLE IF EXISTS ai_session_token_fix_20260929;
<<< */
