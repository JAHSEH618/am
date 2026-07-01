package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.ActivityDeltaDto;
import com.am.server.agent.api.dto.ContentPartDto;
import com.am.server.agent.api.dto.ConversationMessageDto;
import com.am.server.agent.api.dto.MonitorSessionDto;
import com.am.server.agent.service.MessageContentIngestService;
import com.am.server.agent.api.dto.MonitorSnapshotDto;
import com.am.server.agent.api.dto.ToolCallDto;
import com.am.server.agent.security.SignatureContext;
import com.am.server.config.AgentProperties;
import com.am.server.aggregator.DailySummaryAggregator;
import com.am.server.insight.aggregate.BackfillSnapshotSupport;
import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionEventType;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.ai.AiSessionStatus;
import com.am.server.insight.aggregate.NlSkillAttributionSupport;
import com.am.server.insight.aggregate.UserSlashInvocationExtractor;
import com.am.server.insight.domain.AiSessionAudit;
import com.am.server.insight.domain.AiSessionAuditRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.web.support.SessionMessageCountSupport;
import com.am.server.web.dto.AiSessionAuditSummaryDto;
import com.am.server.web.dto.AiSessionDto;
import com.am.server.web.dto.AiSessionEventDto;
import com.am.server.web.sse.SseHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 会话入库通用骨架，所有 Provider（cursor / claude / codex / ...）共享。
 * <p>
 * 子类只需要：
 * <ul>
 *   <li>在构造函数注入仓库 + SseHub</li>
 *   <li>重写 {@link #targetType()} 返回 type_code</li>
 *   <li>必要时重写 {@link #activeWindowSeconds()} 调整"活跃判定"窗口</li>
 *   <li>必要时重写 {@link #onSessionUpsert} 做 Provider 专属字段加工</li>
 * </ul>
 * 这样新接入一种 Agent（比如未来要加 cline / amp），不需要再 copy 一遍 upsert + dedup 逻辑。
 *
 * 算法概要（与 CursorIngestService v1.3 完全一致）：
 *   1. 按 (target_type, external_session_id) upsert ai_session；新建时落 SESSION_OPEN 事件
 *   2. 状态变化、token / message 增量分别落 ai_session_event 流水
 *   3. recent_tools 按 (ai_session_id, event_time, tool_name) 去重写 TOOL_CALL 事件
 *   4. recent_messages 按 (ai_session_id, external_message_id) 唯一索引去重，sequence_no 递增
 *   5. 入库完成后异步推 SSE，让实时大屏立即刷新
 *
 * gz
 */
public abstract class AbstractAiSessionIngestService implements MonitorIngestor {

    private static final Logger log = LoggerFactory.getLogger(AbstractAiSessionIngestService.class);

    /** 同会话并发 ingest 触发 @Version 冲突时的最大尝试次数(含首次)。耗尽本轮跳过,outbox/下轮自愈。 */
    private static final int MAX_OPTIMISTIC_ATTEMPTS = 3;

    protected final AiSessionRepository sessionRepository;
    protected final AiSessionEventRepository eventRepository;
    protected final AiSessionMessageRepository messageRepository;
    protected final SseHub sseHub;

    /**
     * "姓名|工号"展示串解析。用 setter + Autowired 注入，避免破坏 6 个子类的构造签名。
     * required=false 是为了让单元测试可以不挂这个依赖直接构造 ingest service。
     */
    private EmployeeDisplayService employeeDisplayService;

    @Autowired(required = false)
    public void setEmployeeDisplayService(EmployeeDisplayService s) {
        this.employeeDisplayService = s;
    }

    /**
     * daily_summary 追新聚合器。每次 ingest 涉及到的 (lastActivity 那一天) 集合在事务提交后
     * 异步入队，避免拖慢上报；@Lazy 防止跟 6 个子类的 bean 装配产生循环依赖。
     * required=false 让单元测试可以不挂这个依赖直接构造 ingest service。
     */
    private DailySummaryAggregator dailySummaryAggregator;

    @Autowired(required = false)
    public void setDailySummaryAggregator(@Lazy DailySummaryAggregator a) {
        this.dailySummaryAggregator = a;
    }

    /**
     * SSE {@code session_changed} 与列表接口对齐：补上 {@code ai_session_audit} 摘要，避免全局 Jackson
     * {@code Include.ALWAYS} 把 {@code audit:null} 推到前端后冲掉表格里已有评判展示。
     */
    private AiSessionAuditRepository sessionAuditRepository;

    @Autowired(required = false)
    public void setSessionAuditRepository(AiSessionAuditRepository sessionAuditRepository) {
        this.sessionAuditRepository = sessionAuditRepository;
    }

    private MessageContentIngestService messageContentIngestService;

    @Autowired(required = false)
    public void setMessageContentIngestService(MessageContentIngestService messageContentIngestService) {
        this.messageContentIngestService = messageContentIngestService;
    }

    private PlatformTransactionManager transactionManager;
    private TransactionTemplate sessionTxTemplate;
    private AgentProperties agentProperties;

    @Autowired(required = false)
    public void setAgentProperties(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @PostConstruct
    void initSessionTxTemplate() {
        if (transactionManager == null) {
            return;
        }
        sessionTxTemplate = new TransactionTemplate(transactionManager);
        sessionTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 单会话 ingest 在独立事务内的产出，供外层汇总 SSE / daily_summary。 */
    private record SessionIngestSlice(
            int eventsWritten,
            int messagesWritten,
            boolean active,
            List<AiSessionEvent> sseEvents,
            Map<Long, AiSession> sseSessions,
            Set<LocalDate> dates,
            Set<String> suppressedChildComposerIds,
            String userCode) {
        /** 乐观锁重试耗尽时的空切片:不写任何 SSE / 受影响日期,调用方按"本会话本轮跳过"处理。 */
        static SessionIngestSlice empty() {
            return new SessionIngestSlice(0, 0, false, List.of(), Map.of(), Set.of(), Set.of(), null);
        }
    }

    protected AbstractAiSessionIngestService(
            AiSessionRepository sessionRepository,
            AiSessionEventRepository eventRepository,
            AiSessionMessageRepository messageRepository,
            SseHub sseHub) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.messageRepository = messageRepository;
        this.sseHub = sseHub;
    }

    /** 子类返回 monitor_target.type_code，例如 "cursor" / "claude" / "codex"。 */
    protected abstract String targetType();

    /** 多久内的 last_activity 视为活跃；默认 300s ≈ 2.5× 2min 上报，见 {@link AgentProperties#getActivityWindowSeconds()}。 */
    protected long activeWindowSeconds() {
        return agentProperties != null ? agentProperties.getActivityWindowSeconds() : 300L;
    }

    /** Provider 专属字段加工钩子，默认空实现。 */
    protected void onSessionUpsert(AiSession session, MonitorSessionDto incoming, boolean isNew) {
        // no-op
    }

    @Override
    public boolean supports(String typeCode) {
        return targetType().equalsIgnoreCase(typeCode);
    }

    @Override
    public IngestResult ingest(MonitorSnapshotDto snapshot, SignatureContext ctx) {
        if (snapshot == null) {
            return IngestResult.empty();
        }
        boolean hasSessions = snapshot.getSessions() != null && !snapshot.getSessions().isEmpty();
        boolean hasSuppressed = snapshot.getSuppressedSessionIds() != null
                && !snapshot.getSuppressedSessionIds().isEmpty();
        if (!hasSessions && !hasSuppressed) {
            return IngestResult.empty();
        }
        int sessionsTouched = 0;
        int eventsWritten = 0;
        int messagesWritten = 0;
        boolean hasActive = false;

        LocalDateTime wallNow = LocalDateTime.now();
        LocalDateTime snapshotCapturedAt = snapshot.getCapturedAt();
        Map<LocalDate, Set<String>> affectedDateUsers = new LinkedHashMap<>();
        Set<String> suppressedChildComposerIds = new LinkedHashSet<>();

        if (hasSessions) {
            for (MonitorSessionDto incoming : snapshot.getSessions()) {
                if (incoming == null || incoming.getSessionId() == null) {
                    continue;
                }
                SessionIngestSlice slice = ingestOneSessionInNewTx(
                        incoming, ctx, snapshotCapturedAt, wallNow);
                sessionsTouched++;
                eventsWritten += slice.eventsWritten();
                messagesWritten += slice.messagesWritten();
                hasActive |= slice.active();
                accumulateAffected(affectedDateUsers, slice.dates(), slice.userCode());
                suppressedChildComposerIds.addAll(slice.suppressedChildComposerIds());
                publishSse(slice.sseEvents(), slice.sseSessions());
            }
        }

        LinkedHashSet<String> allSuppressed = new LinkedHashSet<>();
        if (snapshot.getSuppressedSessionIds() != null) {
            for (String id : snapshot.getSuppressedSessionIds()) {
                if (id != null && !id.isBlank()) {
                    allSuppressed.add(id.trim());
                }
            }
        }
        allSuppressed.addAll(suppressedChildComposerIds);
        suppressMergedSubagentSessions(new ArrayList<>(allSuppressed), ctx);

        scheduleDailySummaryRefresh(affectedDateUsers);
        log.debug("{} ingest: sessions={} events={} messages={} active={} dates={}",
                targetType(), sessionsTouched, eventsWritten, messagesWritten, hasActive,
                affectedDateUsers.keySet());
        return new IngestResult(sessionsTouched, eventsWritten, messagesWritten, hasActive);
    }

    private SessionIngestSlice ingestOneSessionInNewTx(MonitorSessionDto incoming, SignatureContext ctx,
                                                         LocalDateTime snapshotCapturedAt, LocalDateTime wallNow) {
        if (sessionTxTemplate == null) {
            return ingestOneSession(incoming, ctx, snapshotCapturedAt, wallNow);
        }
        return executeWithOptimisticRetry(
                () -> sessionTxTemplate.execute(status -> ingestOneSession(incoming, ctx, snapshotCapturedAt, wallNow)),
                SessionIngestSlice::empty,
                incoming.getSessionId());
    }

    /**
     * 乐观锁重试(泛型,便于无 DB 单测):{@code action} 抛 {@link ObjectOptimisticLockingFailureException}
     * 说明同会话被并发 ingest 抢先提交(先提交者 version+1)。每次重试都在新的 REQUIRES_NEW 事务里重跑
     * {@code action}(重读会话最新 version + 重算 delta + 重写),最多 {@link #MAX_OPTIMISTIC_ATTEMPTS} 次;
     * 耗尽则返回 {@code onExhausted}(空切片)本轮跳过,下一 tick / P2 outbox 重放自愈。
     */
    <T> T executeWithOptimisticRetry(java.util.function.Supplier<T> action,
                                     java.util.function.Supplier<T> onExhausted, String sessionId) {
        int attempts = 0;
        while (true) {
            try {
                return action.get();
            } catch (ObjectOptimisticLockingFailureException ex) {
                attempts++;
                if (attempts >= MAX_OPTIMISTIC_ATTEMPTS) {
                    log.warn("session ingest optimistic-lock retry exhausted: target={} session={} attempts={} reason={}",
                            targetType(), sessionId, attempts, ex.toString());
                    return onExhausted.get();
                }
                log.debug("session ingest optimistic-lock conflict, retrying {}/{}: target={} session={}",
                        attempts, MAX_OPTIMISTIC_ATTEMPTS - 1, targetType(), sessionId);
            }
        }
    }

    private SessionIngestSlice ingestOneSession(MonitorSessionDto incoming, SignatureContext ctx,
                                                LocalDateTime snapshotCapturedAt, LocalDateTime wallNow) {
        List<AiSessionEvent> sseEvents = new ArrayList<>();
        Map<Long, AiSession> sseSessions = new HashMap<>();
        Set<LocalDate> dates = new LinkedHashSet<>();
        Set<Long> reconciledSessions = new HashSet<>();

        UpsertOutcome outcome = upsertSession(incoming, ctx, snapshotCapturedAt, sseEvents);
        int messagesWritten = writeMessages(outcome.session, incoming, ctx, reconciledSessions);
        SessionMessageCountSupport.reconcileSessionEntity(outcome.session, messageRepository);
        sessionRepository.save(outcome.session);
        Set<String> suppressedChildComposerIds = childComposerIdsFromMessages(outcome.session, incoming);
        sseSessions.put(outcome.session.getId(), outcome.session);

        boolean active = outcome.session.getEndedAt() == null && isActive(outcome.session, wallNow);
        LocalDateTime activityAt = outcome.session.getLastActivity();
        if (activityAt != null) {
            dates.add(activityAt.toLocalDate());
        }
        if (snapshotCapturedAt != null) {
            dates.add(snapshotCapturedAt.toLocalDate());
        }
        return new SessionIngestSlice(
                outcome.eventsWritten,
                messagesWritten,
                active,
                sseEvents,
                sseSessions,
                dates,
                suppressedChildComposerIds,
                outcome.session.getUserCode());
    }

    /**
     * 注册事务提交后回调：把本次 ingest 涉及的工作日喂给 {@link DailySummaryAggregator#enqueueRefresh}。
     * <p>这是"客户端 backfill 历史会话 → daily_summary 自动跟上"链路的关键缝合点：原 hourlyJob 只跑
     * today + yesterday，4 月会话上报后 4-29 那天的 daily_summary 永远生不出来——画像列表全 0。
     * 这里在 AFTER_COMMIT 时机异步入队，避免拖慢 ingest，也避免 aggregate 时事务尚未提交读不到本次数据。
     */
    private void scheduleDailySummaryRefresh(Map<LocalDate, Set<String>> dateUsers) {
        if (dailySummaryAggregator == null || dateUsers.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dailySummaryAggregator.enqueueRefresh(dateUsers);
                }
            });
        } else {
            // 极端兜底：上层调用方没开事务（不应该出现，ingest 自身有 @Transactional），
            // 直接派发，aggregator 内部会做幂等。
            dailySummaryAggregator.enqueueRefresh(dateUsers);
        }
    }

    /** 把单会话的 (dates × userCode) 并进受影响集合;供 ingest 汇总各会话后交给 daily_summary 增量追新。 */
    static void accumulateAffected(Map<LocalDate, Set<String>> acc, Set<LocalDate> dates, String userCode) {
        if (userCode == null || userCode.isBlank() || dates == null) {
            return;
        }
        for (LocalDate d : dates) {
            if (d == null) {
                continue;
            }
            acc.computeIfAbsent(d, k -> new LinkedHashSet<>()).add(userCode);
        }
    }

    private void publishSse(List<AiSessionEvent> events, Map<Long, AiSession> sessions) {
        if (sseHub == null) {
            return;
        }
        for (AiSessionEvent e : events) {
            AiSessionEventDto dto = AiSessionEventDto.of(e);
            dto.setUserDisplay(displayOf(dto.getUserCode()));
            sseHub.publish("session_event", dto);
        }
        Map<Long, AiSessionAudit> auditsBySessionId = new HashMap<>();
        if (sessionAuditRepository != null && !sessions.isEmpty()) {
            List<Long> ids = new ArrayList<>(sessions.keySet());
            for (AiSessionAudit a : sessionAuditRepository.findByAiSessionIdIn(ids)) {
                auditsBySessionId.putIfAbsent(a.getAiSessionId(), a);
            }
        }
        for (AiSession s : sessions.values()) {
            AiSessionDto dto = AiSessionDto.of(s);
            dto.setUserDisplay(displayOf(dto.getUserCode()));
            AiSessionAudit cached = auditsBySessionId.get(s.getId());
            if (cached != null) {
                dto.setAudit(AiSessionAuditSummaryDto.of(cached));
            }
            sseHub.publish("session_changed", dto);
        }
    }

    private String displayOf(String userCode) {
        return employeeDisplayService != null
                ? employeeDisplayService.displayOf(userCode)
                : (userCode == null ? "" : userCode);
    }

    /**
     * 判断一个会话状态是否处于"正在跑工具"语义下，决定 current_tool 字段是否保留。
     * idle / waiting / thinking / compacting 都不该挂工具名。
     */
    private static boolean isToolStatus(String status) {
        if (status == null) return false;
        return switch (status) {
            case "reading", "writing", "running",
                 "searching", "browsing", "spawning" -> true;
            default -> false;
        };
    }

    private UpsertOutcome upsertSession(MonitorSessionDto incoming, SignatureContext ctx,
                                        LocalDateTime snapshotCapturedAt, List<AiSessionEvent> sseEvents) {
        int eventsWritten = 0;
        LocalDateTime activityTime = resolveActivityTime(incoming, snapshotCapturedAt);
        AiSession session = sessionRepository
                .findByTargetTypeAndExternalSessionId(targetType(), incoming.getSessionId())
                .orElse(null);

        boolean isNew = session == null;
        if (isNew) {
            session = new AiSession();
            session.setTargetType(targetType());
            session.setExternalSessionId(incoming.getSessionId());
            session.setAgentId(ctx.getAgentId());
            session.setUserCode(ctx.getUserCode());
            session.setHostHash(ctx.getHostHash());
            session.setStartedAt(or(incoming.getStartedAt(), activityTime));
        }

        String previousStatus = session.getStatus();
        long previousInput = nz(session.getInputTokens());
        long previousOutput = nz(session.getOutputTokens());
        int previousMessages = nz(session.getTotalMessages());

        session.setCwd(incoming.getCwd());
        session.setCwdHash(incoming.getCwdHash());
        session.setGitBranch(incoming.getGitBranch());
        session.setRepoUrl(incoming.getRepoUrl());
        session.setProjectName(incoming.getProjectName());
        session.setIsWorktree(Boolean.TRUE.equals(incoming.getIsWorktree()) ? 1 : 0);
        session.setMainRepo(incoming.getMainRepo());
        session.setModel(incoming.getModel());
        String resolvedStatus = AiSessionStatus.of(incoming.getStatus()).code();
        session.setStatus(resolvedStatus);
        // 工具名只在"工具相关"状态下保留：会话回到 idle / waiting / thinking / compacting 时
        // current_tool 必须清空，否则 UI 会出现"空闲 [Edit]"这种自相矛盾的组合。
        // 这里做后端兜底，不依赖各 Provider 自己在 agent 端清理（实测 openharness 等就漏了）。
        session.setCurrentTool(isToolStatus(resolvedStatus) ? incoming.getCurrentTool() : null);
        session.setLastActivity(activityTime);
        session.setUserMessages(nz(incoming.getUserMessages()));
        session.setAssistantMessages(nz(incoming.getAssistantMessages()));
        session.setTotalMessages(nz(incoming.getUserMessages()) + nz(incoming.getAssistantMessages()));
        int storedBeforeWrite = isNew ? 0 : nz(messageRepository.countByAiSessionId(session.getId()));
        boolean cursorAtTail = incoming.getRecentMessages() == null || incoming.getRecentMessages().isEmpty();
        session.setReportedSnapshotMessages(BackfillSnapshotSupport.resolveReportedSnapshot(
                storedBeforeWrite, resolveSnapshotMessageCount(incoming), cursorAtTail));
        session.setInputTokens(nz(incoming.getInputTokens()));
        session.setOutputTokens(nz(incoming.getOutputTokens()));
        session.setCacheCreateTokens(nz(incoming.getCacheCreateTokens()));
        session.setCacheReadTokens(nz(incoming.getCacheReadTokens()));

        onSessionUpsert(session, incoming, isNew);
        evaluateInvalidReason(session);
        sessionRepository.save(session);

        Set<String> sourceRefsInTxn = isNew
                ? new HashSet<>()
                : new HashSet<>(eventRepository.findSourceRefsByAiSessionId(session.getId()));
        int deltaEvents = writeActivityDeltasFromClient(session, incoming, sseEvents, sourceRefsInTxn);
        if (deltaEvents == 0 && !isNew) {
            deltaEvents = writeDeltasFromRecentMessages(session, incoming, sseEvents, sourceRefsInTxn);
        }
        eventsWritten += deltaEvents;
        boolean hadClientTimedDeltas = deltaEvents > 0;
        boolean prefersPerItemDeltaPath = prefersPerItemDeltaPath(
                incoming, isNew ? null : session.getId());

        if (isNew) {
            // <p>v2.9 baseline 写入策略：客户端 backfill 一个历史会话（startedAt=4-29、lastActivity=4-29）
            // 第一次进 ingest 时 isNew=true，previousInput/Output/Messages 全是 0（新建实体的默认值）。
            //
            // <p>之前两次踩过的坑：
            // <ul>
            //   <li>v2.7：把 token / 消息累计当"今日 delta"写成 event_time=now → 几周前的会话被算成"今日产生"</li>
            //   <li>v2.8：直接 skip baseline，结果按 event_time 切片的 4 月窗口拿不到该会话任何数据
            //       → 员工数据 4 月窗口、project detail 4 月窗口全部 0</li>
            // </ul>
            //
            // <p>v2.9+：baseline 与增量 event_time 均锚客户端时间（last_activity / started_at / captured_at），
            // 禁止服务端 wall-clock，避免历史回填被算进「今日 Top」。
            LocalDateTime sessionOpenTime = or(incoming.getStartedAt(), activityTime);
            if (sessionOpenTime != null) {
                sseEvents.add(writeEvent(session, AiSessionEventType.SESSION_OPEN, session.getStatus(), null,
                        0L, 0L, 0, sessionOpenTime, null, sourceRefsInTxn));
                eventsWritten++;
            }

            if (!hadClientTimedDeltas) {
                LocalDateTime baselineTime = activityTime;
                long baselineInput = nz(session.getInputTokens());
                long baselineOutput = nz(session.getOutputTokens());
                if (baselineTime != null && (baselineInput > 0 || baselineOutput > 0)) {
                    sseEvents.add(writeEvent(session, AiSessionEventType.TOKEN_DELTA, session.getStatus(), null,
                            baselineInput, baselineOutput, 0, baselineTime, null, sourceRefsInTxn));
                    eventsWritten++;
                }
                int baselineMessages = session.getTotalMessages();
                if (baselineTime != null && baselineMessages > 0) {
                    sseEvents.add(writeEvent(session, AiSessionEventType.MESSAGE_DELTA, session.getStatus(), null,
                            0L, 0L, baselineMessages, baselineTime, null, sourceRefsInTxn));
                    eventsWritten++;
                }
            }
        } else {
            LocalDateTime eventTime = session.getLastActivity();
            if (eventTime != null && previousStatus != null && !previousStatus.equals(session.getStatus())) {
                sseEvents.add(writeEvent(session, AiSessionEventType.STATUS_CHANGE, session.getStatus(), null,
                        0L, 0L, 0, eventTime, null, sourceRefsInTxn));
                eventsWritten++;
            }

            if (!hadClientTimedDeltas && !prefersPerItemDeltaPath) {
                long inputDelta = nz(session.getInputTokens()) - previousInput;
                long outputDelta = nz(session.getOutputTokens()) - previousOutput;
                if (eventTime != null && (inputDelta != 0 || outputDelta != 0)) {
                    sseEvents.add(writeEvent(session, AiSessionEventType.TOKEN_DELTA, session.getStatus(), null,
                            inputDelta, outputDelta, 0, eventTime, null, sourceRefsInTxn));
                    eventsWritten++;
                }

                int messagesDelta = clampFallbackMessageDelta(
                        session.getTotalMessages() - previousMessages);
                if (eventTime != null && messagesDelta > 0) {
                    sseEvents.add(writeEvent(session, AiSessionEventType.MESSAGE_DELTA, session.getStatus(), null,
                            0L, 0L, messagesDelta, eventTime, null, sourceRefsInTxn));
                    eventsWritten++;
                }
            }
        }

        if (incoming.getRecentTools() != null && !incoming.getRecentTools().isEmpty()) {
            LocalDateTime toolFallback = session.getLastActivity();
            LocalDateTime minTs = toolFallback;
            LocalDateTime maxTs = toolFallback;
            for (ToolCallDto tool : incoming.getRecentTools()) {
                if (tool == null) {
                    continue;
                }
                LocalDateTime ts = or(tool.getTimestamp(), toolFallback);
                if (ts == null) {
                    continue;
                }
                if (minTs == null || ts.isBefore(minTs)) {
                    minTs = ts;
                }
                if (maxTs == null || ts.isAfter(maxTs)) {
                    maxTs = ts;
                }
            }
            if (minTs != null && maxTs != null) {
                Set<String> existingToolKeys = new HashSet<>();
                if (!isNew) {
                    for (Object[] row : eventRepository.findToolCallKeysByAiSessionIdAndEventTimeBetween(
                            session.getId(), minTs, maxTs)) {
                        if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                            existingToolKeys.add(row[0] + "\0" + row[1]);
                        }
                    }
                }
                for (ToolCallDto tool : incoming.getRecentTools()) {
                    if (tool == null || tool.getName() == null) {
                        continue;
                    }
                    LocalDateTime ts = or(tool.getTimestamp(), toolFallback);
                    if (ts == null || existingToolKeys.contains(tool.getName() + "\0" + ts)) {
                        continue;
                    }
                    sseEvents.add(writeEvent(session, AiSessionEventType.TOOL_CALL, session.getStatus(),
                            tool.getName(), 0L, 0L, 0, ts, tool.getName() + "\0" + ts, sourceRefsInTxn));
                    eventsWritten++;
                }
            }
        }
        return new UpsertOutcome(session, eventsWritten);
    }

    /**
     * 客户端 {@code activity_deltas}：每条增量带原始 event_time（Claude assistant.usage、Codex token_count 等）。
     */
    private int writeActivityDeltasFromClient(AiSession session, MonitorSessionDto incoming,
                                              List<AiSessionEvent> sseEvents, Set<String> sourceRefsInTxn) {
        if (incoming.getActivityDeltas() == null || incoming.getActivityDeltas().isEmpty()) {
            return 0;
        }
        int written = 0;
        for (ActivityDeltaDto d : incoming.getActivityDeltas()) {
            if (d == null || d.getEventTime() == null) {
                continue;
            }
            String ref = d.getSourceRef();
            if (ref != null && !ref.isBlank()
                    && hasSourceRefEvent(ref, sourceRefsInTxn)) {
                continue;
            }
            long inD = nz(d.getInputTokensDelta());
            long outD = nz(d.getOutputTokensDelta());
            // 服务端兜底：客户端累计计数回退（Codex 压缩 / 重置）时可能下发负向 token 增量，
            // 老版本 agent 不会立即升级，这里裁 0 防止「今日 Token」求和变负、前端越界。
            // 与 clampFallbackMessageDelta「禁止负向事件」同口径。
            inD = clampTokenDelta(inD);
            outD = clampTokenDelta(outD);
            int msgD = d.getMessagesDelta() == null ? 0 : d.getMessagesDelta();
            if (inD != 0 || outD != 0) {
                sseEvents.add(writeEvent(session, AiSessionEventType.TOKEN_DELTA, session.getStatus(), null,
                        inD, outD, 0, d.getEventTime(), ref, sourceRefsInTxn));
                written++;
            }
            if (msgD > 0) {
                String msgRef = ref == null || ref.isBlank() ? null : ref + ":msg";
                if (msgRef == null || !hasSourceRefEvent(msgRef, sourceRefsInTxn)) {
                    sseEvents.add(writeEvent(session, AiSessionEventType.MESSAGE_DELTA, session.getStatus(), null,
                            0L, 0L, msgD, d.getEventTime(), msgRef, sourceRefsInTxn));
                    written++;
                }
            }
        }
        return written;
    }

    /** 无 activity_deltas 时，从本 tick recent_messages 的 timestamp + 逐条 token 推导增量。 */
    private int writeDeltasFromRecentMessages(AiSession session, MonitorSessionDto incoming,
                                              List<AiSessionEvent> sseEvents, Set<String> sourceRefsInTxn) {
        if (incoming.getRecentMessages() == null || incoming.getRecentMessages().isEmpty()) {
            return 0;
        }
        int written = 0;
        for (ConversationMessageDto m : incoming.getRecentMessages()) {
            if (m == null || m.getTimestamp() == null) {
                continue;
            }
            String ref = m.getExternalMessageId();
            if (ref == null || ref.isBlank()) {
                continue;
            }
            if (hasSourceRefEvent(ref, sourceRefsInTxn)) {
                continue;
            }
            long inD = nz(m.getInputTokens() == null ? null : m.getInputTokens().longValue());
            long outD = nz(m.getOutputTokens() == null ? null : m.getOutputTokens().longValue());
            if (inD > 0 || outD > 0) {
                sseEvents.add(writeEvent(session, AiSessionEventType.TOKEN_DELTA, session.getStatus(), null,
                        inD, outD, 0, m.getTimestamp(), ref, sourceRefsInTxn));
                written++;
            }
            String role = m.getRole();
            if ("user".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role)) {
                String msgRef = ref + ":msg";
                if (!hasSourceRefEvent(msgRef, sourceRefsInTxn)) {
                    sseEvents.add(writeEvent(session, AiSessionEventType.MESSAGE_DELTA, session.getStatus(), null,
                            0L, 0L, 1, m.getTimestamp(), msgRef, sourceRefsInTxn));
                    written++;
                }
            }
        }
        return written;
    }

    AiSessionEvent writeEvent(AiSession session, AiSessionEventType type, String status, String tool,
                                       long inputTokensDelta, long outputTokensDelta,
                                       int messagesDelta, LocalDateTime time, String sourceRef,
                                       Set<String> sourceRefsInTxn) {
        AiSessionEvent event = new AiSessionEvent();
        event.setAiSessionId(session.getId());
        event.setTargetType(session.getTargetType());
        event.setUserCode(session.getUserCode());
        event.setEventTime(time);
        event.setEventType(type.name());
        event.setStatus(status);
        event.setToolName(tool);
        event.setInputTokensDelta(inputTokensDelta);
        event.setOutputTokensDelta(outputTokensDelta);
        event.setTokensDelta(inputTokensDelta + outputTokensDelta);
        event.setMessagesDelta(messagesDelta);
        if (sourceRef != null && !sourceRef.isBlank()) {
            // source_ref 列上限 191(utf8mb4 索引前缀安全长度)。delta/message 的 ref(UUID / msgid)恒短不受影响;
            // 仅 TOOL_CALL 的 name+"\0"+ts 可能超长(tool_name 设计上可达 512)——它只作"该会话有 source_ref"的标记、
            // 不作去重键(工具去重走 existingToolKeys),故截断无害,且避免 strict-mode MySQL "Data too long" 拖垮整份上报。
            event.setSourceRef(sourceRef.length() > 191 ? sourceRef.substring(0, 191) : sourceRef);
            markSourceRef(sourceRefsInTxn, sourceRef);
        }
        return eventRepository.save(event);
    }

    private boolean hasSourceRefEvent(String sourceRef, Set<String> sourceRefsInTxn) {
        return sourceRefsInTxn.contains(sourceRef);
    }

    private static void markSourceRef(Set<String> sourceRefsInTxn, String sourceRef) {
        if (sourceRefsInTxn != null && sourceRef != null && !sourceRef.isBlank()) {
            sourceRefsInTxn.add(sourceRef);
        }
    }

    /**
     * 是否走逐条增量路径（activity_deltas / recent_messages / 历史 source_ref），
     * 此类会话禁止用快照累计差写 MESSAGE_DELTA fallback，避免 Cursor 重算计数时刷负向事件。
     */
    boolean prefersPerItemDeltaPath(MonitorSessionDto incoming, Long sessionId) {
        if (incoming.getActivityDeltas() != null && !incoming.getActivityDeltas().isEmpty()) {
            return true;
        }
        if (incoming.getRecentMessages() != null && !incoming.getRecentMessages().isEmpty()) {
            return true;
        }
        return sessionId != null && eventRepository.countByAiSessionIdWithAnySourceRef(sessionId) > 0;
    }

    /** 快照累计 fallback 只允许正向消息增量；计数回退是解析 artifact，不应进 event 流。 */
    static int clampFallbackMessageDelta(int delta) {
        return Math.max(delta, 0);
    }

    /** 客户端累计 token 计数回退（压缩 / 重置）会算出负增量；只允许正向，避免汇总变负。 */
    static long clampTokenDelta(long delta) {
        return Math.max(delta, 0L);
    }

    private int writeMessages(AiSession session, MonitorSessionDto incoming, SignatureContext ctx,
                              Set<Long> reconciledSessions) {
        if (incoming.getRecentMessages() == null || incoming.getRecentMessages().isEmpty()) {
            return 0;
        }
        int seq = messageRepository.maxSequenceNoByAiSessionId(session.getId());
        int written = 0;
        Set<String> seen = new HashSet<>();
        Set<String> existingExternalIds = new HashSet<>(
                messageRepository.findExternalMessageIdsByAiSessionId(session.getId()));

        for (ConversationMessageDto m : incoming.getRecentMessages()) {
            if (m == null) {
                continue;
            }
            // 丢弃 Claude Code 本地命令包装的 user 行（未见模型）；规则见 LocalCommandNoise。
            if (LocalCommandNoise.isNoise(m.getRole(), m.getText())) {
                continue;
            }
            String externalId = m.getExternalMessageId();
            if (externalId != null) {
                if (!seen.add(externalId)) {
                    continue;
                }
                if (existingExternalIds.contains(externalId)) {
                    patchExistingConversationOrder(session.getId(), externalId, m);
                    continue;
                }
            }
            AiSessionMessage msg = new AiSessionMessage();
            msg.setAiSessionId(session.getId());
            msg.setTargetType(session.getTargetType());
            msg.setUserCode(ctx.getUserCode());
            msg.setExternalMessageId(externalId);
            msg.setRole(m.getRole() == null ? "user" : m.getRole());
            msg.setSequenceNo(++seq);
            msg.setToolName(m.getToolName());
            msg.setInputTokens(nz(m.getInputTokens()));
            msg.setOutputTokens(nz(m.getOutputTokens()));
            msg.setMessageTime(or(m.getTimestamp(), session.getLastActivity()));
            if (m.getConversationOrder() != null && m.getConversationOrder() > 0) {
                msg.setConversationOrder(m.getConversationOrder());
            }

            String slashSource = m.getText();
            boolean isUser = "user".equalsIgnoreCase(msg.getRole());
            MessageContentIngestService.PreparedMessage prepared = null;
            try {
                if (messageContentIngestService != null) {
                    prepared = messageContentIngestService.prepare(m.getContentParts(), m.getText());
                    msg.setContentPartsJson(prepared.contentPartsJson());
                    msg.setContentText(prepared.contentText());
                    msg.setContentKind(prepared.contentKind());
                    msg.setHasBinary(prepared.hasBinary() ? 1 : 0);
                    msg.setPartsCount(prepared.partsCount());
                    msg.setIngestVersion(1);
                    slashSource = prepared.contentText();
                } else {
                    msg.setContentText(m.getText());
                    msg.setContentKind("text_only");
                    msg.setIngestVersion(0);
                }
            } catch (Exception ex) {
                log.warn("prepare message content failed session={} extId={}: {}",
                        session.getId(), externalId, ex.getMessage());
                msg.setContentText(m.getText());
                msg.setContentKind("text_only");
                msg.setIngestVersion(0);
            }
            if (isUser) {
                UserSlashInvocationExtractor.Annotation slash =
                        UserSlashInvocationExtractor.annotateUserContent(
                                slashSource != null ? slashSource : "", session.getTargetType());
                msg.setSlashCommandCount(slash.slashCommandCount());
                msg.setSlashSkillCount(slash.slashSkillCount());
                msg.setSlashHitsJson(slash.slashHitsJson());
            }
            msg = messageRepository.save(msg);
            if (prepared != null) {
                messageContentIngestService.saveLinks(msg.getId(), prepared.links());
            }
            if (externalId != null) {
                existingExternalIds.add(externalId);
            }
            written++;
            continue;
        }
        if (written > 0 && reconciledSessions.add(session.getId())) {
            NlSkillAttributionSupport.reconcileSession(messageRepository, session.getId());
        }
        return written;
    }

    /** 存量消息补写 conversation_order / 修正 message_time（Cursor 续聊批量改写 createdAt 后的回填）。 */
    private void patchExistingConversationOrder(Long sessionId, String externalId, ConversationMessageDto m) {
        if (m.getConversationOrder() == null || m.getConversationOrder() <= 0) {
            return;
        }
        LocalDateTime messageTime = or(m.getTimestamp(), null);
        if (messageTime == null) {
            return;
        }
        messageRepository.patchConversationOrderAndTime(
                sessionId, externalId, m.getConversationOrder(), messageTime);
    }

    /**
     * v2.11：评估并自动收敛 {@code invalid_reason}。
     *
     * <p>当一个会话满足以下"模型从未真正回应"特征时，打上 invalid 标：
     * <ul>
     *   <li>{@code status == idle}（会话已稳定下来，不在写入/思考中——见下文"瞬时误伤"说明）</li>
     *   <li>{@code user_messages > 0}（用户在客户端输入过内容，包括本地斜杠命令）</li>
     *   <li>{@code assistant_messages == 0} 且 {@code input_tokens == 0} 且 {@code output_tokens == 0}</li>
     * </ul>
     *
     * <p><b>为什么必须加 status==idle 这个条件</b>：用户在 IDE 里刚按下回车、模型还没开始
     * 输出 token 的那短短几秒内，reporter 已经把 user_messages=1 上报上来了。这一瞬间
     * 严格按 token=0 / assist=0 判定会让活跃会话被误标，从 Sessions 列表、Dashboard
     * 活跃数里短暂消失，直到下一次 ingest（≈10s 后）模型有了 token 才恢复，体验闪烁。
     * 等到 status 真的回到 idle 还 token=0、assist=0，才能确凿断定"模型从未回应"。
     *
     * <p>状态自收敛：模型一旦真的回应（assist 或 token 变正），下一次 ingest 进来
     * 这里会重新把 {@code invalid_reason} 清空，会话回到有效集合。客户端先敲了
     * {@code /usage} 再开始正常对话的场景，会先被标 invalid、再自动恢复，完全符合预期。
     *
     * <p>取值约定：claude 来源大概率是 Claude Code 的本地斜杠命令，标
     * {@code "local_command_only"}；其它来源用兜底原因 {@code "no_assistant_reply"}。
     */
    private void evaluateInvalidReason(AiSession session) {
        boolean isIdle = AiSessionStatus.IDLE.code().equalsIgnoreCase(session.getStatus());
        boolean invalid = isIdle
                && nz(session.getUserMessages()) > 0
                && nz(session.getAssistantMessages()) == 0
                && nz(session.getInputTokens()) == 0L
                && nz(session.getOutputTokens()) == 0L;
        if (invalid) {
            if (session.getInvalidReason() == null) {
                String reason = "claude".equalsIgnoreCase(session.getTargetType())
                        ? "local_command_only"
                        : "no_assistant_reply";
                session.setInvalidReason(reason);
            }
        } else if (session.getInvalidReason() != null
                && !"merged_subagent".equals(session.getInvalidReason())) {
            // 状态变成非 idle（用户又开始打字 / 模型在回应），或终于有了 token/assist，
            // 立即解除 invalid 标记，避免"活跃但仍 invalid"的自相矛盾态。
            // merged_subagent 由 ingest 显式收敛，不在此清除。
            session.setInvalidReason(null);
        }
    }

    /**
     * 从父会话上报的消息 external_message_id 前缀识别子 composer UUID，用于 suppress 历史误建的独立 ai_session。
     */
    private Set<String> childComposerIdsFromMessages(AiSession session, MonitorSessionDto incoming) {
        if (!"cursor".equalsIgnoreCase(targetType()) || session == null
                || session.getExternalSessionId() == null || incoming.getRecentMessages() == null) {
            return Set.of();
        }
        String parentExt = session.getExternalSessionId().trim();
        LinkedHashSet<String> childIds = new LinkedHashSet<>();
        for (ConversationMessageDto m : incoming.getRecentMessages()) {
            if (m == null) {
                continue;
            }
            String extId = m.getExternalMessageId();
            if (extId == null || extId.isBlank()) {
                continue;
            }
            int sep = extId.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            String prefix = extId.substring(0, sep).trim();
            if (!prefix.isEmpty() && !prefix.equalsIgnoreCase(parentExt)) {
                childIds.add(prefix);
            }
        }
        return childIds;
    }

    /**
     * 将已归并到父 chat 的子 composer 标为 invalid，从 Sessions 列表 / 统计中隐藏。
     */
    private void suppressMergedSubagentSessions(List<String> externalSessionIds, SignatureContext ctx) {
        if (externalSessionIds == null || externalSessionIds.isEmpty() || ctx == null) {
            return;
        }
        for (String extId : externalSessionIds) {
            if (extId == null || extId.isBlank()) {
                continue;
            }
            sessionRepository.findByTargetTypeAndExternalSessionId(targetType(), extId.trim())
                    .ifPresent(session -> {
                        if (!ctx.getAgentId().equals(session.getAgentId())) {
                            return;
                        }
                        if ("merged_subagent".equals(session.getInvalidReason())) {
                            return;
                        }
                        session.setInvalidReason("merged_subagent");
                        sessionRepository.save(session);
                    });
        }
    }

    private boolean isActive(AiSession session, LocalDateTime now) {
        if (AiSessionStatus.IDLE.code().equalsIgnoreCase(session.getStatus())) {
            return false;
        }
        if (session.getLastActivity() == null) {
            return false;
        }
        return Duration.between(session.getLastActivity(), now).getSeconds() <= activeWindowSeconds();
    }

    protected static long nz(Long v) {
        return v == null ? 0L : v;
    }

    protected static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static int resolveSnapshotMessageCount(MonitorSessionDto incoming) {
        if (incoming.getSnapshotMessageCount() != null && incoming.getSnapshotMessageCount() > 0) {
            return incoming.getSnapshotMessageCount();
        }
        if (incoming.getRecentMessages() != null) {
            return incoming.getRecentMessages().size();
        }
        return 0;
    }

    protected static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    /**
     * 会话/事件时间锚：last_activity → started_at → 本 tick 快照 captured_at。
     * 不用服务端 wall-clock，避免 agent 重扫历史会话时把 token 增量记到「今天」。
     */
    private static LocalDateTime resolveActivityTime(MonitorSessionDto incoming, LocalDateTime snapshotCapturedAt) {
        if (incoming.getLastActivity() != null) {
            return incoming.getLastActivity();
        }
        if (incoming.getStartedAt() != null) {
            return incoming.getStartedAt();
        }
        return snapshotCapturedAt;
    }

    private static class UpsertOutcome {
        final AiSession session;
        final int eventsWritten;

        UpsertOutcome(AiSession session, int eventsWritten) {
            this.session = session;
            this.eventsWritten = eventsWritten;
        }
    }
}
