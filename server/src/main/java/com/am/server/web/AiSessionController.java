package com.am.server.web;

import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.insight.aggregate.BackfillSnapshotSupport;
import com.am.server.insight.domain.AiSessionAudit;
import com.am.server.insight.domain.AiSessionAuditRepository;
import com.am.server.service.AiSessionMessageBlobService;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.AiSessionAuditDetailDto;
import com.am.server.web.dto.AiSessionAuditSummaryDto;
import com.am.server.web.dto.AiSessionDto;
import com.am.server.web.dto.AiSessionEventDto;
import com.am.server.web.dto.AiSessionMessageDto;
import com.am.server.web.dto.PageDto;
import com.am.server.web.dto.SlashInvocationDto;
import com.am.server.web.support.SessionMessageCountSupport;
import com.am.server.web.support.SlashInvocationMergeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 会话查询接口（列表 + 详情 + 消息 + 事件流水）
 *
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai-sessions")
public class AiSessionController {

    private final AiSessionRepository sessionRepository;
    private final AiSessionMessageRepository messageRepository;
    private final AiSessionEventRepository eventRepository;
    private final AiSessionAuditRepository sessionAuditRepository;
    private final EmployeeDisplayService employeeDisplayService;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final AiSessionMessageBlobService messageBlobService;

    @GetMapping
    public R<PageDto<AiSessionDto>> list(
            // 兼容旧入参 user_code（前端 / 三方脚本可能仍在用）
            @RequestParam(name = "user_code", required = false) String userCode,
            // 新增：按"姓名 / 工号"模糊搜索；命中员工的所有 userCode 用 IN 查询
            @RequestParam(name = "user_name", required = false) String userNameKw,
            @RequestParam(required = false) Integer days,
            // v2.4：精确时间窗口。from/to 优先级高于 days；命中后会按 message_time 重算
            // window_message_count / window_tokens，让一个跨期 session 在窗口内只展示窗内消耗。
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "target_type", required = false) String targetType,
            @RequestParam(name = "project_name", required = false) String projectName,
            /** 为 true 时只返回 status != idle，与大盘 {@code active_ai_sessions} 口径一致；不按 last_activity 时间窗收窄列表。 */
            @RequestParam(name = "active_only", defaultValue = "false") boolean activeOnly,
            /**
             * v2.11：是否包含 invalid_reason 非空的无效会话（用户只发过本地命令、模型从未回应）。
             * <p>默认 false——前端列表默认隐藏；运维排查脏数据时可传 {@code include_invalid=1}
             * 查看全量。{@code active_only=true} 时 invalid 会话本身已被 non-idle 查询的
             * invalid_reason IS NULL 过滤，不受此参数影响。
             */
            @RequestParam(name = "include_invalid", defaultValue = "false") boolean includeInvalid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {

        PageRequest pageable = PageRequest.of(page, Math.min(Math.max(size, 1), 1000));

        // v2.10：用 "有效白名单" 替代用户传入的 target_type 过滤。
        //   - 用户没传 target_type / 传了 "all"           → 用全部 active types
        //   - 用户传了具体值                                → 与 active types 取交集
        //   - 交集为空（用户在 URL 里指定了已禁用的 agent） → 直接返回空页，防止绕过
        // 这样禁用某 agent 后：列表里这 agent 的会话立刻消失，前端筛选下拉本就只列 active，整套口径一致。
        java.util.Collection<String> active = activeTargetTypesProvider.getActiveTypes();
        if (active.isEmpty()) {
            return R.ok(PageDto.empty(pageable));
        }
        List<String> userPicked = parseTargetTypes(targetType);
        List<String> targets = effectiveTargetTypes(userPicked, active);
        if (targets.isEmpty()) {
            return R.ok(PageDto.empty(pageable));
        }

        boolean hasProject = projectName != null && !projectName.isBlank();
        boolean hasUserCode = userCode != null && !userCode.isBlank();
        boolean hasUserNameKw = userNameKw != null && !userNameKw.isBlank();

        List<String> matchedUserCodes = null;
        if (hasUserNameKw) {
            matchedUserCodes = employeeDisplayService.searchUserCodes(userNameKw);
            if (matchedUserCodes.isEmpty()) {
                return R.ok(PageDto.empty(pageable));
            }
        } else if (hasUserCode) {
            matchedUserCodes = List.of(userCode);
        }
        boolean hasUser = matchedUserCodes != null;

        // 解析窗口：from/to 显式传入 → 用 [from 0:00, to+1day 0:00)；否则按 days 兜底
        LocalDateTime[] window = resolveWindow(from, to, days);
        LocalDateTime windowStart = window[0];
        LocalDateTime windowEnd = window[1];
        // 列表行集合与时间窗对齐：半开区间 [windowStart, windowEnd)，且 started_at / last_activity
        // 任一落入即命中（overlap），与分析报告 findOverlappingByStartedOrLastActivityAndTargetTypeIn 同构，
        // 避免「只有 last_activity >= 周一」却无视周日截止而把窗外会话（如 5-18）捞出来。
        // 窗内消息数 / token 仍按 event 聚合由 toSessionDtoWithWindow 计算。
        // active_only：与大盘活跃数一致，只筛非 idle，不再用 last_activity 时间窗收窄行集合
        // （窗内 token / 消息仍走 toSessionDtoWithWindow）。
        Page<AiSession> p;
        if (activeOnly) {
            if (hasProject) {
                if (hasUser && matchedUserCodes.size() == 1) {
                    p = sessionRepository.findAllNonIdleByProjectNameAndUserCodeAndTargetTypeIn(
                            projectName, matchedUserCodes.get(0), targets, pageable);
                } else {
                    p = sessionRepository.findAllNonIdleByProjectNameAndTargetTypeIn(
                            projectName, targets, pageable);
                }
            } else if (hasUser) {
                p = sessionRepository.findAllNonIdleByTargetTypeInAndUserCodeIn(
                        targets, matchedUserCodes, pageable);
            } else {
                p = sessionRepository.findAllNonIdleByTargetTypeIn(targets, pageable);
            }
        } else if (hasProject) {
            if (hasUser && matchedUserCodes.size() == 1) {
                p = includeInvalid
                        ? sessionRepository.findOverlappingWindowByProjectNameAndUserCodeAndTargetTypeInIncludingInvalid(
                                projectName, matchedUserCodes.get(0), targets, windowStart, windowEnd, pageable)
                        : sessionRepository.findOverlappingWindowByProjectNameAndUserCodeAndTargetTypeIn(
                                projectName, matchedUserCodes.get(0), targets, windowStart, windowEnd, pageable);
            } else {
                p = includeInvalid
                        ? sessionRepository.findOverlappingWindowByProjectNameAndTargetTypeInIncludingInvalid(
                                projectName, targets, windowStart, windowEnd, pageable)
                        : sessionRepository.findOverlappingWindowByProjectNameAndTargetTypeIn(
                                projectName, targets, windowStart, windowEnd, pageable);
            }
        } else if (hasUser) {
            p = includeInvalid
                    ? sessionRepository.findOverlappingWindowByTargetTypeInAndUserCodeInIncludingInvalid(
                            targets, matchedUserCodes, windowStart, windowEnd, pageable)
                    : sessionRepository.findOverlappingWindowByTargetTypeInAndUserCodeIn(
                            targets, matchedUserCodes, windowStart, windowEnd, pageable);
        } else {
            p = includeInvalid
                    ? sessionRepository.findOverlappingWindowByTargetTypeInIncludingInvalid(
                            targets, windowStart, windowEnd, pageable)
                    : sessionRepository.findOverlappingWindowByTargetTypeIn(
                            targets, windowStart, windowEnd, pageable);
        }
        Map<Long, AiSessionAudit> auditBySessionId = batchSessionAudits(p.getContent());
        Map<Long, long[]> windowAggBySessionId = batchWindowAggregates(p.getContent(), windowStart, windowEnd);
        List<AiSessionDto> items = p.getContent().stream().map(s -> {
            AiSessionDto d = toSessionDtoWithWindow(s, windowAggBySessionId.get(s.getId()));
            AiSessionAudit a = auditBySessionId.get(s.getId());
            if (a != null) {
                d.setAudit(AiSessionAuditSummaryDto.of(a));
            }
            return d;
        }).toList();
        attachSessionsSlashInvocations(items, windowStart, windowEnd);
        return R.ok(new PageDto<>(items, p.getTotalElements(), p.getNumber(), p.getSize()));
    }

    /**
     * 列表页：按当前列表时间窗合并各会话 user 消息的 {@code slash_hits_json}，去重枚举斜杠命令 / 技能等。
     */
    private void attachSessionsSlashInvocations(List<AiSessionDto> items,
                                                 LocalDateTime windowStart,
                                                 LocalDateTime windowEnd) {
        if (items.isEmpty()) {
            return;
        }
        List<Long> ids = items.stream().map(AiSessionDto::getId).toList();
        List<Object[]> rows =
                messageRepository.loadSlashHitsJsonForSessionsInMessageWindow(ids, windowStart, windowEnd);
        Map<Long, List<SlashInvocationDto>> byId = SlashInvocationMergeSupport.mergeHitsBySession(rows);
        for (AiSessionDto d : items) {
            List<SlashInvocationDto> list = byId.get(d.getId());
            d.setSlashInvocations(list != null ? list : List.of());
        }
    }

    /** 一页会话 id 批量聚合窗内 token / 消息数，避免 N+1 */
    private Map<Long, long[]> batchWindowAggregates(List<AiSession> sessions,
                                                    LocalDateTime from, LocalDateTime to) {
        if (sessions.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = sessions.stream().map(AiSession::getId).toList();
        Map<Long, long[]> out = new HashMap<>(ids.size());
        for (Long id : ids) {
            out.put(id, new long[]{0L, 0L});
        }
        for (Object[] row : eventRepository.aggregateSessionWindowsForSessions(ids, from, to)) {
            if (row == null || row.length < 1 || row[0] == null) {
                continue;
            }
            long sessionId = toLong(row[0]);
            long tokens = row.length > 1 ? toLong(row[1]) : 0L;
            long msgCount = row.length > 2 ? toLong(row[2]) : 0L;
            out.put(sessionId, new long[]{tokens, msgCount});
        }
        return out;
    }

    /** 一页会话 id 批量查审计行，避免 N+1 */
    private Map<Long, AiSessionAudit> batchSessionAudits(List<AiSession> sessions) {
        if (sessions.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = sessions.stream().map(AiSession::getId).toList();
        Map<Long, AiSessionAudit> m = new HashMap<>(ids.size());
        for (AiSessionAudit a : sessionAuditRepository.findByAiSessionIdIn(ids)) {
            m.putIfAbsent(a.getAiSessionId(), a);
        }
        return m;
    }

    /**
     * 把"用户筛选 ∩ 白名单"算成最终生效的 target_type 列表。
     * <ul>
     *   <li>userPicked = null（未筛选 / "all"）→ 返回完整 active 列表</li>
     *   <li>userPicked 非空 → 与 active 取交集；如果交集为空说明用户筛了已禁用的 agent，调用方应返回空页</li>
     * </ul>
     */
    private static List<String> effectiveTargetTypes(List<String> userPicked, java.util.Collection<String> active) {
        if (userPicked == null) {
            return new java.util.ArrayList<>(active);
        }
        java.util.Set<String> activeSet = new java.util.HashSet<>(active);
        return userPicked.stream().filter(activeSet::contains).collect(Collectors.toList());
    }

    /**
     * 解析时间窗口（v2.10 起默认窗口改为自然周，与员工数据 / 项目透视 / 模型与工具 / 分析报告页一致）：
     * <ul>
     *   <li>from/to 显式传入 → [from 00:00, to+1day 00:00)；只传一个时另一个回退到本周一 / 今天</li>
     *   <li>仅传 days → [today-days+1, now]（保留旧链接 / 三方脚本兼容）</li>
     *   <li>都没传 → 当前自然周 [本周一 00:00, 本周日 24:00)</li>
     * </ul>
     */
    private static LocalDateTime[] resolveWindow(LocalDate from, LocalDate to, Integer days) {
        if (from != null || to != null) {
            LocalDate today = LocalDate.now();
            LocalDate t = (to == null) ? today : to;
            LocalDate f = (from == null) ? t.with(DayOfWeek.MONDAY) : from;
            if (f.isAfter(t)) {
                LocalDate tmp = f; f = t; t = tmp;
            }
            return new LocalDateTime[]{f.atStartOfDay(), t.plusDays(1).atStartOfDay()};
        }
        if (days != null && days > 0) {
            LocalDateTime start = LocalDate.now().minusDays(days - 1L).atStartOfDay();
            return new LocalDateTime[]{start, LocalDateTime.now()};
        }
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate sunday = monday.plusDays(6);
        return new LocalDateTime[]{monday.atStartOfDay(), sunday.plusDays(1).atStartOfDay()};
    }

    /** Entity → DTO + 注入 user_display 展示串 */
    private AiSessionDto toSessionDto(AiSession s) {
        AiSessionDto d = AiSessionDto.of(s);
        d.setUserDisplay(employeeDisplayService.displayOf(d.getUserCode()));
        return d;
    }

    private AiSessionDto toSessionDetailDto(AiSession s) {
        SessionMessageCountSupport.reconcileSessionEntity(s, messageRepository);
        sessionRepository.save(s);
        AiSessionDto d = toSessionDto(s);
        attachStoredMessageCount(d, s);
        return d;
    }

    /**
     * 详情页窗内视图：消息数 / token 优先按 message 表「对话」口径计算，与下方对话 Tab 一致。
     * <p>仅当尚无入库消息时，才回退到 {@link AiSessionEventRepository#aggregateSessionWindow}（兼容未上报
     * recentMessages 的 provider）。
     */
    private AiSessionDto toSessionDetailDtoWithWindow(AiSession s, LocalDateTime from, LocalDateTime to) {
        SessionMessageCountSupport.reconcileSessionEntity(s, messageRepository);
        sessionRepository.save(s);
        AiSessionDto d = toSessionDto(s);
        attachStoredMessageCount(d, s);

        Integer stored = d.getStoredMessageCount();
        if (stored != null && stored > 0) {
            java.util.List<Object[]> rows = messageRepository.aggregateSessionWindowConversation(
                    s.getId(), from, to);
            long inputTokens = 0L;
            long outputTokens = 0L;
            int conversationCount = 0;
            if (rows != null && !rows.isEmpty()) {
                Object[] agg = rows.get(0);
                inputTokens = toLong(agg.length > 0 ? agg[0] : null);
                outputTokens = toLong(agg.length > 1 ? agg[1] : null);
                conversationCount = (int) toLong(agg.length > 2 ? agg[2] : null);
            }
            d.setWindowMessageCount(conversationCount);
            long messageTokens = inputTokens + outputTokens;
            if (messageTokens > 0) {
                d.setWindowTokens(messageTokens);
            } else if (sessionActivityWithinWindow(s, from, to)) {
                d.setWindowTokens(d.getInputTokens() + d.getOutputTokens());
            } else {
                applyEventWindowFallback(d, s.getId(), from, to);
            }
            return d;
        }

        applyEventWindowFallback(d, s.getId(), from, to);
        return d;
    }

    private void applyEventWindowFallback(AiSessionDto d, Long sessionId, LocalDateTime from, LocalDateTime to) {
        java.util.List<Object[]> rows = eventRepository.aggregateSessionWindow(sessionId, from, to);
        long tokens = 0L;
        int msgCount = 0;
        if (rows != null && !rows.isEmpty()) {
            Object[] agg = rows.get(0);
            tokens = toLong(agg.length > 0 ? agg[0] : null);
            msgCount = (int) toLong(agg.length > 1 ? agg[1] : null);
        }
        d.setWindowTokens(tokens);
        d.setWindowMessageCount(msgCount);
    }

    /** 会话 started_at 与 ended_at / last_activity 均落在 [from, to) 内。 */
    private static boolean sessionActivityWithinWindow(AiSession s, LocalDateTime from, LocalDateTime to) {
        if (s.getStartedAt() == null) {
            return false;
        }
        LocalDateTime end = s.getEndedAt() != null ? s.getEndedAt() : s.getLastActivity();
        if (end == null) {
            end = s.getStartedAt();
        }
        return !s.getStartedAt().isBefore(from) && end.isBefore(to);
    }

    private void attachStoredMessageCount(AiSessionDto d, AiSession s) {
        Integer stored = messageRepository.countByAiSessionId(s.getId());
        int storedN = stored == null ? 0 : stored;
        d.setStoredMessageCount(storedN);
        SessionMessageCountSupport.attachStoredRoleCounts(d, s, messageRepository);
        int reported = d.getReportedSnapshotMessages() == null ? 0 : d.getReportedSnapshotMessages();
        int effective = BackfillSnapshotSupport.reconcileForDisplay(storedN, reported);
        if (effective != reported) {
            d.setReportedSnapshotMessages(effective);
            s.setReportedSnapshotMessages(effective);
            sessionRepository.save(s);
        }
    }

    /**
     * 列表 DTO 转换 + 回填 windowMessageCount / windowTokens。
     * <p>这是用户看到的「窗内会话视图」的核心——一个跨期 session 命中筛选后只展示窗内消息数 / token，
     * 而不是被会话累积值误导。
     * <p>数据源走 ai_session_event（{@link AiSessionEventRepository#aggregateSessionWindow}），
     * 因为不是所有 provider 都上报 recentMessages，但 ingest 端对所有 provider 都会写
     * TOKEN_DELTA / MESSAGE_DELTA 事件。
     */
    private AiSessionDto toSessionDtoWithWindow(AiSession s, LocalDateTime from, LocalDateTime to) {
        java.util.List<Object[]> rows = eventRepository.aggregateSessionWindow(s.getId(), from, to);
        long tokens = 0L;
        int msgCount = 0;
        if (rows != null && !rows.isEmpty()) {
            Object[] agg = rows.get(0);
            tokens = toLong(agg.length > 0 ? agg[0] : null);
            msgCount = (int) toLong(agg.length > 1 ? agg[1] : null);
        }
        return toSessionDtoWithWindow(s, new long[]{tokens, msgCount});
    }

    private AiSessionDto toSessionDtoWithWindow(AiSession s, long[] windowAgg) {
        AiSessionDto d = toSessionDto(s);
        if (windowAgg != null && windowAgg.length >= 2) {
            d.setWindowTokens(windowAgg[0]);
            d.setWindowMessageCount((int) windowAgg[1]);
        } else {
            d.setWindowTokens(0L);
            d.setWindowMessageCount(0);
        }
        return d;
    }

    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * 解析 ?target_type=cursor,claude 形式的多 Provider 过滤。
     *  null  / 空串 / "all" → 不过滤（返回 null）
     *  其它   → 切分 + 去空 + 小写
     */
    private static List<String> parseTargetTypes(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        List<String> out = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toList());
        return out.isEmpty() ? null : out;
    }

    @GetMapping("/{id}")
    public R<AiSessionDto> detail(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AiSession s = loadActiveSession(id);
        if (from == null && to == null) {
            AiSessionDto d = toSessionDetailDto(s);
            attachAudit(d, s.getId());
            return R.ok(d);
        }
        LocalDateTime[] window = resolveWindow(from, to, null);
        AiSessionDto d = toSessionDetailDtoWithWindow(s, window[0], window[1]);
        attachAudit(d, s.getId());
        return R.ok(d);
    }

    private void attachAudit(AiSessionDto dto, Long aiSessionId) {
        sessionAuditRepository.findByAiSessionId(aiSessionId).ifPresent(
                a -> dto.setAudit(AiSessionAuditSummaryDto.of(a)));
    }

    /**
     * 会话审计完整内容（弹框懒加载）：含双 judge 原始难度、五维能力、判定说明等。
     */
    @GetMapping("/{id}/audit")
    public R<AiSessionAuditDetailDto> auditDetail(@PathVariable Long id) {
        loadActiveSession(id);
        return sessionAuditRepository.findByAiSessionId(id)
                .map(a -> R.ok(AiSessionAuditDetailDto.of(a)))
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                        "ai_session_audit not found for session: " + id));
    }

    /**
     * 按 id 拉会话，并校验 target_type 在当前白名单内；不在则视为不存在（404）。
     * <p>这样直接 URL 访问已禁用 agent 的旧 session 也会得到一致的"不存在"，
     * 与列表/详情/消息/事件全部口径对齐。
     */
    private AiSession loadActiveSession(Long id) {
        AiSession s = sessionRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                        "ai_session not found: " + id));
        if (!activeTargetTypesProvider.shouldInclude(s.getTargetType())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "ai_session not found: " + id);
        }
        return s;
    }

    /**
     * 会话消息：默认全量；带 from/to 时只返回 message_time 落在 [from 00:00, to+1day 00:00) 的消息。
     * <p>修复一个跨 4/1 → 5/7 的长会话在筛选 5/5-5/7 时把整段对话都展示出来的问题。
     */
    @GetMapping("/{id}/messages")
    public R<PageDto<AiSessionMessageDto>> messages(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String roles,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        loadActiveSession(id); // 触发白名单校验
        PageRequest pageable = PageRequest.of(page, Math.min(Math.max(size, 1), 500));
        List<String> roleFilters = parseMessageRoleFilters(role, roles);
        if (from == null && to == null) {
            if (roleFilters == null) {
                return R.ok(PageDto.of(
                        messageRepository.findByAiSessionIdOrderByConversationOrderDesc(id, pageable),
                        AiSessionMessageDto::of));
            }
            if (roleFilters.size() == 1) {
                return R.ok(PageDto.of(
                        messageRepository.findByAiSessionIdAndRoleOrderByConversationOrderDesc(
                                id, roleFilters.get(0), pageable),
                        AiSessionMessageDto::of));
            }
            return R.ok(PageDto.of(
                    messageRepository.findByAiSessionIdAndRoleInOrderByConversationOrderDesc(
                            id, roleFilters, pageable),
                    AiSessionMessageDto::of));
        }
        LocalDateTime[] window = resolveWindow(from, to, null);
        if (roleFilters == null) {
            return R.ok(PageDto.of(
                    messageRepository.findByAiSessionIdAndMessageTimeWindowOrderByConversationOrderDesc(
                            id, window[0], window[1], pageable),
                    AiSessionMessageDto::of));
        }
        if (roleFilters.size() == 1) {
            return R.ok(PageDto.of(
                    messageRepository.findByAiSessionIdAndRoleInWindowOrderByConversationOrderDesc(
                            id, roleFilters.get(0), window[0], window[1], pageable),
                    AiSessionMessageDto::of));
        }
        return R.ok(PageDto.of(
                messageRepository.findByAiSessionIdAndRoleInWindowOrderByConversationOrderDesc(
                        id, roleFilters, window[0], window[1], pageable),
                AiSessionMessageDto::of));
    }

    /**
     * 解析消息 role 过滤：{@code roles} 逗号分隔优先；{@code role=conversation} 展开为 user/assistant/subagent。
     */
    private static List<String> parseMessageRoleFilters(String role, String roles) {
        String raw = roles != null && !roles.isBlank() ? roles : role;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        raw = raw.trim();
        if ("conversation".equalsIgnoreCase(raw)) {
            return List.of("user", "assistant", "subagent");
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String r = part.trim().toLowerCase(Locale.ROOT);
            if (!r.isEmpty() && !out.contains(r)) {
                out.add(r);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 会话事件：默认全量；带 from/to 时只返回 event_time 落在窗口内的事件。
     */
    @GetMapping("/{id}/events")
    public R<PageDto<AiSessionEventDto>> events(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        loadActiveSession(id);
        PageRequest pageable = PageRequest.of(page, Math.min(Math.max(size, 1), 200));
        Page<com.am.server.domain.ai.AiSessionEvent> events;
        if (from == null && to == null) {
            events = eventRepository.findByAiSessionIdOrderByEventTimeDesc(id, pageable);
        } else {
            LocalDateTime[] window = resolveWindow(from, to, null);
            events = eventRepository
                    .findByAiSessionIdAndEventTimeGreaterThanEqualAndEventTimeLessThanOrderByEventTimeDesc(
                            id, window[0], window[1], pageable);
        }
        return R.ok(PageDto.of(events, e -> {
            AiSessionEventDto d = AiSessionEventDto.of(e);
            d.setUserDisplay(employeeDisplayService.displayOf(d.getUserCode()));
            return d;
        }));
    }

    /**
     * 消息附件 blob（gzip 解压后以原始字节返回，供前端 Image 等组件加载）。
     */
    @GetMapping("/{id}/messages/{messageId}/blobs/{blobId}")
    public ResponseEntity<byte[]> messageBlob(
            @PathVariable Long id,
            @PathVariable Long messageId,
            @PathVariable Long blobId) {
        loadActiveSession(id);
        AiSessionMessageBlobService.BlobPayload payload =
                messageBlobService.load(id, messageId, blobId);
        String mime = payload.mimeType() != null && !payload.mimeType().isBlank()
                ? payload.mimeType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mime)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(payload.bytes());
    }
}
