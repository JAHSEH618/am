package com.am.server.domain.ai;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AI 会话主表 Repository
 * gz
 */
public interface AiSessionRepository extends JpaRepository<AiSession, Long> {

    // ============================================================
    // v2.11 无效会话治理说明
    // ------------------------------------------------------------
    // ai_session 上新增 invalid_reason 字段：NULL = 有效；非空表示模型从未真正回应过
    // （典型场景：Claude Code 用户只敲了 /usage 等本地斜杠命令）。
    //
    // 所有「面向用户的会话视图」查询（管理页 Sessions 列表、Dashboard 在线 / 活跃口径）
    // 默认增加 s.invalidReason IS NULL 过滤；如果调用方需要看全量（如运维排查），调用
    // 带 IncludeInvalid 后缀的同名变体（无前缀的方法保留为「全量」语义）。
    //
    // 不变更的查询：
    //   - findByTargetTypeAndExternalSessionId：ingest upsert 必须能 hit 到任何状态的 session
    //   - findFirstByAgentIdOrderByLastActivityDesc：WorkSession 回填项目，与有效无关
    //   - findStaleNonIdle：兜底关停卡僵会话，理论上 invalid 会话已 idle 不会命中，
    //     但即使命中改 idle 也无害
    //   - findOverlappingByStartedOrLastActivity（无 target 约束）：仅应急运维全量扫描；
    //     业务链路（Git AI 协助 / Baseline 回填 / 分析报告）走 target ∩ invalid 过滤后的重载
    // ============================================================

    Optional<AiSession> findByTargetTypeAndExternalSessionId(String targetType, String externalSessionId);

    Page<AiSession> findByUserCodeOrderByLastActivityDesc(String userCode, Pageable pageable);

    /** 按多 userCode（姓名模糊命中后的工号列表） + 时间窗口过滤 */
    Page<AiSession> findByUserCodeInAndLastActivityAfterOrderByLastActivityDesc(
            List<String> userCodes, LocalDateTime since, Pageable pageable);

    Page<AiSession> findByLastActivityAfterOrderByLastActivityDesc(LocalDateTime since, Pageable pageable);

    /**
     * 按 Provider 类型 + 时间窗口过滤的列表查询（管理页用）。
     * <p>v2.11 起默认排除 invalid_reason 非空的无效会话；需要看全量请走
     * {@link #findByTargetTypeInAndLastActivityAfterIncludingInvalid}。
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE s.targetType IN :targetTypes
          AND s.lastActivity >= :since
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findByTargetTypeInAndLastActivityAfterOrderByLastActivityDesc(
            @Param("targetTypes") List<String> targetTypes,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    /** v2.11：管理员排查脏数据用——保留 invalid 会话的全量列表。 */
    @Query("""
        SELECT s FROM AiSession s
        WHERE s.targetType IN :targetTypes
          AND s.lastActivity >= :since
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findByTargetTypeInAndLastActivityAfterIncludingInvalid(
            @Param("targetTypes") List<String> targetTypes,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    /** 按 Provider 类型 + 用户过滤的列表查询。 */
    Page<AiSession> findByTargetTypeInAndUserCodeOrderByLastActivityDesc(
            List<String> targetTypes, String userCode, Pageable pageable);

    /**
     * 按 Provider 类型 + 多 userCode + 时间窗口过滤。
     * <p>v2.11 起默认排除 invalid_reason 非空的无效会话；运维场景请走
     * {@link #findByTargetTypeInAndUserCodeInAndLastActivityAfterIncludingInvalid}。
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE s.targetType IN :targetTypes
          AND s.userCode IN :userCodes
          AND s.lastActivity >= :since
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findByTargetTypeInAndUserCodeInAndLastActivityAfterOrderByLastActivityDesc(
            @Param("targetTypes") List<String> targetTypes,
            @Param("userCodes") List<String> userCodes,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    @Query("""
        SELECT s FROM AiSession s
        WHERE s.targetType IN :targetTypes
          AND s.userCode IN :userCodes
          AND s.lastActivity >= :since
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findByTargetTypeInAndUserCodeInAndLastActivityAfterIncludingInvalid(
            @Param("targetTypes") List<String> targetTypes,
            @Param("userCodes") List<String> userCodes,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    /** 按项目名 + 时间窗口过滤；项目透视页"会话数超链 → Sessions"跳转链使用 */
    Page<AiSession> findByProjectNameAndLastActivityAfterOrderByLastActivityDesc(
            String projectName, LocalDateTime since, Pageable pageable);

    Page<AiSession> findByProjectNameAndUserCodeOrderByLastActivityDesc(
            String projectName, String userCode, Pageable pageable);

    /**
     * v2.10：projectName + target_type 白名单 + 时间窗口；与上面同语义，多一层 target_type 过滤。
     * <p>v2.11 起默认排除 invalid_reason 非空的无效会话。
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE s.projectName = :projectName
          AND s.targetType IN :targetTypes
          AND s.lastActivity >= :since
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findByProjectNameAndTargetTypeInAndLastActivityAfter(
            @Param("projectName") String projectName,
            @Param("targetTypes") List<String> targetTypes,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    @Query("""
        SELECT s FROM AiSession s
        WHERE s.projectName = :projectName
          AND s.targetType IN :targetTypes
          AND s.lastActivity >= :since
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findByProjectNameAndTargetTypeInAndLastActivityAfterIncludingInvalid(
            @Param("projectName") String projectName,
            @Param("targetTypes") List<String> targetTypes,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    /**
     * AI 会话列表时间窗：<b>与分析报告同源</b>——半开区间 {@code [t0, t1)} 内，
     * {@code started_at} 或 {@code last_activity} 任一落入即命中（等价于 overlap）。
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE s.targetType IN :targetTypes
          AND (
            (s.startedAt >= :t0 AND s.startedAt < :t1)
            OR (s.lastActivity >= :t0 AND s.lastActivity < :t1)
          )
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findOverlappingWindowByTargetTypeIn(
            @Param("targetTypes") List<String> targetTypes,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            Pageable pageable);

    @Query("""
        SELECT s FROM AiSession s
        WHERE s.targetType IN :targetTypes
          AND (
            (s.startedAt >= :t0 AND s.startedAt < :t1)
            OR (s.lastActivity >= :t0 AND s.lastActivity < :t1)
          )
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findOverlappingWindowByTargetTypeInIncludingInvalid(
            @Param("targetTypes") List<String> targetTypes,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            Pageable pageable);

    @Query("""
        SELECT s FROM AiSession s
        WHERE s.targetType IN :targetTypes
          AND s.userCode IN :userCodes
          AND (
            (s.startedAt >= :t0 AND s.startedAt < :t1)
            OR (s.lastActivity >= :t0 AND s.lastActivity < :t1)
          )
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findOverlappingWindowByTargetTypeInAndUserCodeIn(
            @Param("targetTypes") List<String> targetTypes,
            @Param("userCodes") List<String> userCodes,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            Pageable pageable);

    @Query("""
        SELECT s FROM AiSession s
        WHERE s.targetType IN :targetTypes
          AND s.userCode IN :userCodes
          AND (
            (s.startedAt >= :t0 AND s.startedAt < :t1)
            OR (s.lastActivity >= :t0 AND s.lastActivity < :t1)
          )
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findOverlappingWindowByTargetTypeInAndUserCodeInIncludingInvalid(
            @Param("targetTypes") List<String> targetTypes,
            @Param("userCodes") List<String> userCodes,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            Pageable pageable);

    @Query("""
        SELECT s FROM AiSession s
        WHERE s.projectName = :projectName
          AND s.targetType IN :targetTypes
          AND (
            (s.startedAt >= :t0 AND s.startedAt < :t1)
            OR (s.lastActivity >= :t0 AND s.lastActivity < :t1)
          )
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findOverlappingWindowByProjectNameAndTargetTypeIn(
            @Param("projectName") String projectName,
            @Param("targetTypes") List<String> targetTypes,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            Pageable pageable);

    @Query("""
        SELECT s FROM AiSession s
        WHERE s.projectName = :projectName
          AND s.targetType IN :targetTypes
          AND (
            (s.startedAt >= :t0 AND s.startedAt < :t1)
            OR (s.lastActivity >= :t0 AND s.lastActivity < :t1)
          )
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findOverlappingWindowByProjectNameAndTargetTypeInIncludingInvalid(
            @Param("projectName") String projectName,
            @Param("targetTypes") List<String> targetTypes,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            Pageable pageable);

    @Query("""
        SELECT s FROM AiSession s
        WHERE s.projectName = :projectName
          AND s.userCode = :userCode
          AND s.targetType IN :targetTypes
          AND (
            (s.startedAt >= :t0 AND s.startedAt < :t1)
            OR (s.lastActivity >= :t0 AND s.lastActivity < :t1)
          )
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findOverlappingWindowByProjectNameAndUserCodeAndTargetTypeIn(
            @Param("projectName") String projectName,
            @Param("userCode") String userCode,
            @Param("targetTypes") List<String> targetTypes,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            Pageable pageable);

    @Query("""
        SELECT s FROM AiSession s
        WHERE s.projectName = :projectName
          AND s.userCode = :userCode
          AND s.targetType IN :targetTypes
          AND (
            (s.startedAt >= :t0 AND s.startedAt < :t1)
            OR (s.lastActivity >= :t0 AND s.lastActivity < :t1)
          )
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findOverlappingWindowByProjectNameAndUserCodeAndTargetTypeInIncludingInvalid(
            @Param("projectName") String projectName,
            @Param("userCode") String userCode,
            @Param("targetTypes") List<String> targetTypes,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            Pageable pageable);

    /**
     * v2.10：projectName + userCode + target_type 白名单。
     * <p>v2.11 起默认排除 invalid_reason 非空的无效会话。
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE s.projectName = :projectName
          AND s.userCode = :userCode
          AND s.targetType IN :targetTypes
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findByProjectNameAndUserCodeAndTargetTypeIn(
            @Param("projectName") String projectName,
            @Param("userCode") String userCode,
            @Param("targetTypes") List<String> targetTypes,
            Pageable pageable);

    @Query("""
        SELECT s FROM AiSession s
        WHERE s.projectName = :projectName
          AND s.userCode = :userCode
          AND s.targetType IN :targetTypes
        ORDER BY s.lastActivity DESC
        """)
    Page<AiSession> findByProjectNameAndUserCodeAndTargetTypeInIncludingInvalid(
            @Param("projectName") String projectName,
            @Param("userCode") String userCode,
            @Param("targetTypes") List<String> targetTypes,
            Pageable pageable);

    /** 实时大屏：取最近 N 分钟内有活动的会话 */
    List<AiSession> findByLastActivityAfterOrderByLastActivityDesc(LocalDateTime since);

    /** 关闭超时会话用：lastActivity < cutoff 且 status != idle 且 endedAt 为空 */
    List<AiSession> findByLastActivityBeforeAndEndedAtIsNull(LocalDateTime cutoff);

    /**
     * 大盘活跃口径（v2.8 起）：所有当前 status != idle 的会话。
     *
     * <p>口径选择记录：用户体感"AI 会话列表 2 个非 idle、大盘说 0"反复出现，
     * 根因是 dashboard 多套独立的 last_activity 时间窗判定（180s/300s）总会有边界跨越。
     * v2.8 起统一为"DB 里 status 字段是真相"——
     * <ul>
     *   <li>列表展示按 status 渲染</li>
     *   <li>大盘 active_ai_sessions / active_agents 也按 status 计数</li>
     *   <li>真正的"卡僵 session 自愈"由独立定时任务 {@code AiSessionStaleCloser} 兜底，
     *       超过 5 分钟无活动直接 UPDATE status=idle，让 DB 字段自身回到正确状态</li>
     * </ul>
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE LOWER(s.status) <> 'idle'
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    List<AiSession> findAllNonIdle();

    /**
     * v2.10：与 {@link #findAllNonIdle()} 同口径，但只返回 target_type ∈ activeTypes 的会话。
     * <p>大盘 / 实时活跃页用——禁用的 agent 上即使有 status != idle 的会话，也不应出现在
     * "在线/活跃 agent" 统计与表格里，与员工数据、报告口径一致。
     * <p>v2.11 起额外排除 invalid_reason 非空的无效会话，保持与列表 / 详情同口径。
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE LOWER(s.status) <> 'idle'
          AND s.targetType IN :activeTypes
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    List<AiSession> findAllNonIdleByTargetTypeInList(
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /** 大盘 overview：非 idle 会话条数（与 {@link #findAllNonIdleByTargetTypeInList} 同口径）。 */
    @Query("""
        SELECT COUNT(s) FROM AiSession s
        WHERE LOWER(s.status) <> 'idle'
          AND s.targetType IN :activeTypes
          AND s.invalidReason IS NULL
        """)
    long countNonIdleByTargetTypeIn(@Param("activeTypes") java.util.Collection<String> activeTypes);

    /** 大盘 overview：非 idle 会话涉及的去重 agent_id 数。 */
    @Query("""
        SELECT COUNT(DISTINCT s.agentId) FROM AiSession s
        WHERE LOWER(s.status) <> 'idle'
          AND s.agentId IS NOT NULL
          AND s.targetType IN :activeTypes
          AND s.invalidReason IS NULL
        """)
    long countDistinctNonIdleAgentIdByTargetTypeIn(
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /** 管理端「仅活跃」列表：与大盘 {@link #findAllNonIdle()} 同口径，分页。 */
    @Query("""
            SELECT s FROM AiSession s
            WHERE LOWER(s.status) <> 'idle'
              AND s.invalidReason IS NULL
            ORDER BY s.lastActivity DESC
            """)
    Page<AiSession> findAllNonIdlePaged(Pageable pageable);

    @Query("""
            SELECT s FROM AiSession s
            WHERE LOWER(s.status) <> 'idle'
              AND s.targetType IN :targetTypes
              AND s.invalidReason IS NULL
            ORDER BY s.lastActivity DESC
            """)
    Page<AiSession> findAllNonIdleByTargetTypeIn(
            @Param("targetTypes") List<String> targetTypes, Pageable pageable);

    @Query("""
            SELECT s FROM AiSession s
            WHERE LOWER(s.status) <> 'idle'
              AND s.userCode IN :userCodes
              AND s.invalidReason IS NULL
            ORDER BY s.lastActivity DESC
            """)
    Page<AiSession> findAllNonIdleByUserCodeIn(
            @Param("userCodes") List<String> userCodes, Pageable pageable);

    @Query("""
            SELECT s FROM AiSession s
            WHERE LOWER(s.status) <> 'idle'
              AND s.targetType IN :targetTypes
              AND s.userCode IN :userCodes
              AND s.invalidReason IS NULL
            ORDER BY s.lastActivity DESC
            """)
    Page<AiSession> findAllNonIdleByTargetTypeInAndUserCodeIn(
            @Param("targetTypes") List<String> targetTypes,
            @Param("userCodes") List<String> userCodes,
            Pageable pageable);

    @Query("""
            SELECT s FROM AiSession s
            WHERE LOWER(s.status) <> 'idle'
              AND s.projectName = :projectName
              AND s.invalidReason IS NULL
            ORDER BY s.lastActivity DESC
            """)
    Page<AiSession> findAllNonIdleByProjectName(
            @Param("projectName") String projectName, Pageable pageable);

    /** v2.10：non-idle + projectName + target_type 白名单。 */
    @Query("""
            SELECT s FROM AiSession s
            WHERE LOWER(s.status) <> 'idle'
              AND s.projectName = :projectName
              AND s.targetType IN :targetTypes
              AND s.invalidReason IS NULL
            ORDER BY s.lastActivity DESC
            """)
    Page<AiSession> findAllNonIdleByProjectNameAndTargetTypeIn(
            @Param("projectName") String projectName,
            @Param("targetTypes") List<String> targetTypes,
            Pageable pageable);

    @Query("""
            SELECT s FROM AiSession s
            WHERE LOWER(s.status) <> 'idle'
              AND s.projectName = :projectName
              AND s.userCode = :userCode
              AND s.invalidReason IS NULL
            ORDER BY s.lastActivity DESC
            """)
    Page<AiSession> findAllNonIdleByProjectNameAndUserCode(
            @Param("projectName") String projectName,
            @Param("userCode") String userCode,
            Pageable pageable);

    /** v2.10：non-idle + projectName + userCode + target_type 白名单。 */
    @Query("""
            SELECT s FROM AiSession s
            WHERE LOWER(s.status) <> 'idle'
              AND s.projectName = :projectName
              AND s.userCode = :userCode
              AND s.targetType IN :targetTypes
              AND s.invalidReason IS NULL
            ORDER BY s.lastActivity DESC
            """)
    Page<AiSession> findAllNonIdleByProjectNameAndUserCodeAndTargetTypeIn(
            @Param("projectName") String projectName,
            @Param("userCode") String userCode,
            @Param("targetTypes") List<String> targetTypes,
            Pageable pageable);

    /**
     * 兜底定时任务用：lastActivity 早于 cutoff 且当前 status != idle 的会话。
     * <p>不要求 endedAt 为空——idle 会话不会主动写 endedAt，所以只看 status 是更可靠的过滤。
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE LOWER(s.status) <> 'idle'
          AND s.lastActivity < :cutoff
        """)
    List<AiSession> findStaleNonIdle(LocalDateTime cutoff);

    /** WorkSession 反向写：取该 agent 最近活跃的一条 ai_session（用于回填 project/repo/branch） */
    Optional<AiSession> findFirstByAgentIdOrderByLastActivityDesc(String agentId);

    /** 全局：今日活跃过的会话（含 idle 但今日有过 last_activity） */
    List<AiSession> findByLastActivityBetweenOrderByLastActivityDesc(LocalDateTime from, LocalDateTime to);

    /** 单 user 的当日活跃会话（DailySummaryAggregator 算 token Top 模型用） */
    List<AiSession> findByUserCodeAndLastActivityBetweenOrderByLastActivityDesc(
            String userCode, LocalDateTime from, LocalDateTime to);

    // 注：原 aggregateTopProjects / aggregateTopEmployees 直接 SUM(s.inputTokens) 等会话级累积字段，
    // 长会话被命中后会把历史 token 全算进当前窗口，已在 v2.4 移除。
    // 大盘 Top 接口改用 AiSessionMessageRepository.aggregateByProjectInWindow / aggregateByUserInWindow，
    // 这些查询按 message_time 切片，能正确反映"窗口内真实消耗"。

    /**
     * 大盘 Top 项目：窗内有活动的会话按 project_name 分组求 SUM(userMessages) / SUM(assistantMessages)。
     *
     * <p>这两个字段是 reporter 每次上报全量覆盖的会话级累计值，不依赖 ai_session_message 表，
     * 因此不会因 v1.0.0 客户端 tail(10/40) 截断而偏小。<br>
     * 用途：让 Top 项目"消息"列展示成 "X/Y"（user/assistant），与 AI 会话列表口径一致。
     *
     * <p>窗内判定：{@code lastActivity ∈ [from, to)}——和现有 event 切片聚合命中的会话集合保持一致。
     * 长会话从历史延续到窗内时，会把整个会话生命周期的累计 user/assistant 都算进来——这是和
     * AI 会话列表 "X/Y" 完全相同的语义（列表里那个数字也是会话累计，不按窗口切片）。
     *
     * <p>返回：{@code [projectName, userMessages, assistantMessages]}
     */
    @Query("""
        SELECT s.projectName,
               COALESCE(SUM(s.userMessages), 0),
               COALESCE(SUM(s.assistantMessages), 0)
        FROM AiSession s
        WHERE s.projectName IS NOT NULL
          AND s.lastActivity >= :from AND s.lastActivity < :to
          AND s.invalidReason IS NULL
        GROUP BY s.projectName
        """)
    List<Object[]> aggregateUserAssistantMessagesByProject(LocalDateTime from, LocalDateTime to);

    /** v2.10：按 project 聚合 user/assistant 消息 —— 只统计 active target_type。 */
    @Query("""
        SELECT s.projectName,
               COALESCE(SUM(s.userMessages), 0),
               COALESCE(SUM(s.assistantMessages), 0)
        FROM AiSession s
        WHERE s.projectName IS NOT NULL
          AND s.lastActivity >= :from AND s.lastActivity < :to
          AND s.targetType IN :activeTypes
          AND s.invalidReason IS NULL
        GROUP BY s.projectName
        """)
    List<Object[]> aggregateUserAssistantMessagesByProjectAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * 项目透视：按 project 汇总窗内 input/output token（会话级累计字段，last_activity 切片）。
     *
     * <p>口径与 {@link #aggregateUserAssistantMessagesByProjectAndTargetTypeIn} 一致，供「Token in/out」列与
     * 事件流 {@code SUM(tokens_delta)}（总量）对照；两者数值可能不完全相等，因 slice 语义不同。
     *
     * @return {@code [projectName, sumInputTokens, sumOutputTokens]}
     */
    @Query("""
            SELECT s.projectName,
                   COALESCE(SUM(s.inputTokens), 0L),
                   COALESCE(SUM(s.outputTokens), 0L)
            FROM AiSession s
            WHERE s.projectName IS NOT NULL
              AND s.lastActivity >= :from AND s.lastActivity < :to
              AND s.targetType IN :activeTypes
              AND s.invalidReason IS NULL
            GROUP BY s.projectName
            """)
    List<Object[]> aggregateInputOutputTokensByProjectAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * 大盘 Top 员工：按 user 汇总窗内 input/output token（会话级累计字段，last_activity 切片）。
     *
     * <p>口径与 {@link #aggregateInputOutputTokensByProjectAndTargetTypeIn} 对称。
     *
     * @return {@code [userCode, sumInputTokens, sumOutputTokens]}
     */
    @Query("""
            SELECT s.userCode,
                   COALESCE(SUM(s.inputTokens), 0L),
                   COALESCE(SUM(s.outputTokens), 0L)
            FROM AiSession s
            WHERE s.userCode IS NOT NULL
              AND s.lastActivity >= :from AND s.lastActivity < :to
              AND s.targetType IN :activeTypes
              AND s.invalidReason IS NULL
            GROUP BY s.userCode
            """)
    List<Object[]> aggregateInputOutputTokensByUserAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * 项目详情贡献者矩阵：单项目下按员工汇总 user/assistant 消息与 input/output token（last_activity 切片）。
     *
     * @return {@code [userCode, userMessages, assistantMessages, sumInputTokens, sumOutputTokens]}
     */
    @Query("""
            SELECT s.userCode,
                   COALESCE(SUM(s.userMessages), 0),
                   COALESCE(SUM(s.assistantMessages), 0),
                   COALESCE(SUM(s.inputTokens), 0L),
                   COALESCE(SUM(s.outputTokens), 0L)
            FROM AiSession s
            WHERE s.projectName = :projectName
              AND s.lastActivity >= :from AND s.lastActivity < :to
              AND s.targetType IN :activeTypes
              AND s.invalidReason IS NULL
            GROUP BY s.userCode
            """)
    List<Object[]> aggregateContributorRollupsByProjectAndTargetTypeIn(
            @Param("projectName") String projectName,
            LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * 大盘 Top 员工：同上，但按 user_code 分组。
     * <p>返回：{@code [userCode, userMessages, assistantMessages]}
     */
    @Query("""
        SELECT s.userCode,
               COALESCE(SUM(s.userMessages), 0),
               COALESCE(SUM(s.assistantMessages), 0)
        FROM AiSession s
        WHERE s.userCode IS NOT NULL
          AND s.lastActivity >= :from AND s.lastActivity < :to
          AND s.invalidReason IS NULL
        GROUP BY s.userCode
        """)
    List<Object[]> aggregateUserAssistantMessagesByUser(LocalDateTime from, LocalDateTime to);

    /** v2.10：按 user 聚合 user/assistant 消息 —— 只统计 active target_type。 */
    @Query("""
        SELECT s.userCode,
               COALESCE(SUM(s.userMessages), 0),
               COALESCE(SUM(s.assistantMessages), 0)
        FROM AiSession s
        WHERE s.userCode IS NOT NULL
          AND s.lastActivity >= :from AND s.lastActivity < :to
          AND s.targetType IN :activeTypes
          AND s.invalidReason IS NULL
        GROUP BY s.userCode
        """)
    List<Object[]> aggregateUserAssistantMessagesByUserAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @org.springframework.data.repository.query.Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * AI 协助判定（v2.2 Phase 3）：找出某员工 + 某 repo 的活跃区间与给定窗口
     * [from, to] 重叠的会话。重叠定义：session.startedAt &lt;= to AND session.lastActivity &gt;= from。
     */
    /**
     * Git 提交 AI 协助推断用：时间窗内与 commit 重叠的有效会话，且 target_type 必须在启用白名单内。
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE s.userCode = :userCode
          AND s.repoUrl = :repoUrl
          AND s.startedAt <= :to
          AND s.lastActivity >= :from
          AND s.invalidReason IS NULL
          AND s.targetType IN :activeTypes
        ORDER BY s.id ASC
        """)
    List<AiSession> findOverlappingSessionsAndTargetTypeIn(
            @Param("userCode") String userCode,
            @Param("repoUrl") String repoUrl,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * 当日 token 切片求和（v2.6 daily_summary 重构）。
     *
     * <p>背景：v2.4 把 daily_summary 的 token 改成"按 ai_session_message.message_time 切片求和"，
     * 但实际部署后发现绝大多数 provider 的 message-level 没有 token 数据：
     * <ul>
     *   <li>cursor: bubble.TokenCount 在 Cursor 本地数据源里通常为 0（token 是会话级 ContextTokensUsed 估算）</li>
     *   <li>codex / hermes / openharness: 客户端构造 Message 时没填 InputTokens / OutputTokens</li>
     *   <li>claude: 仅 assistant 偶尔填到</li>
     *   <li>openclaw: 完整填到</li>
     * </ul>
     * 而 ai_session.inputTokens / outputTokens 是各 Provider 在 ReporterPipeline 都会写到的会话级累积值，覆盖完整。
     *
     * <p>口径：会话最后一次活动落在 [from, to) 内 → 把该会话的累计 token 全部算入这个窗口。
     * 跨期会话（昨天开始今天结束）会把历史 token 全计入今日 —— 在"窗口期合计"语义下是合理的。
     * <b>不要用此查询去做"日维度"分布统计</b>，那种场景仍走 message_time 切片。
     *
     * <p>返回 Object[2]：[SUM(inputTokens), SUM(outputTokens)]，无匹配时为 [0, 0]。
     */
    @Query("""
        SELECT COALESCE(SUM(s.inputTokens), 0L), COALESCE(SUM(s.outputTokens), 0L)
        FROM AiSession s
        WHERE s.userCode = :userCode
          AND s.lastActivity >= :from
          AND s.lastActivity < :to
          AND s.invalidReason IS NULL
        """)
    List<Object[]> sumTokensByUserInLastActivityWindow(
            String userCode, LocalDateTime from, LocalDateTime to);

    /** target_type 过滤版本。 */
    @Query("""
        SELECT COALESCE(SUM(s.inputTokens), 0L), COALESCE(SUM(s.outputTokens), 0L)
        FROM AiSession s
        WHERE s.userCode = :userCode
          AND s.lastActivity >= :from
          AND s.lastActivity < :to
          AND s.targetType IN :activeTypes
          AND s.invalidReason IS NULL
        """)
    List<Object[]> sumTokensByUserInLastActivityWindowAndTargetTypeIn(
            String userCode, LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /** target_type 过滤版本：单 user 当日活跃会话（按 last_activity 切片）。 */
    @Query("""
        SELECT s FROM AiSession s
        WHERE s.userCode = :userCode
          AND s.lastActivity >= :from
          AND s.lastActivity < :to
          AND s.targetType IN :activeTypes
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    List<AiSession> findByUserAndLastActivityWindowAndTargetTypeIn(
            String userCode, LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /** DailySummary 模型 Top：仅 model + token 列。返回 [model, inputTokens, outputTokens]。 */
    @Query("""
        SELECT s.model, s.inputTokens, s.outputTokens
        FROM AiSession s
        WHERE s.invalidReason IS NULL
          AND s.userCode = :userCode
          AND s.lastActivity >= :from AND s.lastActivity < :to
          AND s.targetType IN :activeTypes
        """)
    List<Object[]> findModelTokenSlicesByUserAndLastActivityWindowAndTargetTypeIn(
            String userCode, LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * 大盘 online：非 idle 会话摘要，避免 hydrate 全实体。
     * 返回 [agentId, targetType, lastActivity, status, currentTool, model, projectName]。
     */
    @Query("""
        SELECT s.agentId, s.targetType, s.lastActivity, s.status, s.currentTool, s.model, s.projectName
        FROM AiSession s
        WHERE LOWER(s.status) <> 'idle'
          AND s.targetType IN :activeTypes
          AND s.invalidReason IS NULL
        ORDER BY s.lastActivity DESC
        """)
    List<Object[]> findNonIdleSessionSummariesByTargetTypeIn(
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * v2.9 baseline backfill 用：找出 startedAt 或 lastActivity 落在 [from, to) 的所有会话。
     * <p>BaselineEventBackfillService 用它扫历史会话，给"event 流缺失 baseline"的会话补
     * SESSION_OPEN / TOKEN_DELTA / MESSAGE_DELTA。OR 谓语保证跨期会话也能命中（startedAt
     * 在窗内但 lastActivity 已经溢出，或反之）。
     */
    /**
     * 应急运维：无 target 约束的窗内重叠扫描（仍会排除 invalid 会话）。
     * <p>业务路径请使用 {@link #findOverlappingByStartedOrLastActivityAndTargetTypeIn}。
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE ((s.startedAt >= :from AND s.startedAt < :to)
            OR (s.lastActivity >= :from AND s.lastActivity < :to))
          AND s.invalidReason IS NULL
        ORDER BY s.id ASC
        """)
    List<AiSession> findOverlappingByStartedOrLastActivity(LocalDateTime from, LocalDateTime to);

    /**
     * Baseline 回填 / 分析报告流水线：窗内重叠且 target ∈ 启用白名单、有效会话。
     */
    @Query("""
        SELECT s FROM AiSession s
        WHERE ((s.startedAt >= :from AND s.startedAt < :to)
            OR (s.lastActivity >= :from AND s.lastActivity < :to))
          AND s.targetType IN :activeTypes
          AND s.invalidReason IS NULL
        ORDER BY s.id ASC
        """)
    List<AiSession> findOverlappingByStartedOrLastActivityAndTargetTypeIn(
            LocalDateTime from, LocalDateTime to,
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * 系统设置页"活跃 Agent" Tab 用：按 target_type 分组返回最近 since 之后的会话数。
     * 返回 [targetType, count]。
     */
    /**
     * 系统设置「活跃 Agent」Tab：最近窗内、启用白名单内且有效的会话数（禁用 agent 行为 0）。
     */
    @Query("""
        SELECT s.targetType, COUNT(s)
        FROM AiSession s
        WHERE s.lastActivity >= :since
          AND s.invalidReason IS NULL
          AND s.targetType IN :activeTypes
        GROUP BY s.targetType
        """)
    List<Object[]> countSessionsByTargetTypeSinceAndActiveTypesIn(
            @Param("since") LocalDateTime since,
            @Param("activeTypes") java.util.Collection<String> activeTypes);

    /**
     * 后台洞察审计扫描：按主键游标拉取下一批「可能需要 LLM 审计」的会话 id（仅有效会话、target 白名单）。
     */
    @Query(value = """
            SELECT s.id FROM ai_session s
            LEFT JOIN ai_session_audit a ON a.ai_session_id = s.id
            WHERE s.id > :lastId
              AND s.invalid_reason IS NULL
              AND s.total_messages > 0
              AND s.target_type IN (:types)
              AND (
                COALESCE(s.insight_audit_status, 'NONE') IN ('NONE', 'PENDING', 'FAILED')
                OR (s.insight_audit_status = 'RUNNING' AND (s.insight_audit_lease_until IS NULL OR s.insight_audit_lease_until < NOW(3)))
                OR s.insight_reaudit_required = 1
                OR (s.insight_audit_status = 'DONE' AND a.id IS NOT NULL AND s.total_messages > a.message_count_at_audit + :threshold)
                OR (s.insight_audit_status = 'DONE' AND a.id IS NULL)
              )
            ORDER BY s.id ASC
            LIMIT :lim
            """, nativeQuery = true)
    List<Long> findIdsNeedingInsightAudit(
            @Param("lastId") long lastId,
            @Param("types") List<String> types,
            @Param("threshold") int threshold,
            @Param("lim") int limit);

    @Query(value = """
            SELECT COUNT(*) FROM ai_session s
            LEFT JOIN ai_session_audit a ON a.ai_session_id = s.id
            WHERE s.invalid_reason IS NULL
              AND s.total_messages > 0
              AND s.target_type IN (:types)
              AND (
                COALESCE(s.insight_audit_status, 'NONE') IN ('NONE', 'PENDING', 'FAILED')
                OR (s.insight_audit_status = 'RUNNING' AND (s.insight_audit_lease_until IS NULL OR s.insight_audit_lease_until < NOW(3)))
                OR s.insight_reaudit_required = 1
                OR (s.insight_audit_status = 'DONE' AND a.id IS NOT NULL AND s.total_messages > a.message_count_at_audit + :threshold)
                OR (s.insight_audit_status = 'DONE' AND a.id IS NULL)
              )
            """, nativeQuery = true)
    long countPendingInsightAuditSessions(
            @Param("types") List<String> types,
            @Param("threshold") int threshold);

    @Query(value = """
            SELECT COUNT(*) FROM ai_session s
            INNER JOIN ai_session_audit a ON a.ai_session_id = s.id
            WHERE s.invalid_reason IS NULL
              AND s.total_messages > 0
              AND s.target_type IN (:types)
              AND s.insight_audit_status = 'DONE'
              AND COALESCE(s.insight_reaudit_required, 0) = 0
              AND s.total_messages <= a.message_count_at_audit + :threshold
            """, nativeQuery = true)
    long countStableInsightAuditedSessions(
            @Param("types") List<String> types,
            @Param("threshold") int threshold);

    @Query(value = """
            SELECT COUNT(*) FROM ai_session s
            WHERE s.invalid_reason IS NULL
              AND s.total_messages > 0
              AND s.target_type IN (:types)
            """, nativeQuery = true)
    long countValidSessionsForInsightTypes(@Param("types") List<String> types);

    /**
     * 管理员：时间窗内有效会话标记需重审（不自动因 rubric bump 触发）。
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE ai_session s
            SET s.insight_reaudit_required = 1
            WHERE s.invalid_reason IS NULL
              AND s.target_type IN (:types)
              AND (
                (s.started_at >= :t0 AND s.started_at < :t1)
                OR (s.last_activity >= :t0 AND s.last_activity < :t1)
              )
              AND (:userCode IS NULL OR s.user_code = :userCode)
            """, nativeQuery = true)
    int markInsightReauditRequiredInWindow(
            @Param("types") List<String> types,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            @Param("userCode") String userCode);
}
