-- =====================================================================================================
-- AI 使用统计 · 有记录以来（用户 × 编码工具 × 模型 × Token），直接输出 Markdown
--
-- 用法（只读：全部是 SELECT，不写库、不加锁、不读消息正文；在部署目录下执行，先 `set -a; . ./.env; set +a`）：
--   docker exec -i -e MYSQL_PWD="$DB_PASSWORD" aiwatch-mysql \
--       mysql -u"$DB_USERNAME" --default-character-set=utf8mb4 -N -B -r am \
--       < scripts/sql/usage-stats-all-time.sql > aiwatch-usage-$(date +%F).md
--   -N 去列名、-B 批模式、-r 不转义 —— 三个都要，否则输出不是合法 Markdown。
--
-- 数据源只有 ai_session（会话级累计值，一行一个会话）+ employee + monitor_target，不扫 ai_session_event，
-- 每段整表扫一次 ai_session，共 6 次；建议低峰期或从库执行（max_execution_time 封顶 5 分钟/段）。
--
-- 口径（与控制台一致）：
--   * Token 合计 = input + output；cache 写 / cache 读单列展示、不计入合计（Codex / Kimi 等的 input 本身已含缓存）。
--   * 只统计有效会话：invalid_reason IS NULL。
--   * 排除 token 越界会话：input 或 output < 0 或 > @sess_cap（= aiwatch.ingest.max-session-tokens 默认 20 亿，
--     与 DailySummaryAggregator#sumTokensGuarded 同口径，见 docs/ops/token-anomaly.md）；
--     cache 列单独越界（< 0 或 > @cache_cap）时只把该列记 0，不丢整个会话。
--   * 模型取 ai_session.model（一个会话一个模型；会话中途切模型时记最后一个），为空记「(未知)」。
--   * 有记录以来 = 不限时间。若要限定区间，改 @since / @until（按会话 last_activity 归属，整段会话算在最后活跃那天）。
-- =====================================================================================================

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET SESSION group_concat_max_len = 65535;
SET SESSION max_execution_time   = 300000;

SET @since        = '1970-01-01 00:00:00';  -- 会话 last_activity 起（含）
SET @until        = '2999-01-01 00:00:00';  -- 会话 last_activity 止（不含）
SET @only_enabled = 0;                      -- 1 = 只统计 monitor_target.enabled=1 的编码工具（控制台口径）；0 = 全部
SET @sess_cap     = 2000000000;             -- 单会话 input/output 上限（aiwatch.ingest.max-session-tokens）
SET @cache_cap    = 10000000000;            -- 单会话 cache_create/cache_read 上限（aiwatch.ingest.max-session-cache-tokens）

-- -----------------------------------------------------------------------------------------------------
-- 标题
-- -----------------------------------------------------------------------------------------------------
SELECT '# AIWatch AI 使用统计（有记录以来）';
SELECT '';
SELECT CONCAT('> 生成时间：', DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s'),
              ' ｜ 统计区间（按会话最后活跃时间）：',
              IF(@since <= '1970-01-01 00:00:00', '不限', @since), ' ~ ', IF(@until >= '2999-01-01 00:00:00', '不限', @until),
              ' ｜ 编码工具范围：', IF(@only_enabled = 1, '仅已启用', '全部'));
SELECT '>';
SELECT '> 口径：Token 合计 = Input + Output（Cache 单列、不计入合计）；仅有效会话；input/output 越界（<0 或 >20 亿）的坏值会话已排除；模型取会话最后使用的模型。';
SELECT '';

-- -----------------------------------------------------------------------------------------------------
-- 1. 总览
-- -----------------------------------------------------------------------------------------------------
SELECT '## 1. 总览';
SELECT '';
SELECT '| 指标 | 数值 |';
SELECT '| --- | ---: |';
WITH s AS (
    SELECT a.user_code,
           a.target_type,
           COALESCE(NULLIF(TRIM(a.model), ''), '(未知)')                            AS model,
           a.total_messages                                                        AS msgs,
           a.input_tokens                                                          AS in_t,
           a.output_tokens                                                         AS out_t,
           IF(a.cache_create_tokens BETWEEN 0 AND @cache_cap, a.cache_create_tokens, 0) AS cc_t,
           IF(a.cache_read_tokens   BETWEEN 0 AND @cache_cap, a.cache_read_tokens,   0) AS cr_t,
           a.started_at,
           a.last_activity
    FROM ai_session a
    WHERE a.invalid_reason IS NULL
      AND a.input_tokens  BETWEEN 0 AND @sess_cap
      AND a.output_tokens BETWEEN 0 AND @sess_cap
      AND a.last_activity >= @since AND a.last_activity < @until
      AND (@only_enabled = 0 OR a.target_type IN (SELECT type_code FROM monitor_target WHERE enabled = 1))
),
x AS (  -- 被排除的会话（同一时间 / 工具范围）
    SELECT SUM(a.invalid_reason IS NOT NULL)                                       AS invalid_n,
           SUM(a.invalid_reason IS NULL
               AND NOT (a.input_tokens  BETWEEN 0 AND @sess_cap
                    AND a.output_tokens BETWEEN 0 AND @sess_cap))                  AS anomalous_n
    FROM ai_session a
    WHERE a.last_activity >= @since AND a.last_activity < @until
      AND (@only_enabled = 0 OR a.target_type IN (SELECT type_code FROM monitor_target WHERE enabled = 1))
),
t AS (
    SELECT MIN(started_at) AS d0, MAX(last_activity) AS d1,
           COUNT(DISTINCT user_code) AS users, COUNT(*) AS sessions,
           COUNT(DISTINCT target_type) AS tools, COUNT(DISTINCT model) AS models,
           COALESCE(SUM(msgs), 0) AS msgs,
           COALESCE(SUM(in_t), 0) AS in_t, COALESCE(SUM(out_t), 0) AS out_t,
           COALESCE(SUM(cc_t), 0) AS cc_t, COALESCE(SUM(cr_t), 0) AS cr_t
    FROM s
)
SELECT line FROM (
              SELECT 1 AS ord, CONCAT('| 数据时间范围 | ', COALESCE(DATE_FORMAT(d0, '%Y-%m-%d'), '-'), ' ~ ',
                                      COALESCE(DATE_FORMAT(d1, '%Y-%m-%d'), '-'), ' |') AS line FROM t
    UNION ALL SELECT 2,  CONCAT('| 用户数 | ',              FORMAT(users, 0),           ' |') FROM t
    UNION ALL SELECT 3,  CONCAT('| 编码工具数 | ',          FORMAT(tools, 0),           ' |') FROM t
    UNION ALL SELECT 4,  CONCAT('| 模型数 | ',              FORMAT(models, 0),          ' |') FROM t
    UNION ALL SELECT 5,  CONCAT('| 会话数 | ',              FORMAT(sessions, 0),        ' |') FROM t
    UNION ALL SELECT 6,  CONCAT('| 消息数 | ',              FORMAT(msgs, 0),            ' |') FROM t
    UNION ALL SELECT 7,  CONCAT('| Input Tokens | ',        FORMAT(in_t, 0),            ' |') FROM t
    UNION ALL SELECT 8,  CONCAT('| Output Tokens | ',       FORMAT(out_t, 0),           ' |') FROM t
    UNION ALL SELECT 9,  CONCAT('| **Token 合计（In+Out）** | **', FORMAT(in_t + out_t, 0), '** |') FROM t
    UNION ALL SELECT 10, CONCAT('| Cache 写 Tokens | ',     FORMAT(cc_t, 0),            ' |') FROM t
    UNION ALL SELECT 11, CONCAT('| Cache 读 Tokens | ',     FORMAT(cr_t, 0),            ' |') FROM t
    UNION ALL SELECT 12, CONCAT('| 已排除：无效会话 | ',     FORMAT(COALESCE(invalid_n, 0), 0),   ' |') FROM x
    UNION ALL SELECT 13, CONCAT('| 已排除：Token 越界会话 | ', FORMAT(COALESCE(anomalous_n, 0), 0), ' |') FROM x
) r
ORDER BY ord;
SELECT '';

-- -----------------------------------------------------------------------------------------------------
-- 2. 按编码工具
-- -----------------------------------------------------------------------------------------------------
SELECT '## 2. 按编码工具';
SELECT '';
SELECT '| 编码工具 | 用户数 | 会话数 | 消息数 | 模型数 | Input | Output | Cache 写 | Cache 读 | Token 合计 | 占比 | 首次使用 | 最近使用 |';
SELECT '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |';
WITH s AS (
    SELECT a.user_code,
           COALESCE(mt.type_name, a.target_type)                                   AS tool,
           COALESCE(NULLIF(TRIM(a.model), ''), '(未知)')                            AS model,
           a.total_messages                                                        AS msgs,
           a.input_tokens                                                          AS in_t,
           a.output_tokens                                                         AS out_t,
           IF(a.cache_create_tokens BETWEEN 0 AND @cache_cap, a.cache_create_tokens, 0) AS cc_t,
           IF(a.cache_read_tokens   BETWEEN 0 AND @cache_cap, a.cache_read_tokens,   0) AS cr_t,
           a.started_at,
           a.last_activity
    FROM ai_session a
    LEFT JOIN monitor_target mt ON mt.type_code = a.target_type
    WHERE a.invalid_reason IS NULL
      AND a.input_tokens  BETWEEN 0 AND @sess_cap
      AND a.output_tokens BETWEEN 0 AND @sess_cap
      AND a.last_activity >= @since AND a.last_activity < @until
      AND (@only_enabled = 0 OR a.target_type IN (SELECT type_code FROM monitor_target WHERE enabled = 1))
),
g AS (
    SELECT tool,
           COUNT(DISTINCT user_code) AS users, COUNT(*) AS sessions, SUM(msgs) AS msgs,
           COUNT(DISTINCT model) AS models,
           SUM(in_t) AS in_t, SUM(out_t) AS out_t, SUM(cc_t) AS cc_t, SUM(cr_t) AS cr_t,
           SUM(in_t + out_t) AS total,
           MIN(started_at) AS d0, MAX(last_activity) AS d1
    FROM s
    GROUP BY tool
)
SELECT CONCAT('| ', REPLACE(tool, '|', '\\|'),
              ' | ', FORMAT(users, 0), ' | ', FORMAT(sessions, 0), ' | ', FORMAT(msgs, 0), ' | ', FORMAT(models, 0),
              ' | ', FORMAT(in_t, 0), ' | ', FORMAT(out_t, 0), ' | ', FORMAT(cc_t, 0), ' | ', FORMAT(cr_t, 0),
              ' | **', FORMAT(total, 0), '** | ',
              FORMAT(COALESCE(100 * total / NULLIF(SUM(total) OVER (), 0), 0), 2), '%',
              ' | ', DATE_FORMAT(d0, '%Y-%m-%d'), ' | ', DATE_FORMAT(d1, '%Y-%m-%d'), ' |')
FROM g
ORDER BY total DESC, sessions DESC, tool;
SELECT '';

-- -----------------------------------------------------------------------------------------------------
-- 3. 按模型
-- -----------------------------------------------------------------------------------------------------
SELECT '## 3. 按模型';
SELECT '';
SELECT '| 排名 | 模型 | 编码工具 | 用户数 | 会话数 | Input | Output | Cache 写 | Cache 读 | Token 合计 | 占比 |';
SELECT '| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |';
WITH s AS (
    SELECT a.user_code,
           COALESCE(mt.type_name, a.target_type)                                   AS tool,
           COALESCE(NULLIF(TRIM(a.model), ''), '(未知)')                            AS model,
           a.input_tokens                                                          AS in_t,
           a.output_tokens                                                         AS out_t,
           IF(a.cache_create_tokens BETWEEN 0 AND @cache_cap, a.cache_create_tokens, 0) AS cc_t,
           IF(a.cache_read_tokens   BETWEEN 0 AND @cache_cap, a.cache_read_tokens,   0) AS cr_t
    FROM ai_session a
    LEFT JOIN monitor_target mt ON mt.type_code = a.target_type
    WHERE a.invalid_reason IS NULL
      AND a.input_tokens  BETWEEN 0 AND @sess_cap
      AND a.output_tokens BETWEEN 0 AND @sess_cap
      AND a.last_activity >= @since AND a.last_activity < @until
      AND (@only_enabled = 0 OR a.target_type IN (SELECT type_code FROM monitor_target WHERE enabled = 1))
),
g AS (
    SELECT model,
           GROUP_CONCAT(DISTINCT tool ORDER BY tool SEPARATOR ', ') AS tools,
           COUNT(DISTINCT user_code) AS users, COUNT(*) AS sessions,
           SUM(in_t) AS in_t, SUM(out_t) AS out_t, SUM(cc_t) AS cc_t, SUM(cr_t) AS cr_t,
           SUM(in_t + out_t) AS total
    FROM s
    GROUP BY model
)
SELECT CONCAT('| ', ROW_NUMBER() OVER (ORDER BY total DESC, sessions DESC, model),
              ' | ', REPLACE(model, '|', '\\|'), ' | ', REPLACE(tools, '|', '\\|'),
              ' | ', FORMAT(users, 0), ' | ', FORMAT(sessions, 0),
              ' | ', FORMAT(in_t, 0), ' | ', FORMAT(out_t, 0), ' | ', FORMAT(cc_t, 0), ' | ', FORMAT(cr_t, 0),
              ' | **', FORMAT(total, 0), '** | ',
              FORMAT(COALESCE(100 * total / NULLIF(SUM(total) OVER (), 0), 0), 2), '% |')
FROM g
ORDER BY total DESC, sessions DESC, model;
SELECT '';

-- -----------------------------------------------------------------------------------------------------
-- 4. 按用户（排行）
-- -----------------------------------------------------------------------------------------------------
SELECT '## 4. 按用户';
SELECT '';
SELECT '| 排名 | 工号 | 姓名 | 部门 | 会话数 | 编码工具 | 主力模型 | Input | Output | Cache 写 | Cache 读 | Token 合计 | 占比 | 首次使用 | 最近使用 |';
SELECT '| ---: | --- | --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |';
WITH s AS (
    SELECT a.user_code,
           COALESCE(mt.type_name, a.target_type)                                   AS tool,
           COALESCE(NULLIF(TRIM(a.model), ''), '(未知)')                            AS model,
           a.input_tokens                                                          AS in_t,
           a.output_tokens                                                         AS out_t,
           IF(a.cache_create_tokens BETWEEN 0 AND @cache_cap, a.cache_create_tokens, 0) AS cc_t,
           IF(a.cache_read_tokens   BETWEEN 0 AND @cache_cap, a.cache_read_tokens,   0) AS cr_t,
           a.started_at,
           a.last_activity
    FROM ai_session a
    LEFT JOIN monitor_target mt ON mt.type_code = a.target_type
    WHERE a.invalid_reason IS NULL
      AND a.input_tokens  BETWEEN 0 AND @sess_cap
      AND a.output_tokens BETWEEN 0 AND @sess_cap
      AND a.last_activity >= @since AND a.last_activity < @until
      AND (@only_enabled = 0 OR a.target_type IN (SELECT type_code FROM monitor_target WHERE enabled = 1))
),
um AS (  -- 每人 token 最多的模型 = 主力模型
    SELECT user_code, model,
           ROW_NUMBER() OVER (PARTITION BY user_code ORDER BY SUM(in_t + out_t) DESC, COUNT(*) DESC, model) AS rn
    FROM s
    GROUP BY user_code, model
),
g AS (
    SELECT user_code,
           COUNT(*) AS sessions,
           GROUP_CONCAT(DISTINCT tool ORDER BY tool SEPARATOR ', ') AS tools,
           SUM(in_t) AS in_t, SUM(out_t) AS out_t, SUM(cc_t) AS cc_t, SUM(cr_t) AS cr_t,
           SUM(in_t + out_t) AS total,
           MIN(started_at) AS d0, MAX(last_activity) AS d1
    FROM s
    GROUP BY user_code
)
SELECT CONCAT('| ', ROW_NUMBER() OVER (ORDER BY g.total DESC, g.sessions DESC, g.user_code),
              ' | ', REPLACE(g.user_code, '|', '\\|'),
              ' | ', REPLACE(COALESCE(e.user_name, '-'), '|', '\\|'),
              ' | ', REPLACE(COALESCE(NULLIF(e.department, ''), '-'), '|', '\\|'),
              ' | ', FORMAT(g.sessions, 0),
              ' | ', REPLACE(g.tools, '|', '\\|'),
              ' | ', REPLACE(COALESCE(um.model, '-'), '|', '\\|'),
              ' | ', FORMAT(g.in_t, 0), ' | ', FORMAT(g.out_t, 0), ' | ', FORMAT(g.cc_t, 0), ' | ', FORMAT(g.cr_t, 0),
              ' | **', FORMAT(g.total, 0), '** | ',
              FORMAT(COALESCE(100 * g.total / NULLIF(SUM(g.total) OVER (), 0), 0), 2), '%',
              ' | ', DATE_FORMAT(g.d0, '%Y-%m-%d'), ' | ', DATE_FORMAT(g.d1, '%Y-%m-%d'), ' |')
FROM g
LEFT JOIN um       ON um.user_code = g.user_code AND um.rn = 1
LEFT JOIN employee e ON e.user_code = g.user_code
ORDER BY g.total DESC, g.sessions DESC, g.user_code;
SELECT '';

-- -----------------------------------------------------------------------------------------------------
-- 5. 用户 × 编码工具 × 模型 明细
-- -----------------------------------------------------------------------------------------------------
SELECT '## 5. 用户 × 编码工具 × 模型 明细';
SELECT '';
SELECT '> 按用户 Token 合计降序，同一用户内按 Token 合计降序；「用户内占比」= 该行 / 该用户合计。';
SELECT '';
SELECT '| 工号 | 姓名 | 编码工具 | 模型 | 会话数 | Input | Output | Cache 写 | Cache 读 | Token 合计 | 用户内占比 | 首次使用 | 最近使用 |';
SELECT '| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |';
WITH s AS (
    SELECT a.user_code,
           COALESCE(mt.type_name, a.target_type)                                   AS tool,
           COALESCE(NULLIF(TRIM(a.model), ''), '(未知)')                            AS model,
           a.input_tokens                                                          AS in_t,
           a.output_tokens                                                         AS out_t,
           IF(a.cache_create_tokens BETWEEN 0 AND @cache_cap, a.cache_create_tokens, 0) AS cc_t,
           IF(a.cache_read_tokens   BETWEEN 0 AND @cache_cap, a.cache_read_tokens,   0) AS cr_t,
           a.started_at,
           a.last_activity
    FROM ai_session a
    LEFT JOIN monitor_target mt ON mt.type_code = a.target_type
    WHERE a.invalid_reason IS NULL
      AND a.input_tokens  BETWEEN 0 AND @sess_cap
      AND a.output_tokens BETWEEN 0 AND @sess_cap
      AND a.last_activity >= @since AND a.last_activity < @until
      AND (@only_enabled = 0 OR a.target_type IN (SELECT type_code FROM monitor_target WHERE enabled = 1))
),
g AS (
    SELECT user_code, tool, model,
           COUNT(*) AS sessions,
           SUM(in_t) AS in_t, SUM(out_t) AS out_t, SUM(cc_t) AS cc_t, SUM(cr_t) AS cr_t,
           SUM(in_t + out_t) AS total,
           MIN(started_at) AS d0, MAX(last_activity) AS d1
    FROM s
    GROUP BY user_code, tool, model
),
w AS (
    SELECT g.*,
           SUM(total)    OVER (PARTITION BY user_code) AS user_total,
           SUM(sessions) OVER (PARTITION BY user_code) AS user_sessions
    FROM g
)
SELECT CONCAT('| ', REPLACE(w.user_code, '|', '\\|'),
              ' | ', REPLACE(COALESCE(e.user_name, '-'), '|', '\\|'),
              ' | ', REPLACE(w.tool, '|', '\\|'),
              ' | ', REPLACE(w.model, '|', '\\|'),
              ' | ', FORMAT(w.sessions, 0),
              ' | ', FORMAT(w.in_t, 0), ' | ', FORMAT(w.out_t, 0), ' | ', FORMAT(w.cc_t, 0), ' | ', FORMAT(w.cr_t, 0),
              ' | **', FORMAT(w.total, 0), '** | ',
              FORMAT(COALESCE(100 * w.total / NULLIF(w.user_total, 0), 0), 2), '%',
              ' | ', DATE_FORMAT(w.d0, '%Y-%m-%d'), ' | ', DATE_FORMAT(w.d1, '%Y-%m-%d'), ' |')
FROM w
LEFT JOIN employee e ON e.user_code = w.user_code
ORDER BY w.user_total DESC, w.user_sessions DESC, w.user_code, w.total DESC, w.sessions DESC, w.tool, w.model;
SELECT '';
