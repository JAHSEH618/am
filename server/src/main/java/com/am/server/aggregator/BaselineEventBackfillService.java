package com.am.server.aggregator;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionEventType;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 历史会话 baseline event 一次性回填工具。
 *
 * <p><b>背景</b>：v2.7 ~ v2.8 期间客户端 backfill 历史会话时，server 端 ingest 处理 isNew=true
 * 走过两版策略——v2.7 把累计 token 当 "今日 delta" 写成 event_time=now（后被批量清理），
 * v2.8 直接 skip TOKEN_DELTA / MESSAGE_DELTA。两个版本都有副作用：v2.7 写入的 baseline 被
 * 当成抖动事件清掉了、v2.8 干脆就没写。结果存量历史会话（4 月那批）在 ai_session_event
 * 表里只有 TOOL_CALL，没有 SESSION_OPEN / TOKEN_DELTA / MESSAGE_DELTA。
 *
 * <p>v2.9 起 ingest 已经修正成"baseline 照写、event_time 锚到会话自身时间"，但**老数据**得
 * 一次性回补。这个 service 就干这个事——按 [from, to] 区间扫 ai_session 表，对"event 流
 * 缺失 baseline"的会话按 ai_session 累计字段补写 SESSION_OPEN / TOKEN_DELTA / MESSAGE_DELTA。
 *
 * <p><b>幂等性</b>：检查 ai_session_event 里是否已存在该会话的对应 eventType，已有则跳过；
 * 多次运行同一区间不会重复写入。已有 N 条 TOKEN_DELTA 加起来等于会话累计 token 的会话也不
 * 会再补一条（避免 token 翻倍）——SUM 校正逻辑刻意未做，因为窗口聚合靠 SUM 而非真值校验，
 * 拿"是否已存在某类型"判定 is good enough。
 *
 * gz
 */
@Component
@RequiredArgsConstructor
public class BaselineEventBackfillService {

    private static final Logger log = LoggerFactory.getLogger(BaselineEventBackfillService.class);

    private final AiSessionRepository sessionRepository;
    private final AiSessionEventRepository eventRepository;
    private final DailySummaryAggregator dailySummaryAggregator;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;

    /**
     * 扫 [from, to] 区间内所有 startedAt 或 lastActivity 落入的会话，给 event 流空白的部分补
     * baseline event；最后把涉及到的 work_date 喂给 {@link DailySummaryAggregator#enqueueRefresh}
     * 让 daily_summary 自动跟上。
     */
    @Transactional
    public BackfillResult backfill(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            LocalDate tmp = from; from = to; to = tmp;
        }
        LocalDateTime t0 = from.atStartOfDay();
        LocalDateTime t1 = to.plusDays(1).atStartOfDay();

        // 与员工数据 / 报告一致：只回补 monitor_target.enabled=1 且有效会话；禁用 agent 不入库事件、不触发重聚放大
        java.util.Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        List<AiSession> sessions = activeTypes.isEmpty()
                ? java.util.List.of()
                : sessionRepository.findOverlappingByStartedOrLastActivityAndTargetTypeIn(t0, t1, activeTypes);
        BackfillResult result = new BackfillResult();
        result.from = from;
        result.to = to;
        result.sessionsScanned = sessions.size();

        Set<LocalDate> affectedDates = new LinkedHashSet<>();
        LocalDateTime fallbackNow = LocalDateTime.now();

        for (AiSession s : sessions) {
            // event 流当前已有的 eventType 集合——决定哪些 baseline 还缺。
            Set<String> existing = new HashSet<>(eventRepository.findDistinctEventTypesByAiSessionId(s.getId()));

            // SESSION_OPEN：缺则补，event_time 用 startedAt（兜底 lastActivity → now）。
            if (!existing.contains(AiSessionEventType.SESSION_OPEN.name())) {
                LocalDateTime t = firstNonNull(s.getStartedAt(), s.getLastActivity(), fallbackNow);
                writeBaseline(s, AiSessionEventType.SESSION_OPEN, t, 0L, 0L, 0);
                result.sessionOpensCreated++;
                affectedDates.add(t.toLocalDate());
            }

            // TOKEN_DELTA：缺且累计 > 0 才补，event_time 用 lastActivity（兜底 startedAt → now）。
            long input = nz(s.getInputTokens());
            long output = nz(s.getOutputTokens());
            if ((input > 0 || output > 0) && !existing.contains(AiSessionEventType.TOKEN_DELTA.name())) {
                LocalDateTime t = firstNonNull(s.getLastActivity(), s.getStartedAt(), fallbackNow);
                writeBaseline(s, AiSessionEventType.TOKEN_DELTA, t, input, output, 0);
                result.tokenDeltasCreated++;
                affectedDates.add(t.toLocalDate());
            }

            // MESSAGE_DELTA：同上，count 走 totalMessages 兜底 user+assistant。
            int messages = nz(s.getTotalMessages());
            if (messages == 0) {
                messages = nz(s.getUserMessages()) + nz(s.getAssistantMessages());
            }
            if (messages > 0 && !existing.contains(AiSessionEventType.MESSAGE_DELTA.name())) {
                LocalDateTime t = firstNonNull(s.getLastActivity(), s.getStartedAt(), fallbackNow);
                writeBaseline(s, AiSessionEventType.MESSAGE_DELTA, t, 0L, 0L, messages);
                result.messageDeltasCreated++;
                affectedDates.add(t.toLocalDate());
            }
        }

        result.affectedDates = affectedDates;
        if (!affectedDates.isEmpty()) {
            // 触发 daily_summary debounce 重聚——等 15s 后台跑一遍每个日期。
            dailySummaryAggregator.enqueueRefresh(affectedDates);
        }
        log.info("baseline backfill: from={} to={} sessions={} session_opens={} token_deltas={} message_deltas={} dates_touched={}",
                result.from, result.to, result.sessionsScanned, result.sessionOpensCreated,
                result.tokenDeltasCreated, result.messageDeltasCreated, affectedDates.size());
        return result;
    }

    private void writeBaseline(AiSession s, AiSessionEventType type, LocalDateTime time,
                                long inputTokensDelta, long outputTokensDelta, int messagesDelta) {
        AiSessionEvent e = new AiSessionEvent();
        e.setAiSessionId(s.getId());
        e.setTargetType(s.getTargetType());
        e.setUserCode(s.getUserCode());
        e.setEventTime(time);
        e.setEventType(type.name());
        e.setStatus(s.getStatus());
        e.setToolName(null);
        e.setInputTokensDelta(inputTokensDelta);
        e.setOutputTokensDelta(outputTokensDelta);
        e.setTokensDelta(inputTokensDelta + outputTokensDelta);
        e.setMessagesDelta(messagesDelta);
        eventRepository.save(e);
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
    private static int nz(Integer v) { return v == null ? 0 : v; }

    @SafeVarargs
    private static <T> T firstNonNull(T... candidates) {
        for (T c : candidates) {
            if (c != null) return c;
        }
        return null;
    }

    /** 返回值：调用方观测回填规模 + 涉及到的 work_date。 */
    public static class BackfillResult {
        public LocalDate from;
        public LocalDate to;
        public int sessionsScanned;
        public int sessionOpensCreated;
        public int tokenDeltasCreated;
        public int messageDeltasCreated;
        public Set<LocalDate> affectedDates = new LinkedHashSet<>();
    }
}
