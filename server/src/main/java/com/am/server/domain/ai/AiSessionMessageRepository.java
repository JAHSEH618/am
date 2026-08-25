package com.am.server.domain.ai;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * AI 会话消息原文 Repository
 *
 * <p><b>窗内聚合 vs 会话累积</b>：
 * 本仓库提供「按 message_time 切片」的聚合查询（{@code aggregate*InWindow}），用于
 * 大盘 / 项目透视 / 模型分布 / 员工数据等所有按时间窗筛选的展示。<br>
 * <em>不要</em>用 {@code AiSession.inputTokens / userMessages} 这类会话级累积字段在窗口里求和——
 * 单个 session 可能跨期数周，按 last_activity 命中后取累积值会把历史消耗算进当前窗口，
 * 数据被严重放大。
 *
 * <p>所有窗内聚合的时间区间为 <b>[from, to)</b>（左闭右开），调用方把"to"传成"次日 0:00"即可。
 *
 * gz
 */
public interface AiSessionMessageRepository extends JpaRepository<AiSessionMessage, Long> {

    Optional<AiSessionMessage> findByAiSessionIdAndExternalMessageId(Long aiSessionId, String externalMessageId);

    /** NL skill 归因：取 sequence 严格小于给定序号的前一条 user 消息 */
    Optional<AiSessionMessage> findFirstByAiSessionIdAndRoleIgnoreCaseAndSequenceNoLessThanOrderBySequenceNoDesc(
            Long aiSessionId, String role, Integer sequenceNo);

    Page<AiSessionMessage> findByAiSessionIdOrderBySequenceNoAsc(Long aiSessionId, Pageable pageable);

    /** 详情页默认排序：最新在前（对话顺序倒序 → 时间 → 入库序号），免翻页即可看最新聊天。 */
    @Query("""
        SELECT m FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
        ORDER BY COALESCE(m.conversationOrder, 2147483647) DESC,
                 m.messageTime DESC,
                 m.sequenceNo DESC
        """)
    Page<AiSessionMessage> findByAiSessionIdOrderByConversationOrderDesc(
            @Param("sessionId") Long sessionId, Pageable pageable);

    @Modifying
    @Query("""
        UPDATE AiSessionMessage m
        SET m.conversationOrder = :conversationOrder,
            m.messageTime = :messageTime
        WHERE m.aiSessionId = :sessionId
          AND m.externalMessageId = :externalMessageId
          AND (
            m.conversationOrder IS NULL
            OR m.conversationOrder <> :conversationOrder
            OR m.messageTime <> :messageTime
          )
        """)
    int patchConversationOrderAndTime(
            @Param("sessionId") Long sessionId,
            @Param("externalMessageId") String externalMessageId,
            @Param("conversationOrder") int conversationOrder,
            @Param("messageTime") LocalDateTime messageTime);

    Integer countByAiSessionId(Long aiSessionId);

    /** 单 session 按 role（小写）聚合消息条数，供详情页 / ingest 与 Agent 快照对齐。 */
    @Query("""
            SELECT LOWER(m.role), COUNT(m)
            FROM AiSessionMessage m
            WHERE m.aiSessionId = :sessionId
            GROUP BY LOWER(m.role)
            """)
    List<Object[]> countGroupedByRoleForSession(@Param("sessionId") Long sessionId);

    @Query("SELECT COALESCE(MAX(m.sequenceNo), 0) FROM AiSessionMessage m WHERE m.aiSessionId = :aiSessionId")
    int maxSequenceNoByAiSessionId(@Param("aiSessionId") Long aiSessionId);

    /**
     * ingest 去重用：已存消息的 external_id **连同**排序字段一次取回。
     *
     * <p>只取 id 是不够的：拿不到 conversation_order / message_time 就只能对每条已存消息
     * 各发一条条件 UPDATE 去「试探」要不要改。Cursor 单次上报最多带 1000 条历史消息，
     * 那等于每个会话每一拍上千次写不动任何行的往返 —— DB 压力一大就被放大成分钟级请求。
     * 取回现值后在内存里比对，只有真变了才发 UPDATE，稳态是 0 次。
     */
    interface MessageOrderRow {
        String getExternalMessageId();

        Integer getConversationOrder();

        LocalDateTime getMessageTime();
    }

    @Query("""
        SELECT m.externalMessageId AS externalMessageId,
               m.conversationOrder AS conversationOrder,
               m.messageTime AS messageTime
        FROM AiSessionMessage m
        WHERE m.aiSessionId = :aiSessionId AND m.externalMessageId IS NOT NULL
        """)
    List<MessageOrderRow> findMessageOrderByAiSessionId(@Param("aiSessionId") Long aiSessionId);

    /** 聚合查询：把多个 session 的消息按 (sessionId, sequenceNo) 升序一次性拉出，
     *  供 DailySummaryAggregator 按日计算"首次响应时长 / 重试次数"。 */
    List<AiSessionMessage> findByAiSessionIdInOrderByAiSessionIdAscSequenceNoAsc(
            Collection<Long> aiSessionIds);

    /** 单个 session 的全部消息，按 sequence 升序 —— 当日切片由调用方按 messageTime 过滤 */
    List<AiSessionMessage> findByAiSessionIdOrderBySequenceNoAsc(Long aiSessionId);

    /** 单个 session + 时间窗口的消息分页（详情页"按筛选区间看对话"用） */
    Page<AiSessionMessage> findByAiSessionIdAndMessageTimeGreaterThanEqualAndMessageTimeLessThanOrderBySequenceNoAsc(
            Long aiSessionId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    @Query("""
        SELECT m FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
          AND m.messageTime >= :from AND m.messageTime < :to
        ORDER BY COALESCE(m.conversationOrder, 2147483647) DESC,
                 m.messageTime DESC,
                 m.sequenceNo DESC
        """)
    Page<AiSessionMessage> findByAiSessionIdAndMessageTimeWindowOrderByConversationOrderDesc(
            @Param("sessionId") Long sessionId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    /** 详情页「仅用户消息」：按 role 过滤（大小写不敏感） */
    @Query("""
        SELECT m FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
          AND LOWER(m.role) = LOWER(:role)
        ORDER BY m.sequenceNo ASC
        """)
    Page<AiSessionMessage> findByAiSessionIdAndRoleOrderBySequenceNoAsc(
            @Param("sessionId") Long sessionId, @Param("role") String role, Pageable pageable);

    @Query("""
        SELECT m FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
          AND LOWER(m.role) = LOWER(:role)
        ORDER BY COALESCE(m.conversationOrder, 2147483647) DESC,
                 m.messageTime DESC,
                 m.sequenceNo DESC
        """)
    Page<AiSessionMessage> findByAiSessionIdAndRoleOrderByConversationOrderDesc(
            @Param("sessionId") Long sessionId, @Param("role") String role, Pageable pageable);

    /** 详情页「仅用户消息」+ 时间窗 */
    @Query("""
        SELECT m FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
          AND LOWER(m.role) = LOWER(:role)
          AND m.messageTime >= :from AND m.messageTime < :to
        ORDER BY m.sequenceNo ASC
        """)
    Page<AiSessionMessage> findByAiSessionIdAndRoleInWindowOrderBySequenceNoAsc(
            @Param("sessionId") Long sessionId,
            @Param("role") String role,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    @Query("""
        SELECT m FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
          AND LOWER(m.role) = LOWER(:role)
          AND m.messageTime >= :from AND m.messageTime < :to
        ORDER BY COALESCE(m.conversationOrder, 2147483647) DESC,
                 m.messageTime DESC,
                 m.sequenceNo DESC
        """)
    Page<AiSessionMessage> findByAiSessionIdAndRoleInWindowOrderByConversationOrderDesc(
            @Param("sessionId") Long sessionId,
            @Param("role") String role,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    /** 详情页「多 role 过滤」：按 conversation_order 排序 */
    @Query("""
        SELECT m FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
          AND LOWER(m.role) IN :roles
        ORDER BY COALESCE(m.conversationOrder, 2147483647) DESC,
                 m.messageTime DESC,
                 m.sequenceNo DESC
        """)
    Page<AiSessionMessage> findByAiSessionIdAndRoleInOrderByConversationOrderDesc(
            @Param("sessionId") Long sessionId,
            @Param("roles") Collection<String> roles,
            Pageable pageable);

    @Query("""
        SELECT m FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
          AND LOWER(m.role) IN :roles
          AND m.messageTime >= :from AND m.messageTime < :to
        ORDER BY COALESCE(m.conversationOrder, 2147483647) DESC,
                 m.messageTime DESC,
                 m.sequenceNo DESC
        """)
    Page<AiSessionMessage> findByAiSessionIdAndRoleInWindowOrderByConversationOrderDesc(
            @Param("sessionId") Long sessionId,
            @Param("roles") Collection<String> roles,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    /**
     * 单 session 在窗内的消息聚合：单行 [inputTokens, outputTokens, messageCount]
     * <p>仅在 provider 上报了 recentMessages 时才有数据；窗内主真相源是 {@code AiSessionEventRepository.aggregateSessionWindow}，
     * 这里只用于会话详情页展示「窗内消息列表」时的辅助统计。
     * <p>用 {@code List<Object[]>} 而非 {@code Object[]} 以规避 Hibernate 6 单行包装行为差异。
     */
    @Query("""
        SELECT COALESCE(SUM(m.inputTokens), 0),
               COALESCE(SUM(m.outputTokens), 0),
               COUNT(m)
        FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
          AND m.messageTime >= :from AND m.messageTime < :to
        """)
    List<Object[]> aggregateSessionWindowMessages(Long sessionId, LocalDateTime from, LocalDateTime to);

    /**
     * 单 session 在窗内的「对话」聚合：单行 [inputTokens, outputTokens, conversationCount]。
     * <p>对话 = user + assistant + subagent，与会话详情页「对话」Tab / 摘要区口径一致。
     */
    @Query("""
        SELECT COALESCE(SUM(m.inputTokens), 0),
               COALESCE(SUM(m.outputTokens), 0),
               COUNT(m)
        FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
          AND LOWER(m.role) IN ('user', 'assistant', 'subagent')
          AND m.messageTime >= :from AND m.messageTime < :to
        """)
    List<Object[]> aggregateSessionWindowConversation(Long sessionId, LocalDateTime from, LocalDateTime to);

    /**
     * 拉某用户 + 某日的消息切片（按 sessionId, sequenceNo 升序）。
     * <p>给 DailySummaryAggregator 算 firstResponse / retry 用——这两个指标依赖
     * "用户 / 助手"语义，必须在 message 表上算。注意：只对上报了 recentMessages 的 provider 有效，
     * 其它 provider 的 firstResponse / retry 在该日为 0（合理：没有原文就推断不出节奏）。
     */
    @Query("""
        SELECT m FROM AiSessionMessage m
        WHERE m.userCode = :userCode
          AND m.messageTime >= :from AND m.messageTime < :to
        ORDER BY m.aiSessionId ASC, m.sequenceNo ASC
        """)
    List<AiSessionMessage> findByUserAndWindow(String userCode,
                                               LocalDateTime from, LocalDateTime to);

    /** {@link #findByUserAndWindow} 的 target_type 过滤版本（v2.10 起 DailySummaryAggregator 用）。 */
    @Query("""
        SELECT m FROM AiSessionMessage m JOIN AiSession s ON s.id = m.aiSessionId
        WHERE s.invalidReason IS NULL
          AND m.userCode = :userCode
          AND m.messageTime >= :from AND m.messageTime < :to
          AND m.targetType IN :activeTypes
        ORDER BY m.aiSessionId ASC, m.sequenceNo ASC
        """)
    List<AiSessionMessage> findByUserAndWindowAndTargetTypeIn(
            String userCode,
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") Collection<String> activeTypes);

    /**
     * DailySummary 切片：仅 ai_session_id / role / message_time，避免拉 MEDIUMTEXT。
     * 返回 [aiSessionId, role, messageTime]。
     */
    @Query("""
        SELECT m.aiSessionId, m.role, m.messageTime
        FROM AiSessionMessage m JOIN AiSession s ON s.id = m.aiSessionId
        WHERE s.invalidReason IS NULL
          AND m.userCode = :userCode
          AND m.messageTime >= :from AND m.messageTime < :to
          AND m.targetType IN :activeTypes
        ORDER BY m.aiSessionId ASC, m.sequenceNo ASC
        """)
    List<Object[]> findMessageSlicesByUserAndWindowAndTargetTypeIn(
            String userCode,
            LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    /** 审计 prompt：仅拉构建所需列，按 sequence 升序。返回 [role, contentText, contentPartsJson, sequenceNo]。 */
    @Query("""
        SELECT m.role, m.contentText, m.contentPartsJson, m.sequenceNo
        FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
        ORDER BY m.sequenceNo ASC
        """)
    List<Object[]> findAuditMessageFieldsByAiSessionIdOrderBySequenceNoAsc(@Param("sessionId") Long sessionId);

    @Query("""
        SELECT m.role, m.contentText, m.contentPartsJson, m.sequenceNo
        FROM AiSessionMessage m
        WHERE m.aiSessionId = :sessionId
        ORDER BY m.sequenceNo ASC
        """)
    List<Object[]> findAuditMessageFieldsSliceByAiSessionId(
            @Param("sessionId") Long sessionId, Pageable pageable);

    /**
     * 斜杠 Top：仅拉已有 slash_hits_json 的行（无 content_text）。返回 [userCode, sessionId, slashHitsJson, targetType]。
     */
    @Query("""
        SELECT m.userCode, m.aiSessionId, m.slashHitsJson, m.targetType
        FROM AiSessionMessage m JOIN AiSession s ON s.id = m.aiSessionId
        WHERE s.invalidReason IS NULL
          AND m.messageTime >= :from AND m.messageTime < :to
          AND m.targetType IN :activeTypes
          AND LOWER(m.role) = 'user'
          AND m.slashHitsJson IS NOT NULL AND TRIM(m.slashHitsJson) <> '' AND TRIM(m.slashHitsJson) <> '[]'
        """)
    List<Object[]> loadSlashHitsJsonOnlyInWindowGlobal(
            LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    /**
     * capability 日聚合（MCP 兜底路径）：窗内疑似含 MCP tool_call part 的消息。
     * 返回 [userCode, aiSessionId, contentPartsJson]。
     * <p>{@code LIKE '%mcp__%'} 宽松预滤（通配/大小写同 event 侧说明），part 级严格解析由
     * {@code com.am.server.aggregator.CapabilityDailyAggregator} 完成；调用方仅对
     * 「当日无 MCP TOOL_CALL 事件」的会话启用本路径，覆盖 hermes 这类基本不写 event、
     * message 密集的 provider，且不与 event 路径重复计数。
     */
    @Query("""
        SELECT m.userCode, m.aiSessionId, m.contentPartsJson
        FROM AiSessionMessage m JOIN AiSession s ON s.id = m.aiSessionId
        WHERE s.invalidReason IS NULL
          AND m.messageTime >= :from AND m.messageTime < :to
          AND m.targetType IN :activeTypes
          AND m.contentPartsJson LIKE '%mcp__%'
        """)
    List<Object[]> loadMcpContentPartsInWindowGlobal(
            LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    /**
     * 斜杠 Top 回算：仅缺 slash_hits_json 且有正文的 user 消息。返回 [userCode, sessionId, contentText, targetType]。
     */
    @Query("""
        SELECT m.userCode, m.aiSessionId, m.contentText, m.targetType
        FROM AiSessionMessage m JOIN AiSession s ON s.id = m.aiSessionId
        WHERE s.invalidReason IS NULL
          AND m.messageTime >= :from AND m.messageTime < :to
          AND m.targetType IN :activeTypes
          AND LOWER(m.role) = 'user'
          AND (m.slashHitsJson IS NULL OR TRIM(m.slashHitsJson) = '' OR TRIM(m.slashHitsJson) = '[]')
          AND m.contentText IS NOT NULL AND TRIM(m.contentText) <> ''
        """)
    List<Object[]> loadSlashFallbackContentInWindowGlobal(
            LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    /**
     * 窗口期内按员工 × role（小写）聚合消息条数，供员工数据「问答比」批量加载。
     * <p>区间 [from, to) 左闭右开。
     */
    @Query("""
            SELECT m.userCode, LOWER(m.role), COUNT(m)
            FROM AiSessionMessage m
            WHERE m.messageTime >= :from AND m.messageTime < :to
            GROUP BY m.userCode, LOWER(m.role)
            """)
    List<Object[]> countGroupedByUserAndRoleLowerInWindow(LocalDateTime from, LocalDateTime to);

    /**
     * v2.10：窗内员工 × role 聚合消息条数 —— 只统计 active target_type 的消息。
     * 系统设置里禁用的 agent 不进入员工数据问答比口径。
     *
     * <p>v1.3.3：问答比（user / assistant 条数）与 Slash Commands 合计原来是两条 SQL，
     * 扫的却是<b>同一批行</b>（同窗口、同 activeTypes、同 invalid 过滤）；员工数据列表每次
     * 打开都要把窗内 ai_session_message 走两遍。合成一条按员工分组的 SUM(CASE ...)，
     * 一次扫描出全部三个数。
     * <p>返回 {@code [user_code, userMsgCount, assistantMsgCount, slashCount]}。
     */
    @Query(nativeQuery = true, value = """
        SELECT m.user_code,
               SUM(CASE WHEN LOWER(m.role) = 'user' THEN 1 ELSE 0 END),
               SUM(CASE WHEN LOWER(m.role) = 'assistant' THEN 1 ELSE 0 END),
               COALESCE(SUM(CASE WHEN LOWER(m.role) = 'user'
                                 THEN m.slash_command_count + m.slash_skill_count ELSE 0 END), 0)
        FROM ai_session_message m
        INNER JOIN ai_session s ON s.id = m.ai_session_id
        WHERE s.invalid_reason IS NULL
          AND m.message_time >= :from AND m.message_time < :to
          AND m.target_type IN (:activeTypes)
        GROUP BY m.user_code
        """)
    List<Object[]> aggregatePeopleMessageStatsByUserInWindow(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    /**
     * 单员工窗口期内按 role（小写）聚合消息条数。
     */
    @Query("""
            SELECT LOWER(m.role), COUNT(m)
            FROM AiSessionMessage m
            WHERE m.userCode = :userCode
              AND m.messageTime >= :from AND m.messageTime < :to
            GROUP BY LOWER(m.role)
            """)
    List<Object[]> countGroupedByRoleLowerForUserInWindow(
            String userCode, LocalDateTime from, LocalDateTime to);

    /**
     * v2.10：单员工窗内按 role 聚合 —— 只统计 active target_type 的消息。
     *
     * <p>v1.3.3：员工详情原本为同一批行发三条 SQL——窗内 role 计数、Slash 合计、Slash 按天。
     * 按天分组一次查完：合计由调用方把各天相加得到，与原口径逐日等价（窗口边界都是自然日）。
     * <p>返回 {@code [work_date, userMsgCount, assistantMsgCount, slashCount]}。
     */
    @Query(nativeQuery = true, value = """
        SELECT DATE(m.message_time),
               SUM(CASE WHEN LOWER(m.role) = 'user' THEN 1 ELSE 0 END),
               SUM(CASE WHEN LOWER(m.role) = 'assistant' THEN 1 ELSE 0 END),
               COALESCE(SUM(CASE WHEN LOWER(m.role) = 'user'
                                 THEN m.slash_command_count + m.slash_skill_count ELSE 0 END), 0)
        FROM ai_session_message m
        INNER JOIN ai_session s ON s.id = m.ai_session_id
        WHERE s.invalid_reason IS NULL
          AND m.user_code = :userCode
          AND m.message_time >= :from AND m.message_time < :to
          AND m.target_type IN (:activeTypes)
        GROUP BY DATE(m.message_time)
        """)
    List<Object[]> aggregatePeopleMessageStatsByDayForUserInWindow(
            @Param("userCode") String userCode,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    /**
     * 分析报告：对给定会话，按用户汇总已落库的斜杠次数（入库时 {@link UserSlashInvocationExtractor} 写入）。
     * <p>返回 [user_code, sum_slash_command_count, sum_slash_skill_count]。
     */
    @Query(nativeQuery = true, value = """
        SELECT m.user_code,
               COALESCE(SUM(m.slash_command_count), 0),
               COALESCE(SUM(m.slash_skill_count), 0)
        FROM ai_session_message m
        INNER JOIN ai_session s ON s.id = m.ai_session_id
        WHERE s.invalid_reason IS NULL
          AND m.ai_session_id IN (:sessionIds)
          AND LOWER(m.role) = 'user'
        GROUP BY m.user_code
        """)
    List<Object[]> sumStoredSlashCountsByUserForSessions(@Param("sessionIds") Collection<Long> sessionIds);

    /**
     * 分析报告：拉取已落库的斜杠命中明细 JSON，供团队/个人饼图聚合。
     * <p>返回 [user_code, slash_hits_json]。
     */
    @Query(nativeQuery = true, value = """
        SELECT m.user_code, m.slash_hits_json
        FROM ai_session_message m
        INNER JOIN ai_session s ON s.id = m.ai_session_id
        WHERE s.invalid_reason IS NULL
          AND m.ai_session_id IN (:sessionIds)
          AND LOWER(m.role) = 'user'
          AND m.slash_hits_json IS NOT NULL
          AND m.slash_hits_json != 'null'
          AND m.slash_hits_json != '[]'
        """)
    List<Object[]> loadStoredSlashHitsJsonForSessions(@Param("sessionIds") Collection<Long> sessionIds);

    /**
     * AI 会话列表：拉取窗内 user 消息的斜杠命中 JSON，供合并去重枚举「本会话用过的命令 / 技能」。
     * <p>时间区间 [from, to) 左闭右开，与消息列表窗内筛选一致。
     * <p>返回 [ai_session_id, slash_hits_json]。
     */
    @Query(nativeQuery = true, value = """
        SELECT m.ai_session_id, m.slash_hits_json
        FROM ai_session_message m
        WHERE m.ai_session_id IN (:sessionIds)
          AND LOWER(m.role) = 'user'
          AND m.message_time >= :from AND m.message_time < :to
          AND m.slash_hits_json IS NOT NULL
          AND m.slash_hits_json != 'null'
          AND m.slash_hits_json != '[]'
        """)
    List<Object[]> loadSlashHitsJsonForSessionsInMessageWindow(
            @Param("sessionIds") Collection<Long> sessionIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 单用户窗内 user 消息的斜杠 Top —— 只拉已有 {@code slash_hits_json} 的行。
     * 返回 [slash_hits_json]。
     *
     * <p>v1.3.3：原来一条 SQL 同时 SELECT {@code content_text}（MEDIUMTEXT，消息原文不截断），
     * 于是员工详情每开一次就要把该员工窗内**全部提问原文**搬到应用层，只为在其中极少数
     * 缺 JSON 的历史行上回算斜杠命令——30 天窗口下这是几十 MB 级的结果集。
     * 拆成「仅 json」+「仅回算」两路后，稳态只走本条、且不碰大字段。
     * 与团队 Top 的 {@code loadSlashHitsJsonOnlyInWindowGlobal} 是同一套拆法（见性能归档 R2-H2）。
     */
    @Query(nativeQuery = true, value = """
        SELECT m.slash_hits_json
        FROM ai_session_message m
        INNER JOIN ai_session s ON s.id = m.ai_session_id
        WHERE s.invalid_reason IS NULL
          AND m.user_code = :userCode
          AND LOWER(m.role) = 'user'
          AND m.message_time >= :from AND m.message_time < :to
          AND m.target_type IN (:activeTypes)
          AND m.slash_hits_json IS NOT NULL
          AND m.slash_hits_json != 'null' AND m.slash_hits_json != '[]'
        """)
    List<String> loadSlashHitsJsonOnlyForUserInWindow(
            @Param("userCode") String userCode,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    /**
     * 单用户窗内斜杠 Top 回算：仅缺 {@code slash_hits_json} 且有正文的 user 消息。
     * 返回 [content_text, target_type]。
     */
    @Query(nativeQuery = true, value = """
        SELECT m.content_text, m.target_type
        FROM ai_session_message m
        INNER JOIN ai_session s ON s.id = m.ai_session_id
        WHERE s.invalid_reason IS NULL
          AND m.user_code = :userCode
          AND LOWER(m.role) = 'user'
          AND m.message_time >= :from AND m.message_time < :to
          AND m.target_type IN (:activeTypes)
          AND (m.slash_hits_json IS NULL
               OR m.slash_hits_json = 'null' OR m.slash_hits_json = '[]')
          AND m.content_text IS NOT NULL AND TRIM(m.content_text) != ''
        """)
    List<Object[]> loadSlashFallbackContentForUserInWindow(
            @Param("userCode") String userCode,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    /**
     * 窗内 user 消息（团队 Top）。返回 [user_code, ai_session_id, content_text, target_type, slash_hits_json]。
     */
    @Query(nativeQuery = true, value = """
        SELECT m.user_code, m.ai_session_id, m.content_text, m.target_type, m.slash_hits_json
        FROM ai_session_message m
        INNER JOIN ai_session s ON s.id = m.ai_session_id
        WHERE s.invalid_reason IS NULL
          AND LOWER(m.role) = 'user'
          AND m.message_time >= :from AND m.message_time < :to
          AND m.target_type IN (:activeTypes)
          AND (
            (m.slash_hits_json IS NOT NULL AND m.slash_hits_json != 'null' AND m.slash_hits_json != '[]')
            OR (m.content_text IS NOT NULL AND TRIM(m.content_text) != '')
          )
        """)
    List<Object[]> loadUserMessagesForSlashStatsInWindowGlobal(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    /**
     * 单项目窗内 user 消息，供斜杠 Top 聚合。返回 [content_text, target_type, slash_hits_json]。
     */
    @Query(nativeQuery = true, value = """
        SELECT m.content_text, m.target_type, m.slash_hits_json
        FROM ai_session_message m
        INNER JOIN ai_session s ON s.id = m.ai_session_id
        WHERE s.invalid_reason IS NULL
          AND s.project_name = :projectName
          AND LOWER(m.role) = 'user'
          AND m.message_time >= :from AND m.message_time < :to
          AND m.target_type IN (:activeTypes)
          AND (
            (m.slash_hits_json IS NOT NULL AND m.slash_hits_json != 'null' AND m.slash_hits_json != '[]')
            OR (m.content_text IS NOT NULL AND TRIM(m.content_text) != '')
          )
        """)
    List<Object[]> loadUserMessagesForSlashStatsByProjectInWindow(
            @Param("projectName") String projectName,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activeTypes") Collection<String> activeTypes);

    /** NL skill 启动回填：含 Read SKILL.md 的 tool/assistant 消息所在会话 */
    @Query("""
        SELECT DISTINCT m.aiSessionId FROM AiSessionMessage m
        WHERE LOWER(m.role) IN ('tool', 'assistant')
          AND (
            (m.contentPartsJson IS NOT NULL AND LOWER(m.contentPartsJson) LIKE '%skill.md%')
            OR (m.contentText IS NOT NULL AND LOWER(m.contentText) LIKE '%skill.md%')
          )
        """)
    List<Long> findSessionIdsWithSkillMdToolReads();
}
