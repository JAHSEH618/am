package com.am.server.domain.ai;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * AI 会话事件流水 Repository
 * gz
 */
public interface AiSessionEventRepository extends JpaRepository<AiSessionEvent, Long> {

    List<AiSessionEvent> findByAiSessionIdOrderByEventTimeAsc(Long aiSessionId);

    List<AiSessionEvent> findByUserCodeAndEventTimeBetweenOrderByEventTimeDesc(
            String userCode, LocalDateTime from, LocalDateTime to);

    List<AiSessionEvent> findByEventTypeAndEventTimeBetween(
            String eventType, LocalDateTime from, LocalDateTime to);

    boolean existsByAiSessionIdAndEventTypeAndToolNameAndEventTime(
            Long aiSessionId, String eventType, String toolName, LocalDateTime eventTime);

    /** 预载会话既有 source_ref(P3-3a):COALESCE 列 + JSON 兜底,过渡期(回填未完)也正确。仅返回有 ref 的行。 */
    @Query(value = "SELECT COALESCE(source_ref, JSON_UNQUOTE(JSON_EXTRACT(extra_json, '$.source_ref'))) "
            + "FROM ai_session_event WHERE ai_session_id = :sessionId "
            + "AND (source_ref IS NOT NULL OR JSON_EXTRACT(extra_json, '$.source_ref') IS NOT NULL)",
            nativeQuery = true)
    List<String> findSourceRefsByAiSessionId(@Param("sessionId") Long sessionId);

    /** 会话是否已有带 source_ref 的逐条增量(列或 JSON 兜底)。 */
    @Query(value = "SELECT COUNT(*) FROM ai_session_event WHERE ai_session_id = :sessionId "
            + "AND (source_ref IS NOT NULL OR JSON_EXTRACT(extra_json, '$.source_ref') IS NOT NULL)",
            nativeQuery = true)
    long countByAiSessionIdWithAnySourceRef(@Param("sessionId") Long sessionId);

    Page<AiSessionEvent> findByAiSessionIdOrderByEventTimeDesc(Long aiSessionId, Pageable pageable);

    long countByEventTypeAndEventTimeAfter(String eventType, LocalDateTime since);

    long countByEventTimeAfter(LocalDateTime since);

    /** 工具排行：按 tool_name 聚合 TOOL_CALL 次数 */
    @Query("""
        SELECT e.toolName AS toolName,
               COUNT(e) AS count,
               COUNT(DISTINCT e.userCode) AS userCount,
               COUNT(DISTINCT e.aiSessionId) AS sessionCount
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventType = 'TOOL_CALL'
          AND e.eventTime BETWEEN :from AND :to
          AND e.toolName IS NOT NULL
        GROUP BY e.toolName
        ORDER BY COUNT(e) DESC
        """)
    List<Object[]> aggregateTools(LocalDateTime from, LocalDateTime to);

    /** v2.10：全局工具排行 —— 只统计 active target_type 的事件。 */
    @Query("""
        SELECT e.toolName AS toolName,
               COUNT(e) AS count,
               COUNT(DISTINCT e.userCode) AS userCount,
               COUNT(DISTINCT e.aiSessionId) AS sessionCount
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventType = 'TOOL_CALL'
          AND e.eventTime BETWEEN :from AND :to
          AND e.toolName IS NOT NULL
          AND e.targetType IN :activeTypes
        GROUP BY e.toolName
        ORDER BY COUNT(e) DESC
        """)
    List<Object[]> aggregateToolsAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /**
     * 单用户窗内 Top 工具：[toolName, count]
     * <p>修复 PeopleController 详情 Top 工具原本拿全量事件、未按 eventTime 截窗的 bug。
     */
    @Query("""
        SELECT e.toolName, COUNT(e)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.userCode = :userCode
          AND e.eventType = 'TOOL_CALL'
          AND e.toolName IS NOT NULL
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY e.toolName
        ORDER BY COUNT(e) DESC
        """)
    List<Object[]> aggregateToolsForUserInWindow(String userCode,
                                                 LocalDateTime from, LocalDateTime to);

    /**
     * v2.10：单用户窗内 Top 工具 —— 只统计 active target_type 的事件。
     */
    @Query("""
        SELECT e.toolName, COUNT(e)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.userCode = :userCode
          AND e.eventType = 'TOOL_CALL'
          AND e.toolName IS NOT NULL
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY e.toolName
        ORDER BY COUNT(e) DESC
        """)
    List<Object[]> aggregateToolsForUserInWindowAndTargetTypeIn(
            String userCode, LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /**
     * capability 日聚合：窗内 MCP 工具调用原始分组行，返回 [userCode, aiSessionId, toolName, count]。
     * <p>{@code LIKE 'mcp__%'} 只是宽松预滤（{@code _} 是单字符通配、utf8mb4_unicode_ci 大小写
     * 不敏感，Mcp__ / mcpXY 之类也会放过），server/tool 的严格解析与大小写合并由
     * {@code com.am.server.aggregator.CapabilityItemParser} 在 Java 侧完成。
     * <p>按 (user, session, toolName) 保留原始行而非直接按 toolName 聚合：同一 server/tool 的
     * 大小写变体要在解析后合并，会话去重必须发生在合并之后。
     */
    @Query("""
        SELECT e.userCode, e.aiSessionId, e.toolName, COUNT(e)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventType = 'TOOL_CALL'
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.toolName IS NOT NULL
          AND e.toolName LIKE 'mcp__%'
          AND e.targetType IN :activeTypes
        GROUP BY e.userCode, e.aiSessionId, e.toolName
        """)
    List<Object[]> aggregateMcpToolCallRowsInWindowAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /**
     * 归因引擎「commit 前最近活动事件」信号源：候选会话集在窗口内的 (sessionId, eventTime) 时间点。
     * 只取两列，量级 = 用户单日事件数；走 idx_session_time。
     */
    @Query("""
        SELECT e.aiSessionId, e.eventTime
        FROM AiSessionEvent e
        WHERE e.aiSessionId IN :sessionIds
          AND e.eventTime >= :from AND e.eventTime <= :to
        """)
    List<Object[]> findEventTimesBySessionIdsInWindow(
            @org.springframework.data.repository.query.Param("sessionIds") Collection<Long> sessionIds,
            LocalDateTime from, LocalDateTime to);

    /** 窗内 TOOL_CALL 事件总数（左闭右开） */
    @Query("""
        SELECT COUNT(e) FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventType = 'TOOL_CALL'
          AND e.eventTime >= :from AND e.eventTime < :to
        """)
    long countToolCallsInWindow(LocalDateTime from, LocalDateTime to);

    /** v2.10：窗内 TOOL_CALL 总数 —— 只统计 active target_type。 */
    @Query("""
        SELECT COUNT(e) FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventType = 'TOOL_CALL'
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        """)
    long countToolCallsInWindowAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /**
     * 拉某用户 + 某日的全部事件，按 eventTime 升序。
     * <p>DailySummaryAggregator 算 thinking 时长 / 当日 tool_call_count 用。
     */
    @Query("""
        SELECT e FROM AiSessionEvent e
        WHERE e.userCode = :userCode
          AND e.eventTime >= :from AND e.eventTime < :to
        ORDER BY e.eventTime ASC, e.id ASC
        """)
    List<AiSessionEvent> findByUserAndWindow(String userCode,
                                             LocalDateTime from, LocalDateTime to);

    /** 单个 session + 时间窗口的事件分页（详情页"按筛选区间看事件"用） */
    Page<AiSessionEvent> findByAiSessionIdAndEventTimeGreaterThanEqualAndEventTimeLessThanOrderByEventTimeDesc(
            Long aiSessionId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /** 实时事件流：取最近 N 条事件。id 逆序依赖单实例单调分配；当前无调用方。 */
    List<AiSessionEvent> findTop100ByOrderByIdDesc();

    // ============================================================
    // v2.4 窗内聚合：以 ai_session_event 为主真相源
    //
    // 原因：ai_session_message 行只有当 agent 上报了 recentMessages 时才会写，
    // Cursor / Claude Code 等会上报，但 Hermes / OpenHarness / OpenClaw 等很多
    // provider 只上报 session 累积值；ingest 则始终把累积差额写成 TOKEN_DELTA /
    // MESSAGE_DELTA 事件。所以 event 流是 100% 完整的"窗内增量真相"，按 event_time
    // 切片求 SUM(tokens_delta) / SUM(messages_delta) 是所有 provider 都能对得上的。
    //
    // v2.9 关键约束：所有窗内聚合都加上 eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
    // 白名单。原因：TOOL_CALL / STATUS_CHANGE 这两类事件在窗内单独存在不代表 session 真有"内容
    // 性活动"——典型场景就是会话停在 idle、客户端重启或 cursor IDE 在后台被点了下，agent 上报
    // 了一次 status flip 或 tool 列表，ingest 写入了若干 STATUS_CHANGE / TOOL_CALL，但 session
    // 既没新 token 也没新消息。老 SQL 把它们的 ai_session_id 也算进 COUNT(DISTINCT)，导致
    // 项目透视列表出现"会话数=N、消息=0、Token=0"的幽灵行；前端跳到 AI 会话列表又按
    // last_activity 过滤（last_activity 仍停在那一瞬，对应 AI 会话页可能为空），双口径错位。
    // 限定到 SESSION_OPEN/TOKEN_DELTA/MESSAGE_DELTA 后："窗内有这类 event" ↔ "session 在窗内
    // 真的产生了 token 或消息或新会话"，与 ai_session.last_activity / user_messages /
    // assistant_messages 完全同口径，list / detail / dashboard / 跳转 AI 会话列表全部对得齐。
    //
    // v2.11：所有 JOIN AiSession 的窗内聚合均带 s.invalidReason IS NULL，无效会话不进员工数据 /
    // 项目透视 / 模型 / 工具等平台统计。
    //
    // TOOL_CALL 自身的统计走 aggregateTools / aggregateToolsForUserInWindow / countToolCallsInWindow，
    // 不受影响。
    // ============================================================

    /**
     * 窗内总览（全 provider 兼容）：单行 [inputTokens, outputTokens, messageCount, distinctSessionCount, distinctUserCount, distinctProjectCount]
     * <p>统一声明 {@code List<Object[]>} 避免 Hibernate 6 对单行 {@code Object[]} 的包装行为差异。
     */
    @Query("""
        SELECT COALESCE(SUM(e.inputTokensDelta), 0),
               COALESCE(SUM(e.outputTokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId),
               COUNT(DISTINCT e.userCode),
               COUNT(DISTINCT s.projectName)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        """)
    List<Object[]> aggregateWindowTotals(LocalDateTime from, LocalDateTime to);

    /** v2.10：窗内总览 —— 只统计 active target_type 的事件。 */
    @Query("""
        SELECT COALESCE(SUM(e.inputTokensDelta), 0),
               COALESCE(SUM(e.outputTokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId),
               COUNT(DISTINCT e.userCode),
               COUNT(DISTINCT s.projectName)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        """)
    List<Object[]> aggregateWindowTotalsAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /**
     * dashboard /overview 专用：把窗内总览与 TOOL_CALL 计数合并成**一次**扫描。
     *
     * <p>原先 overview 对 ai_session_event 打两条独立查询（总览 + 工具调用数），窗口、
     * JOIN、target_type 过滤完全相同，等于把同一批行扫了两遍。合并后 WHERE 收 4 种
     * event_type，用条件聚合把两组指标一次算出。
     *
     * <p>返回顺序：[inputTokens, outputTokens, messages, sessions, users, projects, toolCalls]。
     * 前 6 项只统计非 TOOL_CALL 行，与合并前的口径逐项一致（见 DashboardOverviewQueryTest）。
     */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN e.eventType <> 'TOOL_CALL' THEN e.inputTokensDelta ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN e.eventType <> 'TOOL_CALL' THEN e.outputTokensDelta ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN e.eventType <> 'TOOL_CALL' THEN e.messagesDelta ELSE 0 END), 0),
               COUNT(DISTINCT CASE WHEN e.eventType <> 'TOOL_CALL' THEN e.aiSessionId END),
               COUNT(DISTINCT CASE WHEN e.eventType <> 'TOOL_CALL' THEN e.userCode END),
               COUNT(DISTINCT CASE WHEN e.eventType <> 'TOOL_CALL' THEN s.projectName END),
               COALESCE(SUM(CASE WHEN e.eventType = 'TOOL_CALL' THEN 1 ELSE 0 END), 0)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA','TOOL_CALL')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        """)
    List<Object[]> aggregateOverviewWindow(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 按 user_code 分组：[userCode, totalTokens, messageCount, sessionCount, projectCount] */
    @Query("""
        SELECT e.userCode,
               COALESCE(SUM(e.tokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId),
               COUNT(DISTINCT s.projectName)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY e.userCode
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC,
                 COUNT(DISTINCT e.aiSessionId) DESC
        """)
    List<Object[]> aggregateByUserInWindow(LocalDateTime from, LocalDateTime to);

    /** v2.10：按 user 分组 —— 只统计 active target_type。 */
    @Query("""
        SELECT e.userCode,
               COALESCE(SUM(e.tokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId),
               COUNT(DISTINCT s.projectName)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY e.userCode
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC,
                 COUNT(DISTINCT e.aiSessionId) DESC
        """)
    List<Object[]> aggregateByUserInWindowAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 按 project_name 分组：[projectName, totalTokens, messageCount, sessionCount, userCount] */
    @Query("""
        SELECT s.projectName,
               COALESCE(SUM(e.tokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId),
               COUNT(DISTINCT e.userCode)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY s.projectName
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC
        """)
    List<Object[]> aggregateByProjectInWindow(LocalDateTime from, LocalDateTime to);

    /** v2.10：按 project 分组 —— 只统计 active target_type。 */
    @Query("""
        SELECT s.projectName,
               COALESCE(SUM(e.tokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId),
               COUNT(DISTINCT e.userCode)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY s.projectName
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC
        """)
    List<Object[]> aggregateByProjectInWindowAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 按 model 分组：[model, inputTokens, outputTokens, messageCount, sessionCount, userCount] */
    @Query("""
        SELECT s.model,
               COALESCE(SUM(e.inputTokensDelta), 0),
               COALESCE(SUM(e.outputTokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId),
               COUNT(DISTINCT e.userCode)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.model IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY s.model
        ORDER BY COALESCE(SUM(e.inputTokensDelta), 0) + COALESCE(SUM(e.outputTokensDelta), 0) DESC
        """)
    List<Object[]> aggregateByModelInWindow(LocalDateTime from, LocalDateTime to);

    /** v2.10：按 model 分组 —— 只统计 active target_type。 */
    @Query("""
        SELECT s.model,
               COALESCE(SUM(e.inputTokensDelta), 0),
               COALESCE(SUM(e.outputTokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId),
               COUNT(DISTINCT e.userCode)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.model IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY s.model
        ORDER BY COALESCE(SUM(e.inputTokensDelta), 0) + COALESCE(SUM(e.outputTokensDelta), 0) DESC
        """)
    List<Object[]> aggregateByModelInWindowAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 单项目内贡献者：[userCode, totalTokens, messageCount, sessionCount] */
    @Query("""
        SELECT e.userCode,
               COALESCE(SUM(e.tokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName = :projectName
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY e.userCode
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC
        """)
    List<Object[]> aggregateContributorsByProject(String projectName,
                                                  LocalDateTime from, LocalDateTime to);

    /** v2.10：单项目贡献者 —— 只统计 active target_type。 */
    @Query("""
        SELECT e.userCode,
               COALESCE(SUM(e.tokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName = :projectName
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY e.userCode
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC
        """)
    List<Object[]> aggregateContributorsByProjectAndTargetTypeIn(
            String projectName, LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 单项目按日时间线：[date, totalTokens, messageCount, sessionCount, userCount] */
    @Query("""
        SELECT FUNCTION('DATE', e.eventTime),
               COALESCE(SUM(e.tokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId),
               COUNT(DISTINCT e.userCode)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName = :projectName
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY FUNCTION('DATE', e.eventTime)
        ORDER BY FUNCTION('DATE', e.eventTime)
        """)
    List<Object[]> aggregateProjectDailyTimeline(String projectName,
                                                 LocalDateTime from, LocalDateTime to);

    /** v2.10：单项目按日时间线 —— 只统计 active target_type。 */
    @Query("""
        SELECT FUNCTION('DATE', e.eventTime),
               COALESCE(SUM(e.tokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0),
               COUNT(DISTINCT e.aiSessionId),
               COUNT(DISTINCT e.userCode)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName = :projectName
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY FUNCTION('DATE', e.eventTime)
        ORDER BY FUNCTION('DATE', e.eventTime)
        """)
    List<Object[]> aggregateProjectDailyTimelineAndTargetTypeIn(
            String projectName, LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 单项目内 Top 模型：[model, totalTokens, sessionCount] */
    @Query("""
        SELECT s.model,
               COALESCE(SUM(e.tokensDelta), 0),
               COUNT(DISTINCT e.aiSessionId)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName = :projectName
          AND s.model IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY s.model
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC
        """)
    List<Object[]> aggregateModelsByProject(String projectName,
                                            LocalDateTime from, LocalDateTime to);

    /** v2.10：单项目内 Top 模型 —— 只统计 active target_type。 */
    @Query("""
        SELECT s.model,
               COALESCE(SUM(e.tokensDelta), 0),
               COUNT(DISTINCT e.aiSessionId)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName = :projectName
          AND s.model IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY s.model
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC
        """)
    List<Object[]> aggregateModelsByProjectAndTargetTypeIn(
            String projectName, LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 项目 × 模型矩阵：[projectName, model, totalTokens] */
    @Query("""
        SELECT s.projectName, s.model,
               COALESCE(SUM(e.tokensDelta), 0)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName IS NOT NULL
          AND s.model IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY s.projectName, s.model
        """)
    List<Object[]> aggregateProjectModelMatrix(LocalDateTime from, LocalDateTime to);

    /** v2.10：项目 × 模型矩阵 —— 只统计 active target_type。 */
    @Query("""
        SELECT s.projectName, s.model,
               COALESCE(SUM(e.tokensDelta), 0)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName IS NOT NULL
          AND s.model IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY s.projectName, s.model
        """)
    List<Object[]> aggregateProjectModelMatrixAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 项目元信息：[projectName, lastEventTime, repoUrl] */
    @Query("""
        SELECT s.projectName, MAX(e.eventTime), MAX(s.repoUrl)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY s.projectName
        """)
    List<Object[]> aggregateProjectMeta(LocalDateTime from, LocalDateTime to);

    /** v2.10：项目元信息 —— 只统计 active target_type。 */
    @Query("""
        SELECT s.projectName, MAX(e.eventTime), MAX(s.repoUrl)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.projectName IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY s.projectName
        """)
    List<Object[]> aggregateProjectMetaAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 单用户窗内 Top 模型：[model, totalTokens, sessionCount] */
    @Query("""
        SELECT s.model,
               COALESCE(SUM(e.tokensDelta), 0),
               COUNT(DISTINCT e.aiSessionId)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.userCode = :userCode
          AND s.model IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY s.model
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC
        """)
    List<Object[]> aggregateModelsForUserInWindow(String userCode,
                                                  LocalDateTime from, LocalDateTime to);

    /**
     * v2.10：单用户窗内 Top 模型 —— 只统计 active target_type 的事件。
     */
    @Query("""
        SELECT s.model,
               COALESCE(SUM(e.tokensDelta), 0),
               COUNT(DISTINCT e.aiSessionId)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.userCode = :userCode
          AND s.model IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY s.model
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC
        """)
    List<Object[]> aggregateModelsForUserInWindowAndTargetTypeIn(
            String userCode, LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 单用户窗内 Top 项目：[projectName, totalTokens, sessionCount] */
    @Query("""
        SELECT s.projectName,
               COALESCE(SUM(e.tokensDelta), 0),
               COUNT(DISTINCT e.aiSessionId)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.userCode = :userCode
          AND s.projectName IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY s.projectName
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC
        """)
    List<Object[]> aggregateProjectsForUserInWindow(String userCode,
                                                    LocalDateTime from, LocalDateTime to);

    /**
     * v2.10：单用户窗内 Top 项目 —— 只统计 active target_type 的事件。
     */
    @Query("""
        SELECT s.projectName,
               COALESCE(SUM(e.tokensDelta), 0),
               COUNT(DISTINCT e.aiSessionId)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.userCode = :userCode
          AND s.projectName IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY s.projectName
        ORDER BY COALESCE(SUM(e.tokensDelta), 0) DESC
        """)
    List<Object[]> aggregateProjectsForUserInWindowAndTargetTypeIn(
            String userCode, LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /** 模型 × 日期热力图：[date, model, totalTokens] */
    @Query("""
        SELECT FUNCTION('DATE', e.eventTime),
               s.model,
               COALESCE(SUM(e.tokensDelta), 0)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.model IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY FUNCTION('DATE', e.eventTime), s.model
        """)
    List<Object[]> aggregateModelHeatmap(LocalDateTime from, LocalDateTime to);

    /** v2.10：模型 × 日期热力图 —— 只统计 active target_type。 */
    @Query("""
        SELECT FUNCTION('DATE', e.eventTime),
               s.model,
               COALESCE(SUM(e.tokensDelta), 0)
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND s.model IS NOT NULL
          AND e.eventType IN ('SESSION_OPEN','TOKEN_DELTA','MESSAGE_DELTA')
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        GROUP BY FUNCTION('DATE', e.eventTime), s.model
        """)
    List<Object[]> aggregateModelHeatmapAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /**
     * 单 session + 时间窗的事件聚合：返回 List 包一行 [totalTokens, messageCount]
     * <p>注意：Hibernate 6 对返回 {@code Object[]} 的 JPQL 多列 select 行为有变化——
     * 直接声明 {@code Object[]} 时它返回的是 {@code Object[1]{Object[]}}（包一层），
     * 容易踩坑。这里统一声明 {@code List<Object[]>} 显式取第一行更可靠。
     */
    @Query("""
        SELECT COALESCE(SUM(e.tokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0)
        FROM AiSessionEvent e
        WHERE e.aiSessionId = :sessionId
          AND e.eventTime >= :from AND e.eventTime < :to
        """)
    List<Object[]> aggregateSessionWindow(Long sessionId, LocalDateTime from, LocalDateTime to);

    /**
     * 批量：多 session 在同一时间窗内的事件聚合。
     * 返回 [aiSessionId, totalTokens, messageCount]。
     */
    @Query("""
        SELECT e.aiSessionId,
               COALESCE(SUM(e.tokensDelta), 0),
               COALESCE(SUM(e.messagesDelta), 0)
        FROM AiSessionEvent e
        WHERE e.aiSessionId IN :sessionIds
          AND e.eventTime >= :from AND e.eventTime < :to
        GROUP BY e.aiSessionId
        """)
    List<Object[]> aggregateSessionWindowsForSessions(
            @Param("sessionIds") Collection<Long> sessionIds,
            LocalDateTime from, LocalDateTime to);

    /** 拉一个时间窗内有事件的所有 user_code，DailySummaryAggregator 用 */
    @Query("""
        SELECT DISTINCT e.userCode
        FROM AiSessionEvent e
        WHERE e.eventTime >= :from AND e.eventTime < :to
        """)
    List<String> findActiveUsersInWindow(LocalDateTime from, LocalDateTime to);

    /** 同上但仅统计 target_type ∈ activeTypes 的事件——v2.10 起 DailySummaryAggregator 走这条。 */
    @Query("""
        SELECT DISTINCT e.userCode
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        """)
    List<String> findActiveUsersInWindowAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") java.util.Collection<String> activeTypes);

    /** {@link #findByUserAndWindow} 的 target_type 过滤版本。 */
    @Query("""
        SELECT e FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.userCode = :userCode
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        ORDER BY e.eventTime ASC, e.id ASC
        """)
    List<AiSessionEvent> findByUserAndWindowAndTargetTypeIn(
            String userCode,
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * DailySummary 切片：仅活跃区间算法所需列。
     * 返回 [aiSessionId, eventTime, eventType, status]。
     */
    @Query("""
        SELECT e.aiSessionId, e.eventTime, e.eventType, e.status
        FROM AiSessionEvent e JOIN AiSession s ON s.id = e.aiSessionId
        WHERE s.invalidReason IS NULL
          AND e.userCode = :userCode
          AND e.eventTime >= :from AND e.eventTime < :to
          AND e.targetType IN :activeTypes
        ORDER BY e.eventTime ASC, e.id ASC
        """)
    List<Object[]> findEventSlicesByUserAndWindowAndTargetTypeIn(
            String userCode,
            LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    @Query("""
        SELECT e.toolName, e.eventTime FROM AiSessionEvent e
        WHERE e.aiSessionId = :sessionId
          AND e.eventType = 'TOOL_CALL'
          AND e.eventTime >= :from AND e.eventTime <= :to
        """)
    List<Object[]> findToolCallKeysByAiSessionIdAndEventTimeBetween(
            @Param("sessionId") Long sessionId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * v2.9 baseline backfill 用：返回某会话在 event 表里已存在的所有 distinct event_type。
     * <p>BaselineEventBackfillService 用它判断"这个 session 是否需要补 baseline"——
     * 已经有 SESSION_OPEN 就不再补 SESSION_OPEN，已经有 TOKEN_DELTA 就不再补 TOKEN_DELTA，
     * 避免 token 重复计入。
     */
    @Query("""
        SELECT DISTINCT e.eventType FROM AiSessionEvent e WHERE e.aiSessionId = :sessionId
        """)
    List<String> findDistinctEventTypesByAiSessionId(Long sessionId);

    /**
     * v2.9 view-time 过期检测用：返回 [from, to) 区间内每天最新 event_time。
     * <p>员工数据 / 报告页访问时，跟 daily_summary 同窗口的 MAX(updated_time) per day 比对——
     * 若某天最新事件时间 &gt; daily_summary 最大 updated_time，说明 backfill / 实时上报后
     * aggregator 还没追上，立即同步 ensureFresh 重聚（60s TTL 节流），保证用户看到的不是中间快照。
     * <p>用 native SQL 是因为 JPQL 没有跨方言通用的 DATE() 提取，nativeQuery 在 MySQL 下最干净。
     * <p>返回 {@code [work_date, max_event_time]}：[java.sql.Date, java.sql.Timestamp]。
     */
    @Query(nativeQuery = true, value = """
        SELECT DATE(event_time) AS work_date, MAX(event_time) AS latest
        FROM ai_session_event
        WHERE event_time >= :from AND event_time < :to
        GROUP BY DATE(event_time)
        """)
    List<Object[]> findMaxEventTimePerDay(LocalDateTime from, LocalDateTime to);

    /**
     * v2.10：只统计 active target_type 事件流，用于员工数据 ensureFresh 收口与 daily_summary
     * （也按 active types 聚合）对齐口径。
     *
     * <p><b>过期探针</b>：某个自然日 {@code [from, to)} 内是否还存在「晚于 :after」的有效事件。
     * 原实现按 {@code GROUP BY DATE(event_time)} 一次算出整窗每天的 MAX(event_time)——窗口拉到
     * 30 天时那就是把整整 30 天的事件流全扫一遍，而它的唯一用途只是回答一个是/否问题。
     * 改成按天探针后：稳态区间为空，走 {@code idx_target_event_time} 直接落空；有新事件时
     * 命中第一条即返回，代价与窗口长度无关。
     *
     * <p>{@code event_time >= :from} 与 {@code event_time > :after} 两个下界都写上：前者框住自然日，
     * 后者才是「比快照新」的语义，不依赖 DATETIME 的秒级精度做 ±1s 换算。
     */
    @Query(nativeQuery = true, value = """
        SELECT 1
        FROM ai_session_event e
        INNER JOIN ai_session s ON s.id = e.ai_session_id
        WHERE e.event_time >= :from AND e.event_time < :to
          AND e.event_time > :after
          AND e.target_type IN (:activeTypes)
          AND s.invalid_reason IS NULL
        LIMIT 1
        """)
    List<Integer> probeActiveEventAfter(
            LocalDateTime from, LocalDateTime to, LocalDateTime after,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);
}
