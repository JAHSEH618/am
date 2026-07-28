package com.am.server.web;

import com.am.server.aggregator.DailySummaryAggregator;
import com.am.server.common.R;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.ai.AiSessionStatus;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.session.WorkSession;
import com.am.server.domain.session.WorkSessionRepository;
import com.am.server.domain.summary.DailySummaryRepository;
import com.am.server.config.AgentProperties;
import com.am.server.insight.config.InsightProperties;
import com.am.server.service.AiPenetrationService;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.service.InstallManifestService;
import com.am.server.service.PenetrationWindow;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.AiPenetrationDto;
import com.am.server.web.dto.DashboardInsightAuditFastDto;
import com.am.server.web.dto.DashboardInsightAuditSlowDto;
import com.am.server.web.dto.DashboardOverviewDto;
import com.am.server.web.dto.OnlineAgentDto;
import com.am.server.web.dto.TokenTrendDto;
import com.am.server.web.dto.TopItemDto;
import com.am.server.web.sse.SseHub;
import com.am.server.web.support.TokenTrendSupport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 大盘聚合接口（首页 + 实时大屏）
 *
 * <p>AI 会话相关计数与窗内聚合（今日 token/消息/项目数、Top 榜单等）与员工数据同口径：
 * 仅 {@code ai_session.invalid_reason IS NULL} 的有效会话，且 {@code target_type} 为当前启用 Agent。
 *
 * 全部为只读 GET 接口，无 HMAC，路径前缀 /api/v1/dashboard
 *
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    /** 在线窗口见 {@link AgentProperties#getOnlineWindowSeconds()}（默认 5min ≈ 2.5× 2min 上报）。 */
    // v2.8 起删除 ACTIVE_WINDOW_SECONDS / STATUS_STALE_THRESHOLD_SECONDS：
    //
    // 之前 dashboard 用 last_activity 时间窗（180s/300s）作为"活跃"判定，与 AI 会话列表
    // "status != idle" 的口径不一致——同一时刻"列表 2 个非 idle，大盘 0 个"反复出现。
    //
    // v2.8 起统一为单一真相源："DB 里 status 字段就是当下状态"：
    //   - 列表 / 大盘 / online 表都按 status 计数，不再做时间窗二次过滤
    //   - 真正的"卡僵 session 自愈"由 AiSessionStaleCloser 定时任务兜底
    //     （超过 5 分钟无活动直接 UPDATE status=idle，让 DB 自身回到正确）

    private final AgentDeviceRepository deviceRepository;
    private final WorkSessionRepository workSessionRepository;
    private final AiSessionRepository aiSessionRepository;
    private final AiSessionEventRepository eventRepository;
    private final GitCommitRepository gitCommitRepository;
    private final EmployeeDisplayService employeeDisplayService;
    private final InstallManifestService installManifestService;
    private final SseHub sseHub;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final InsightProperties insightProperties;
    private final AgentProperties agentProperties;
    private final AiPenetrationService aiPenetrationService;
    private final DailySummaryRepository dailySummaryRepository;
    private final DailySummaryAggregator dailySummaryAggregator;

    /** token-trend 进入前对今日的兜底刷新节流，与 PeopleController.ENSURE_FRESH_TTL 同值。 */
    private static final Duration ENSURE_FRESH_TTL = Duration.ofSeconds(60);

    @GetMapping("/overview")
    public R<DashboardOverviewDto> overview() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime onlineCutoff = now.minusSeconds(agentProperties.getOnlineWindowSeconds());
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        DashboardOverviewDto out = new DashboardOverviewDto();
        // v2.10：所有 ai_session* 维度的聚合 / 计数都按 active target_type 白名单过滤，
        // 禁用的 agent 不进入"今日 token / 活跃会话 / 工具调用"等指标。
        // 设备 / work_session / git_commit 不受白名单影响（与 agent 类型无关，沿用原口径）。
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();

        // 在线 agent：device 心跳在 online-window 内；离线 = 同 status 全量 − 在线
        long activeDeviceTotal = deviceRepository.countByStatus(AgentDevice.STATUS_ACTIVE);
        long onlineCount = deviceRepository.countByStatusAndLastSeenTimeAfter(
                AgentDevice.STATUS_ACTIVE, onlineCutoff);
        out.setOnlineAgents((int) onlineCount);
        out.setOfflineAgents((int) Math.max(0, activeDeviceTotal - onlineCount));

        // 活跃 AI 会话：纯 status 口径，与 AI 会话列表"非空闲"严格一致。
        // 真正的"卡僵 session"自愈由 AiSessionStaleCloser 定时任务兜底（5min 无活动 → status=idle）。
        if (activeTypes.isEmpty()) {
            out.setActiveAgents(0);
            out.setActiveAiSessions(0);
        } else {
            out.setActiveAgents((int) aiSessionRepository.countDistinctNonIdleAgentIdByTargetTypeIn(activeTypes));
            out.setActiveAiSessions((int) aiSessionRepository.countNonIdleByTargetTypeIn(activeTypes));
        }

        // 今日 work_session 在线 / 活跃秒数
        Object[] secs = workSessionRepository.aggregateTodaySeconds(todayStart);
        out.setTodayOnlineSeconds(coalesceLong(secs, 0));
        out.setTodayActiveSeconds(coalesceLong(secs, 1));

        // 今日 token / message / 项目 / 用户（v2.4 起改为按 event_time 切片 ai_session_event 流水，
        // 修复"长会话命中今日 last_activity 后把历史 token 全算进今日"的数据放大问题）。
        // event 表是全 provider 兼容的真相源；窗内 in/out 来自 input_tokens_delta / output_tokens_delta。
        // 总览 6 项 + 工具调用数合并成一次扫描（原先是对同一窗口的两条独立查询）。
        java.util.List<Object[]> totalsRows = activeTypes.isEmpty()
                ? java.util.List.of()
                : eventRepository.aggregateOverviewWindow(todayStart, now, activeTypes);
        Object[] totals = (totalsRows == null || totalsRows.isEmpty()) ? null : totalsRows.get(0);
        long todayInput = toLong(totals != null && totals.length > 0 ? totals[0] : null);
        long todayOutput = toLong(totals != null && totals.length > 1 ? totals[1] : null);
        long messages = toLong(totals != null && totals.length > 2 ? totals[2] : null);
        long todaySessions = toLong(totals != null && totals.length > 3 ? totals[3] : null);
        long todayUsers = toLong(totals != null && totals.length > 4 ? totals[4] : null);
        long todayProjects = toLong(totals != null && totals.length > 5 ? totals[5] : null);
        long todayToolCalls = toLong(totals != null && totals.length > 6 ? totals[6] : null);

        out.setTodayInputTokens(todayInput);
        out.setTodayOutputTokens(todayOutput);
        out.setTodayMessages(messages);
        out.setTodayProjects((int) todayProjects);
        out.setTodayUsers((int) todayUsers);
        // 今日累计开过的 AI 会话数 —— 单独字段，不再覆盖 activeAiSessions（曾经的 bug：
        // Math.max 把"今日早些时候跑过、现在已空闲"的会话计入"活跃"，导致仪表盘虚高）
        out.setTodayAiSessions((int) todaySessions);
        // AI 渗透率（北极星）：首屏直出默认 30 天口径；前端切换窗口走 /dashboard/ai-penetration。
        out.setAiPenetrationPercent(aiPenetrationService.compute(PenetrationWindow.D30));

        out.setTodayToolCalls(todayToolCalls);

        out.setLatestAgentVersion(installManifestService.readPublishedClientVersion().orElse(null));

        return R.ok(out);
    }

    /**
     * AI 渗透率（北极星）· 可选时间窗：today / 7d / 30d（默认 30d）。
     *
     * <p>口径见 {@link AiPenetrationService}：AI协助行 / 全部非merge行 × 100；无数据返回 -1。
     */
    @GetMapping("/ai-penetration")
    public R<AiPenetrationDto> aiPenetration(@RequestParam(defaultValue = "30d") String window) {
        PenetrationWindow w = PenetrationWindow.parse(window);
        return R.ok(new AiPenetrationDto(aiPenetrationService.compute(w), window));
    }

    /**
     * 全公司 Token 走势：近 N 天（默认 30，钳 [1,90]）input/output 按日汇总。
     *
     * <p>口径 = daily_summary（与员工数据页同源可对账），只含 input+output；
     * 逐日补零，date 升序。历史日由 00:05/每小时聚合任务定型，仅今日一点在动 ——
     * 进入前对今日 ensureFresh（60s TTL 节流，稳态零成本），与员工数据页同语义。
     */
    @GetMapping("/token-trend")
    public R<TokenTrendDto> tokenTrend(@RequestParam(defaultValue = "30") int days) {
        int d = TokenTrendSupport.clampDays(days);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(d - 1L);
        dailySummaryAggregator.ensureFresh(today, ENSURE_FRESH_TTL);
        List<Object[]> rows = dailySummaryRepository.sumTokensGroupedByWorkDate(from, today);
        return R.ok(new TokenTrendDto(TokenTrendSupport.fillDaily(from, today, rows)));
    }

    /**
     * 洞察审计进度 · 快指标（建议 10s 轮询）：已稳定审计数 + 队列剩余。
     *
     * <p>口径与员工数据一致：有效会话且 target_type 为当前启用的 Agent。
     */
    @GetMapping("/insight-audit/fast")
    public R<DashboardInsightAuditFastDto> insightAuditFast() {
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes == null || activeTypes.isEmpty()) {
            return R.ok(new DashboardInsightAuditFastDto(0L, 0L));
        }
        List<String> types = new ArrayList<>(activeTypes);
        int th = insightProperties.getReauditMessageThreshold();
        long pending = aiSessionRepository.countPendingInsightAuditSessions(types, th);
        long stable = aiSessionRepository.countStableInsightAuditedSessions(types, th);
        return R.ok(new DashboardInsightAuditFastDto(stable, pending));
    }

    /**
     * 洞察审计进度 · 慢指标（建议 60s 轮询）：有效会话总量、未稳定审计会话数。
     */
    @GetMapping("/insight-audit/slow")
    public R<DashboardInsightAuditSlowDto> insightAuditSlow() {
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes == null || activeTypes.isEmpty()) {
            return R.ok(new DashboardInsightAuditSlowDto(0L, 0L));
        }
        List<String> types = new ArrayList<>(activeTypes);
        int th = insightProperties.getReauditMessageThreshold();
        long total = aiSessionRepository.countValidSessionsForInsightTypes(types);
        long stable = aiSessionRepository.countStableInsightAuditedSessions(types, th);
        long unaudited = Math.max(0L, total - stable);
        return R.ok(new DashboardInsightAuditSlowDto(total, unaudited));
    }

    /**
     * 当前 Agent 表：先是在线设备（每行 = agent_device × target_type），再拼接离线设备（每设备一行）。
     *
     * <p>v2.3 起在线段每行 = (agent_device, target_type) 二元组 —— 同一台机器并发跑多种 AI 工具
     * （比如 Cursor + Claude Code）会展开成多行；前端用 rowSpan 把"员工 / 主机 / Git / Cursor /
     * 在线时长 / 最近上报 / 离线时长 / 版本"等"按机器不变"的列合并显示，"运行中 Agent / 当前状态 /
     * 工具 / 模型 / 项目"分行展开。
     *
     * <p>没有任何活跃 ai_session 的 agent_device 仍会输出 1 行（targetType=null），
     * 这样在线段不会因为员工只是开机但还没跑 AI 而漏掉这台机器。
     *
     * <p>离线段：ACTIVE 台账内心跳超出在线窗口的设备，deviceOnline=false，offlineSeconds 为距最后心跳（或注册）的秒数。
     */
    @GetMapping("/online")
    public R<List<OnlineAgentDto>> online() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime onlineCutoff = now.minusSeconds(agentProperties.getOnlineWindowSeconds());

        List<AgentDevice> onlineDevices = deviceRepository
                .findByStatusAndLastSeenTimeAfter(AgentDevice.STATUS_ACTIVE, onlineCutoff);

        // v2.8 口径：online 表展开"在线 device 上当下 status != idle 的会话"——
        //   - device 必须心跳在 onlineCutoff 内（见 aiwatch.agent.online-window-seconds）
        //   - 但会话维度只看 status，不再要求 last_activity 在某窗口内
        // 同一 (agent, target) 多条 active 会话取 lastActivity 最新一条，避免一台机器多 cursor 窗口同时跑时炸表。
        // v2.10：会话集合按 active target_type 过滤；禁用的 agent 即使有非空闲会话也不再出现在实时活跃表里。
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        Map<String, Map<String, AiSession>> sessionsByAgentByTarget = new HashMap<>();
        if (!activeTypes.isEmpty()) {
            for (Object[] row : aiSessionRepository.findNonIdleSessionSummariesByTargetTypeIn(activeTypes)) {
                AiSession s = sessionFromSummaryRow(row);
                if (s.getAgentId() == null || s.getTargetType() == null) {
                    continue;
                }
                sessionsByAgentByTarget
                        .computeIfAbsent(s.getAgentId(), k -> new HashMap<>())
                        .merge(s.getTargetType(), s, (oldS, newS) ->
                                oldS.getLastActivity() != null
                                        && (newS.getLastActivity() == null
                                        || oldS.getLastActivity().isAfter(newS.getLastActivity()))
                                        ? oldS : newS);
            }
        }

        List<String> allAgentIds = new ArrayList<>(onlineDevices.size() + 64);
        for (AgentDevice d : onlineDevices) {
            allAgentIds.add(d.getAgentId());
        }
        Map<String, WorkSession> openWorkSessionByAgent = loadLatestOpenWorkSessions(allAgentIds);

        List<OnlineAgentDto> rows = new ArrayList<>(onlineDevices.size());
        for (AgentDevice d : onlineDevices) {
            // 公共字段（按 agent_device 不变）
            String userCode = d.getUserCode();
            String userDisplay = employeeDisplayService.displayOf(userCode);
            long sinceSeen = d.getLastSeenTime() == null ? -1
                    : Duration.between(d.getLastSeenTime(), now).getSeconds();

            WorkSession ws = openWorkSessionByAgent.get(d.getAgentId());
            long duration = ws == null ? 0L : nullToZero(ws.getDurationSeconds());
            long active = ws == null ? 0L : nullToZero(ws.getActiveSeconds());
            String wsProject = ws == null ? null : ws.getProjectName();
            String wsRepo = ws == null ? null : ws.getRepoUrl();
            String wsBranch = ws == null ? null : ws.getBranchName();

            // 按 target_type 展开：每个 active 会话一行；没有任何 active 会话时输出 1 行 targetType=null
            Map<String, AiSession> targetMap = sessionsByAgentByTarget.getOrDefault(d.getAgentId(), Map.of());
            if (targetMap.isEmpty()) {
                rows.add(buildRow(d, userDisplay, sinceSeen, duration, active,
                        wsProject, wsRepo, wsBranch,
                        null, null, true, 0L));
            } else {
                // target_type 升序，让同一台机器的子行顺序稳定
                targetMap.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> rows.add(buildRow(d, userDisplay, sinceSeen, duration, active,
                                wsProject, wsRepo, wsBranch,
                                e.getKey(), e.getValue(), true, 0L)));
            }
        }

        // 按 (在线时长 desc, agent_id) 排，保持同一 agent 的所有子行连续，便于前端 rowSpan 合并
        rows.sort((a, b) -> {
            int cmp = Long.compare(b.getDurationSeconds(), a.getDurationSeconds());
            if (cmp != 0) return cmp;
            return a.getAgentId().compareTo(b.getAgentId());
        });

        List<AgentDevice> offlineDevices = deviceRepository.findByStatusAndOfflineBefore(
                AgentDevice.STATUS_ACTIVE, onlineCutoff);
        List<String> offlineAgentIds = new ArrayList<>(offlineDevices.size());
        for (AgentDevice d : offlineDevices) {
            offlineAgentIds.add(d.getAgentId());
        }
        Map<String, WorkSession> offlineOpenWorkSessionByAgent = loadLatestOpenWorkSessions(offlineAgentIds);

        List<OnlineAgentDto> offlineRows = new ArrayList<>(offlineDevices.size());
        for (AgentDevice d : offlineDevices) {
            String userDisplay = employeeDisplayService.displayOf(d.getUserCode());
            long sinceSeen = d.getLastSeenTime() == null ? -1
                    : Duration.between(d.getLastSeenTime(), now).getSeconds();
            LocalDateTime anchor = d.getLastSeenTime() != null ? d.getLastSeenTime() : d.getCreatedTime();
            long offlineSec = anchor == null ? 0L : Duration.between(anchor, now).getSeconds();

            WorkSession ws = offlineOpenWorkSessionByAgent.get(d.getAgentId());
            long duration = ws == null ? 0L : nullToZero(ws.getDurationSeconds());
            long activeSec = ws == null ? 0L : nullToZero(ws.getActiveSeconds());
            String wsProject = ws == null ? null : ws.getProjectName();
            String wsRepo = ws == null ? null : ws.getRepoUrl();
            String wsBranch = ws == null ? null : ws.getBranchName();

            offlineRows.add(buildRow(d, userDisplay, sinceSeen, duration, activeSec,
                    wsProject, wsRepo, wsBranch,
                    null, null, false, offlineSec));
        }
        offlineRows.sort((a, b) -> {
            int cmp = Long.compare(b.getOfflineSeconds(), a.getOfflineSeconds());
            if (cmp != 0) return cmp;
            return a.getAgentId().compareTo(b.getAgentId());
        });
        rows.addAll(offlineRows);
        return R.ok(rows);
    }

    private static AiSession sessionFromSummaryRow(Object[] row) {
        AiSession s = new AiSession();
        if (row == null || row.length < 2) {
            return s;
        }
        s.setAgentId(row[0] == null ? null : row[0].toString());
        s.setTargetType(row.length > 1 && row[1] != null ? row[1].toString() : null);
        if (row.length > 2 && row[2] != null) {
            if (row[2] instanceof java.time.LocalDateTime ldt) {
                s.setLastActivity(ldt);
            } else if (row[2] instanceof java.sql.Timestamp ts) {
                s.setLastActivity(ts.toLocalDateTime());
            }
        }
        if (row.length > 3 && row[3] != null) {
            s.setStatus(row[3].toString());
        }
        if (row.length > 4 && row[4] != null) {
            s.setCurrentTool(row[4].toString());
        }
        if (row.length > 5 && row[5] != null) {
            s.setModel(row[5].toString());
        }
        if (row.length > 6 && row[6] != null) {
            s.setProjectName(row[6].toString());
        }
        return s;
    }

    private Map<String, WorkSession> loadLatestOpenWorkSessions(List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = agentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<String, WorkSession> out = new HashMap<>(distinct.size());
        for (WorkSession w : workSessionRepository.findLatestOpenByAgentIdIn(distinct, WorkSession.STATUS_OPEN)) {
            if (w.getAgentId() != null) {
                out.putIfAbsent(w.getAgentId(), w);
            }
        }
        return out;
    }

    private OnlineAgentDto buildRow(
            AgentDevice d, String userDisplay, long sinceSeen,
            long duration, long active,
            String wsProject, String wsRepo, String wsBranch,
            String targetType, AiSession s,
            boolean deviceOnline, long offlineSeconds) {
        OnlineAgentDto row = new OnlineAgentDto();
        row.setAgentId(d.getAgentId());
        row.setUserCode(d.getUserCode());
        row.setUserDisplay(userDisplay);
        row.setHostname(d.getHostname());
        row.setOsType(d.getOsType());
        row.setAgentVersion(d.getAgentVersion());
        row.setLocalIp(d.getLocalIp());
        row.setGitUserName(d.getGitUserName());
        row.setGitUserEmail(d.getGitUserEmail());
        row.setCursorEmail(d.getCursorEmail());
        row.setCursorMembershipType(d.getCursorMembershipType());
        row.setCursorSubscriptionStatus(d.getCursorSubscriptionStatus());
        row.setCursorSignupType(d.getCursorSignupType());
        row.setLastSeenTime(d.getLastSeenTime());
        row.setSinceSeen(sinceSeen);
        row.setDurationSeconds(duration);
        row.setActiveSeconds(active);
        // 项目优先取 work_session（更准），ai_session 作为兜底
        row.setProjectName(wsProject != null ? wsProject : (s != null ? s.getProjectName() : null));
        row.setRepoUrl(wsRepo);
        row.setBranchName(wsBranch);

        row.setTargetType(targetType);
        if (s != null) {
            // staleSinceSeconds 仅用于前端展示"会话上次有动静多久了"，不参与 active 判定。
            long staleSec = s.getLastActivity() == null
                    ? Long.MAX_VALUE
                    : Duration.between(s.getLastActivity(), LocalDateTime.now()).getSeconds();
            row.setStaleSinceSeconds(staleSec);

            // v2.8：DB 里 status 字段是真相；不再做 last_activity 时间窗的二次降级。
            // 真正的"卡僵 session"由 AiSessionStaleCloser 定时任务统一改 status=idle。
            String rawStatus = s.getStatus();
            row.setCurrentStatus(rawStatus);
            row.setCurrentTool(s.getCurrentTool());
            row.setCurrentModel(s.getModel());
            row.setActive(!AiSessionStatus.IDLE.code().equalsIgnoreCase(rawStatus));
        }
        row.setDeviceOnline(deviceOnline);
        row.setOfflineSeconds(offlineSeconds);
        return row;
    }

    @GetMapping("/top-projects")
    public R<List<TopItemDto>> topProjects(@RequestParam(defaultValue = "10") int limit) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            return R.ok(new ArrayList<>());
        }
        // 主聚合：按 event_time 切片 [projectName, totalTokens, messageCount, sessionCount, userCount]
        // 决定排序与"今日"口径，不会因长会话把历史 token 误算进当日。
        List<TopItemDto> base = toTopItemsFromEventAgg(
                eventRepository.aggregateByProjectInWindowAndTargetTypeIn(todayStart, now, activeTypes), limit, null);
        // 补充 user/assistant 拆分（来自 ai_session 表的会话级累计），让前端"消息"列能展示 "X/Y"，
        // 与 AI 会话列表完全同口径。aggregate 命中范围与主聚合保持一致（窗内有活动的会话）。
        Map<String, long[]> roleSplit = toRoleSplitMap(
                aiSessionRepository.aggregateUserAssistantMessagesByProjectAndTargetTypeIn(todayStart, now, activeTypes));
        applyRoleSplit(base, roleSplit);
        Map<String, long[]> ioSplit = toIoTokenSplitMap(
                aiSessionRepository.aggregateInputOutputTokensByProjectAndTargetTypeIn(todayStart, now, activeTypes));
        applyIoTokenSplit(base, ioSplit);
        return R.ok(base);
    }

    @GetMapping("/top-employees")
    public R<List<TopItemDto>> topEmployees(@RequestParam(defaultValue = "10") int limit) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            return R.ok(new ArrayList<>());
        }
        // 员工维度同上：[userCode, totalTokens, messageCount, sessionCount, projectCount]
        // displayLabel = "姓名|工号"（无姓名退化为工号），统一通过 EmployeeDisplayService 拿
        List<TopItemDto> base = toTopItemsFromEventAgg(
                eventRepository.aggregateByUserInWindowAndTargetTypeIn(todayStart, now, activeTypes), limit,
                employeeDisplayService::displayOf);
        Map<String, long[]> roleSplit = toRoleSplitMap(
                aiSessionRepository.aggregateUserAssistantMessagesByUserAndTargetTypeIn(todayStart, now, activeTypes));
        applyRoleSplit(base, roleSplit);
        Map<String, long[]> ioSplit = toIoTokenSplitMap(
                aiSessionRepository.aggregateInputOutputTokensByUserAndTargetTypeIn(todayStart, now, activeTypes));
        applyIoTokenSplit(base, ioSplit);
        return R.ok(base);
    }

    /**
     * 把 [key, userMsg, assistantMsg] 三元组列表转成 key → [user, assistant] map，
     * 用于按 key 把 user/assistant 拆分回填进主聚合结果。
     */
    private static Map<String, long[]> toRoleSplitMap(List<Object[]> rows) {
        Map<String, long[]> out = new HashMap<>(rows.size() * 2);
        for (Object[] r : rows) {
            if (r == null || r.length < 3 || r[0] == null) continue;
            out.put(r[0].toString(), new long[]{ toLong(r[1]), toLong(r[2]) });
        }
        return out;
    }

    /** 按 TopItemDto.key 把 user/assistant 拆分回填到 base list（缺失时回退为 0/messageCount，让 UI 至少展示总数）。 */
    private static void applyRoleSplit(List<TopItemDto> base, Map<String, long[]> roleSplit) {
        for (TopItemDto it : base) {
            long[] split = roleSplit.get(it.getKey());
            if (split != null) {
                it.setUserMessageCount(split[0]);
                it.setAssistantMessageCount(split[1]);
            } else {
                // 主聚合命中了某 key 但 ai_session 那边查不到（极端情况）——降级到 0/totalMessages，
                // 避免前端因 user+assistant=0 渲染成 "0/0" 让人误以为没消息。
                it.setUserMessageCount(0);
                it.setAssistantMessageCount(it.getMessageCount());
            }
        }
    }

    private static Map<String, long[]> toIoTokenSplitMap(List<Object[]> rows) {
        Map<String, long[]> out = new HashMap<>(rows.size() * 2);
        for (Object[] r : rows) {
            if (r == null || r.length < 3 || r[0] == null) continue;
            out.put(r[0].toString(), new long[]{toLong(r[1]), toLong(r[2])});
        }
        return out;
    }

    private static void applyIoTokenSplit(List<TopItemDto> base, Map<String, long[]> ioSplit) {
        for (TopItemDto it : base) {
            long[] split = ioSplit.get(it.getKey());
            if (split != null) {
                it.setInputTokenCount(split[0]);
                it.setOutputTokenCount(split[1]);
            } else {
                it.setInputTokenCount(0L);
                it.setOutputTokenCount(0L);
            }
        }
    }

    @GetMapping(path = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream() {
        log.debug("sse subscribe; current size before={}", sseHub.size());
        return sseHub.subscribe();
    }

    /**
     * 把 eventRepository 窗内聚合结果转成 TopItemDto。
     * <p>SQL 输出顺序为 [key, totalTokens, messageCount, sessionCount, extraCount]
     * （extraCount = userCount on project agg / projectCount on user agg）。
     * 而 TopItemDto 字段顺序是 (key, label, sessionCount, messageCount, tokenCount, extraCount)，
     * 所以要在这里重排序。
     */
    private static List<TopItemDto> toTopItemsFromEventAgg(
            List<Object[]> rows, int limit,
            java.util.function.Function<String, String> displayMapper) {
        List<TopItemDto> out = new ArrayList<>(Math.min(rows.size(), limit));
        for (int i = 0; i < rows.size() && i < limit; i++) {
            Object[] r = rows.get(i);
            String key = r[0] == null ? "(unknown)" : r[0].toString();
            String label = displayMapper != null ? displayMapper.apply(key) : key;
            long tokens = toLong(r[1]);
            long messages = toLong(r[2]);
            long sessions = toLong(r[3]);
            long extra = r.length > 4 ? toLong(r[4]) : 0L;
            out.add(new TopItemDto(key, label, sessions, messages, tokens, extra));
        }
        return out;
    }

    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long coalesceLong(Object[] arr, int idx) {
        if (arr == null || idx >= arr.length || arr[idx] == null) return 0;
        if (arr[idx] instanceof Number n) return n.longValue();
        return 0;
    }

    private static int nullToZero(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nullToZero(Long v) {
        return v == null ? 0 : v;
    }
}
