-- =====================================================================================================
-- Token 数据异常 · 只读诊断（Dashboard「Token 走势」某天出现 ~2 万亿 input / ~100 亿 output 的离谱峰值）
--
-- 用法（只读，全部是 SELECT，可反复执行；建议在从库或低峰期跑，第 8 段会全表扫 ai_session）：
--   docker exec -i -e MYSQL_PWD="$DB_PASSWORD" aiwatch-mysql mysql -u"$DB_USERNAME" --default-character-set=utf8mb4 am \
--       < scripts/sql/token-anomaly-diagnose.sql
--   或在 mysql 客户端里 `source scripts/sql/token-anomaly-diagnose.sql`。
--
-- 本脚本不含任何 UPDATE / DELETE / DDL，也不读取任何消息正文（只有 id / 计数 / token 数值）。
-- 先改下面 @from / @to / @spike：[@from, @to) 是 ai_session.last_activity 的窗口，需包含峰值日。
--
-- 背景（详见 docs/ops/token-anomaly.md）：
--   Dashboard 趋势图 = SUM(daily_summary.total_*_tokens) 按 work_date；
--   daily_summary.total_* = 「last_activity 落在当日」的会话累计 ai_session.input/output_tokens 之和；
--   ai_session.input/output_tokens = 客户端上报的会话累计值，服务端（护栏上线前）原样采信。
--   ⇒ 只要有一个会话的累计值被客户端算大，它就会整块砸进它 last_activity 那一天；而会话滑出 48h 回看窗口后
--     不再被上报，坏值就永远留在库里，daily_summary 再怎么重算都是同一个坏值。
-- =====================================================================================================

SET @from        = '2026-09-19 00:00:00';   -- 窗口起（含）
SET @to          = '2026-09-24 00:00:00';   -- 窗口止（不含）
SET @spike       = '2026-09-21';            -- 峰值日
SET @sess_cap    = 2000000000;              -- 单会话 input/output 合理上限（与 aiwatch.ingest.max-session-tokens 默认一致：20 亿）
SET @delta_cap   = 500000000;               -- 单条 TOKEN_DELTA 合理上限（默认 5 亿）

-- -----------------------------------------------------------------------------------------------------
-- 0. 环境
-- -----------------------------------------------------------------------------------------------------
SELECT NOW() AS now_, @@version AS mysql_version, @@transaction_isolation AS isolation_level,
       @@global.binlog_expire_logs_seconds AS binlog_expire_seconds;

-- -----------------------------------------------------------------------------------------------------
-- 1. 复现图表：近 45 天每日 token 合计（= Dashboard 口径）+ 单用户日最大值 + 输入/输出比
--    读法：只有一天巨大、其余日子平常 ⇒ 少数会话的坏值；max_user_input ≈ input_tokens ⇒ 几乎全来自一个人。
-- -----------------------------------------------------------------------------------------------------
SELECT work_date,
       COUNT(*)                                             AS users,
       SUM(total_input_tokens)                              AS input_tokens,
       SUM(total_output_tokens)                             AS output_tokens,
       MAX(total_input_tokens)                              AS max_user_input,
       ROUND(SUM(total_input_tokens) / NULLIF(SUM(total_output_tokens), 0), 1) AS in_out_ratio
FROM daily_summary
WHERE work_date >= CURDATE() - INTERVAL 45 DAY
GROUP BY work_date
ORDER BY work_date;

-- 1b. 峰值日 daily_summary 里谁贡献（updated_time 能看出这行最后一次被重算是什么时候）
SELECT user_code, work_date, total_input_tokens, total_output_tokens,
       ai_session_count, ai_message_count, active_model_top, updated_time
FROM daily_summary
WHERE work_date = DATE(@spike)
ORDER BY total_input_tokens DESC
LIMIT 20;

-- -----------------------------------------------------------------------------------------------------
-- 2. Top 50 异常会话（窗口内按 max(input, output) 降序）
--    读法：
--      * span_hours 很小（几小时/一两天）却有万亿 token ⇒ 不可能是真实用量；
--      * agent_version < 1.3.3 ⇒ 吻合「子 agent 归并每 tick 原地累加」的旧客户端（1.3.3 才修复）；
--      * in_out_ratio 在 100~300 且各会话相近 ⇒ input 含缓存的 provider（codex / kimicode / 走兼容网关的 claude）。
-- -----------------------------------------------------------------------------------------------------
SELECT s.id, s.user_code, s.target_type, s.agent_id, d.agent_version, d.os_type, d.hostname,
       s.external_session_id, s.model,
       s.started_at, s.last_activity,
       TIMESTAMPDIFF(HOUR, s.started_at, s.last_activity)         AS span_hours,
       s.input_tokens, s.output_tokens, s.cache_read_tokens, s.cache_create_tokens,
       ROUND(s.input_tokens / NULLIF(s.output_tokens, 0), 1)       AS in_out_ratio,
       s.total_messages, s.invalid_reason, s.status, s.updated_time
FROM ai_session s
LEFT JOIN agent_device d ON d.agent_id = s.agent_id
WHERE s.last_activity >= @from AND s.last_activity < @to
ORDER BY GREATEST(s.input_tokens, s.output_tokens) DESC
LIMIT 50;

-- -----------------------------------------------------------------------------------------------------
-- 3. 分组：user_code × target_type × agent_id × 日 × model（Top 50，按 input 之和降序）
--    读法：是否集中在某一个 user / 某一种 provider / 某一台机器 / 某一个模型。
-- -----------------------------------------------------------------------------------------------------
SELECT s.user_code, s.target_type, s.agent_id, DATE(s.last_activity) AS last_activity_date, s.model,
       COUNT(*)              AS sessions,
       SUM(s.input_tokens)   AS input_tokens,
       SUM(s.output_tokens)  AS output_tokens,
       MAX(s.input_tokens)   AS max_session_input,
       MAX(s.output_tokens)  AS max_session_output
FROM ai_session s
WHERE s.last_activity >= @from AND s.last_activity < @to
GROUP BY s.user_code, s.target_type, s.agent_id, DATE(s.last_activity), s.model
ORDER BY input_tokens DESC
LIMIT 50;

-- -----------------------------------------------------------------------------------------------------
-- 4. 交叉验证：Top 50 会话的「会话累计值」vs「TOKEN_DELTA 事件之和」vs「消息级 token 之和」
--    这是区分两大类成因的关键：
--      verdict = TOTAL_INFLATED   会话累计远大于事件流之和、且事件本身正常
--                                 ⇒ 客户端把子 agent 的 token 反复累加进了会话累计（claude/codex/openclaw 的归并不产出
--                                    子 agent 的 delta，所以事件流是干净的，只有累计值虚高）——旧客户端 bug 的特征。
--      verdict = DELTA_INFLATED   单条 delta 就超过 @delta_cap ⇒ 把累计值当增量 / 单位错误 / cursor 增量重复追加。
--      verdict = NO_DELTA_EVENTS  该会话没有 TOKEN_DELTA（走 baseline 或快照差路径）。
--      verdict = CONSISTENT       累计 ≈ 事件之和（真实大会话，或坏值在事件流里也有）。
--    注意：新客户端 / 新服务端上线后 delta 已按 5 亿封顶，本表看的是护栏上线前的历史数据。
-- -----------------------------------------------------------------------------------------------------
WITH top_s AS (
    SELECT id, user_code, target_type, agent_id, last_activity, input_tokens, output_tokens
    FROM ai_session
    WHERE last_activity >= @from AND last_activity < @to
    ORDER BY GREATEST(input_tokens, output_tokens) DESC
    LIMIT 50
)
SELECT t.id, t.user_code, t.target_type, t.agent_id, t.last_activity,
       t.input_tokens  AS session_input,
       e.delta_input_sum, e.delta_input_max, e.delta_input_min, e.delta_events,
       ROUND(t.input_tokens / NULLIF(e.delta_input_sum, 0), 2)   AS session_over_delta_input,
       t.output_tokens AS session_output,
       e.delta_output_sum, e.delta_output_max,
       m.msg_input_sum, m.msg_output_sum, m.msg_rows,
       CASE
           WHEN e.delta_events IS NULL                                         THEN 'NO_DELTA_EVENTS'
           WHEN e.delta_input_max > @delta_cap OR e.delta_output_max > @delta_cap THEN 'DELTA_INFLATED'
           WHEN t.input_tokens > @sess_cap AND t.input_tokens > 10 * e.delta_input_sum THEN 'TOTAL_INFLATED'
           ELSE 'CONSISTENT'
       END AS verdict
FROM top_s t
LEFT JOIN (
    SELECT ev.ai_session_id,
           COUNT(*)                     AS delta_events,
           SUM(ev.input_tokens_delta)   AS delta_input_sum,
           MAX(ev.input_tokens_delta)   AS delta_input_max,
           MIN(ev.input_tokens_delta)   AS delta_input_min,
           SUM(ev.output_tokens_delta)  AS delta_output_sum,
           MAX(ev.output_tokens_delta)  AS delta_output_max
    FROM ai_session_event ev
    JOIN top_s x ON x.id = ev.ai_session_id
    WHERE ev.event_type = 'TOKEN_DELTA'
    GROUP BY ev.ai_session_id
) e ON e.ai_session_id = t.id
LEFT JOIN (
    SELECT ms.ai_session_id,
           COUNT(*)                AS msg_rows,
           SUM(ms.input_tokens)    AS msg_input_sum,
           SUM(ms.output_tokens)   AS msg_output_sum
    FROM ai_session_message ms
    JOIN top_s x ON x.id = ms.ai_session_id
    GROUP BY ms.ai_session_id
) m ON m.ai_session_id = t.id
ORDER BY GREATEST(t.input_tokens, t.output_tokens) DESC;

-- 4b. 事件流本身当天有没有峰值（idx_event_time 范围扫，窗口只有几天，很快）
--     峰值日事件流也是万亿级 ⇒ 坏值在 delta 里；只有会话累计是万亿级 ⇒ 坏值只在会话累计里。
SELECT DATE(event_time)                              AS event_date,
       COUNT(*)                                       AS token_delta_events,
       SUM(input_tokens_delta)                        AS input_delta_sum,
       SUM(output_tokens_delta)                       AS output_delta_sum,
       MAX(input_tokens_delta)                        AS max_single_input_delta,
       SUM(input_tokens_delta > @delta_cap OR output_tokens_delta > @delta_cap) AS over_cap_events,
       SUM(input_tokens_delta < 0 OR output_tokens_delta < 0)                   AS negative_events
FROM ai_session_event
WHERE event_type = 'TOKEN_DELTA' AND event_time >= @from AND event_time < @to
GROUP BY DATE(event_time)
ORDER BY event_date;

-- -----------------------------------------------------------------------------------------------------
-- 5. 按 agent_id 看是否集中在少数客户端 / 版本
-- -----------------------------------------------------------------------------------------------------
SELECT s.agent_id, d.user_code AS device_user, d.hostname, d.os_type, d.agent_version, d.last_seen_time,
       COUNT(*)                     AS sessions,
       SUM(s.input_tokens)          AS input_tokens,
       MAX(s.input_tokens)          AS max_session_input,
       ROUND(100 * SUM(s.input_tokens) / NULLIF(
           (SELECT SUM(input_tokens) FROM ai_session WHERE last_activity >= @from AND last_activity < @to), 0), 2) AS pct_of_window_input
FROM ai_session s
LEFT JOIN agent_device d ON d.agent_id = s.agent_id
WHERE s.last_activity >= @from AND s.last_activity < @to
GROUP BY s.agent_id, d.user_code, d.hostname, d.os_type, d.agent_version, d.last_seen_time
ORDER BY input_tokens DESC
LIMIT 30;

-- 5b. 全体设备的客户端版本分布（< 1.3.3 的还带着「子 agent 每 tick 累加」bug，要强制升级）
SELECT agent_version, COUNT(*) AS devices, SUM(last_seen_time >= NOW() - INTERVAL 2 DAY) AS seen_within_2d
FROM agent_device
GROUP BY agent_version
ORDER BY agent_version;

-- 5c. 越界会话（> @sess_cap）都属于哪些客户端版本 / provider
SELECT d.agent_version, s.target_type, COUNT(*) AS bad_sessions, SUM(s.input_tokens) AS input_tokens
FROM ai_session s
LEFT JOIN agent_device d ON d.agent_id = s.agent_id
WHERE s.input_tokens > @sess_cap OR s.output_tokens > @sess_cap
GROUP BY d.agent_version, s.target_type
ORDER BY bad_sessions DESC;

-- -----------------------------------------------------------------------------------------------------
-- 6. 集中度：峰值日是「一个会话」还是「整段历史被压到一天」
--    一个会话：top1_pct ≈ 100；整段历史压到一天：会话数远高于平常、sessions_started_before_7d 很多、单会话值都不大。
-- -----------------------------------------------------------------------------------------------------
SELECT DATE(last_activity)                                   AS last_activity_date,
       COUNT(*)                                              AS sessions,
       SUM(input_tokens)                                     AS input_tokens,
       MAX(input_tokens)                                     AS max_session_input,
       ROUND(100 * MAX(input_tokens) / NULLIF(SUM(input_tokens), 0), 2) AS top1_pct,
       SUM(started_at < last_activity - INTERVAL 7 DAY)      AS sessions_started_before_7d
FROM ai_session
WHERE last_activity >= CURDATE() - INTERVAL 45 DAY
GROUP BY DATE(last_activity)
ORDER BY last_activity_date;

SELECT ROUND(100 * (SELECT SUM(input_tokens) FROM (SELECT input_tokens FROM ai_session
                                                    WHERE last_activity >= DATE(@spike) AND last_activity < DATE(@spike) + INTERVAL 1 DAY
                                                    ORDER BY input_tokens DESC LIMIT 10) t10)
             / NULLIF((SELECT SUM(input_tokens) FROM ai_session
                       WHERE last_activity >= DATE(@spike) AND last_activity < DATE(@spike) + INTERVAL 1 DAY), 0), 2) AS top10_pct_on_spike_day;

-- -----------------------------------------------------------------------------------------------------
-- 7. daily_summary 有没有重复计数：与 ai_session 直接复算对账（同一 (user, 日)）
--    聚合 SQL 本身没有 JOIN，按 (user_code, work_date) 主键 upsert，不会翻倍；这里用数据证明。
--    有差异的行通常是「monitor_target 被禁用的 provider」「invalid 会话」「行还没被重算」，逐行看即可。
-- -----------------------------------------------------------------------------------------------------
SELECT ds.user_code, ds.work_date, ds.total_input_tokens AS summary_input, x.session_input,
       ds.total_input_tokens - x.session_input AS diff, ds.updated_time
FROM daily_summary ds
JOIN (SELECT user_code, DATE(last_activity) AS d, SUM(input_tokens) AS session_input
      FROM ai_session
      WHERE invalid_reason IS NULL AND last_activity >= @from AND last_activity < @to
      GROUP BY user_code, DATE(last_activity)) x
  ON x.user_code = ds.user_code AND x.d = ds.work_date
WHERE ds.total_input_tokens <> x.session_input
ORDER BY ABS(ds.total_input_tokens - x.session_input) DESC
LIMIT 50;

-- -----------------------------------------------------------------------------------------------------
-- 8. 全库范围的越界会话概览（不限峰值日；用于判断历史污染面 —— 全表扫 ai_session，请在低峰 / 从库执行）
-- -----------------------------------------------------------------------------------------------------
SELECT DATE(last_activity) AS last_activity_date, target_type,
       COUNT(*)            AS bad_sessions,
       SUM(input_tokens)   AS input_tokens,
       SUM(output_tokens)  AS output_tokens,
       MIN(id)             AS min_id,
       MAX(id)             AS max_id
FROM ai_session
WHERE input_tokens > @sess_cap OR output_tokens > @sess_cap OR input_tokens < 0 OR output_tokens < 0
   OR cache_read_tokens > 10 * @sess_cap OR cache_create_tokens > 10 * @sess_cap
GROUP BY DATE(last_activity), target_type
ORDER BY last_activity_date, target_type;

-- 8b. 事件流里的越界 / 负向 TOKEN_DELTA（历史上 fallback 快照差路径没有裁负数；全表扫 ai_session_event，谨慎）
--     默认注释掉；确需时按 event_time 加窗口后再放开。
-- SELECT DATE(event_time) AS d, COUNT(*) AS bad_events, SUM(input_tokens_delta) AS input_delta_sum
-- FROM ai_session_event
-- WHERE event_type = 'TOKEN_DELTA' AND event_time >= @from AND event_time < @to
--   AND (input_tokens_delta > @delta_cap OR output_tokens_delta > @delta_cap
--        OR input_tokens_delta < 0 OR output_tokens_delta < 0)
-- GROUP BY DATE(event_time);

-- -----------------------------------------------------------------------------------------------------
-- 9. 单位错误嫌疑：会话累计值恰好落在 epoch 秒 / 毫秒的量级（2014–2036 年）
--    「把时间戳当 token」的典型形态。命中很多行 ⇒ 某个 provider 读错了列 / 字段；只命中坏会话本身 ⇒ 不是这个成因。
-- -----------------------------------------------------------------------------------------------------
SELECT s.id, s.user_code, s.target_type, s.agent_id, s.last_activity, s.input_tokens, s.output_tokens,
       CASE WHEN s.input_tokens BETWEEN 1400000000000 AND 2100000000000 THEN 'input≈epoch_ms'
            WHEN s.input_tokens BETWEEN 1400000000    AND 2100000000    THEN 'input≈epoch_s'
            WHEN s.output_tokens BETWEEN 1400000000000 AND 2100000000000 THEN 'output≈epoch_ms'
            ELSE 'output≈epoch_s' END AS looks_like
FROM ai_session s
WHERE s.last_activity >= @from AND s.last_activity < @to
  AND (s.input_tokens  BETWEEN 1400000000 AND 2100000000
    OR s.input_tokens  BETWEEN 1400000000000 AND 2100000000000
    OR s.output_tokens BETWEEN 1400000000 AND 2100000000
    OR s.output_tokens BETWEEN 1400000000000 AND 2100000000000)
ORDER BY GREATEST(s.input_tokens, s.output_tokens) DESC
LIMIT 50;

-- -----------------------------------------------------------------------------------------------------
-- 读结果对照表（根因判别）
--   段 4 verdict=TOTAL_INFLATED 集中在 agent_version < 1.3.3 的少数设备
--       ⇒ 客户端「子 agent 归并原地改写缓存」旧 bug（1.3.3 已修，需强制升级）。这是代码层面可证明的机制。
--   段 4 verdict=DELTA_INFLATED，或段 4b 事件流当天也有万亿级
--       ⇒ 增量本身错了：累计值当增量 / provider 单位错误 / cursor 增量重复追加。看 target_type 定位 provider。
--   段 6 会话数远高于平常且 sessions_started_before_7d 很高、单会话值都不大
--       ⇒ 不是坏值，而是「大量老会话的 last_activity 被同时改到峰值日」，历史累计被压到一天（查 ai_session.updated_time
--          是否集中在同一时刻、是否有人跑过回填 / 重放）。
--   段 9 命中很多行 ⇒ 时间戳被当 token 读了。
-- =====================================================================================================
