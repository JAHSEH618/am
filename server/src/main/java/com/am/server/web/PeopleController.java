package com.am.server.web;

import com.am.server.common.R;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.employee.Employee;
import com.am.server.domain.employee.EmployeeRepository;
import com.am.server.aggregator.DailySummaryAggregator;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.summary.DailySummary;
import com.am.server.domain.summary.DailySummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.PeopleDetailDto;
import com.am.server.web.dto.PeopleSummaryDto;
import com.am.server.web.dto.ProjectGitCommitRowDto;
import com.am.server.web.support.GitCommitRowMapper;
import com.am.server.web.support.SlashCommandStatSupport;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 员工数据（v2.1 Phase 2）
 *
 * <p><b>统计口径（产品语义）</b>：本页展示的 AI 协作类指标，只统计员工在<b>当前启用的 Agent</b>
 * （{@code monitor_target.enabled=1} → {@link ActiveTargetTypesProvider}）下、且
 * {@code ai_session.invalid_reason IS NULL} 的<b>有效会话</b>及其关联流水（message / event）。
 * 禁用 Agent 的数据仍会入库，但不进入本页汇总；无效会话同理。
 *
 * <p>实现上：日汇总由 {@link DailySummaryAggregator} 按上述白名单与 invalid 过滤写入 {@code daily_summary}；
 * 窗口内「提问次数 / 问答比」等对 {@code ai_session_message} 的查询亦使用同一套 activeTypes + 有效会话 JOIN。
 * 访问列表 / 详情前对窗口内每个自然日 {@link DailySummaryAggregator#ensureFresh}，避免摘要行滞后于该口径。
 *
 * <p>接口：
 * <ul>
 *   <li>GET /api/v1/people?from&to                       列表（窗口期默认最近 7 天）</li>
 *   <li>GET /api/v1/people/{userCode}?from&to            详情（含每日时间线 / Top N）</li>
 *   <li>GET /api/v1/people/{userCode}/git-commits?from&to       Git 提交明细（弹框）</li>
 *   <li>GET /api/v1/people/{userCode}/slash-commands?from&to  Slash Commands 明细（弹框）</li>
 * </ul>
 *
 * <p>列表人事范围仍为在岗 ACTIVE 全员（左连汇总）；无上述有效会话时各 AI 指标为 0，
 * 而不是从列表消失。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/people")
public class PeopleController {

    private static final int TOP_N = 5;

    private final DailySummaryRepository summaryRepository;
    private final AiSessionMessageRepository messageRepository;
    private final AiSessionEventRepository eventRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeDisplayService employeeDisplayService;
    private final DailySummaryAggregator dailySummaryAggregator;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final GitCommitRepository gitCommitRepository;
    private final ObjectMapper objectMapper;
    private final SlashCommandStatSupport slashCommandStatSupport;

    /** 员工数据访问触发的 today 聚合 TTL：60s 内不重复算同一天 */
    private static final Duration ENSURE_FRESH_TTL = Duration.ofSeconds(60);

    /** 同一窗口 list/detail 连续访问时跳过重复的过期扫描与 ensureFresh（与 ENSURE_FRESH_TTL 对齐）。 */
    private static final ConcurrentHashMap<String, Instant> ENSURE_WINDOW_RECENT = new ConcurrentHashMap<>();

    /**
     * 安装客户端弹框前置校验：
     * 员工填写工号 → 前端调一次此端点 → 命中且 ACTIVE 才让员工拿安装命令。
     * 不在职名单 / 离职状态会在这里被劝退，比安装完 aiwatchd 跑起来才报错友好得多。
     * <p>本端点是公开的（不需要 admin token），且只读 employee 表，无副作用。
     */
    @GetMapping("/check")
    public R<EmployeeCheckDto> check(@RequestParam("userCode") String userCode) {
        EmployeeCheckDto out = new EmployeeCheckDto();
        if (userCode == null || userCode.isBlank()) {
            out.setExists(false);
            return R.ok(out);
        }
        Employee emp = employeeRepository.findByUserCode(userCode.trim()).orElse(null);
        if (emp == null) {
            out.setExists(false);
            return R.ok(out);
        }
        out.setExists(true);
        out.setUserName(emp.getUserName());
        out.setDepartment(emp.getDepartment());
        out.setStatus(emp.getStatus());
        return R.ok(out);
    }

    @Data
    public static class EmployeeCheckDto {
        private boolean exists;
        private String userName;
        private String department;
        private String status;
    }

    @GetMapping
    public R<List<PeopleSummaryDto>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] window = resolveWindow(from, to);
        // v2.9：扫窗口里每一天的过期状态，同步 ensureFresh 收口（60s TTL 节流，稳态零成本）
        ensureWindowFreshIfStale(window);

        // 列表数据源 = "全员"，而非"daily_summary 里有行的人"。
        //
        // <p>历史 bug：旧版本只遍历 daily_summary，结果"完全没碰过 AI / 没绑 cursor 账号 /
        // 装了客户端但没产生过 ai_session_event"的员工（cursor 账号为 null 是常见表象之一）
        // 在画像里会整个消失——实际上这些"沉默对象"恰恰是管理者最关心的群体。
        //
        // <p>改法：以 employee(ACTIVE) 为基线，左连 daily_summary 窗口数据；
        // 没有 daily_summary 行的员工返回各项 AI 指标为 0。
        List<DailySummary> summaryRows = summaryRepository
                .findByWorkDateBetweenOrderByWorkDateDesc(window[0], window[1]);
        Map<String, List<DailySummary>> byUser = new LinkedHashMap<>();
        for (DailySummary row : summaryRows) {
            byUser.computeIfAbsent(row.getUserCode(), k -> new ArrayList<>()).add(row);
        }

        LocalDateTime msgFrom = window[0].atStartOfDay();
        LocalDateTime msgTo = windowElapsedEndExclusive(window[1]);
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        boolean applyAiDailySummary = !activeTypes.isEmpty();
        Map<String, long[]> userAssistantByCode = loadUserAssistantCountsBulk(msgFrom, msgTo, activeTypes);

        Map<String, Long> gitCommitsByUser = loadGitCommitCountsByUser(msgFrom, msgTo);
        Map<String, Long> slashCommandsByUser = applyAiDailySummary
                ? slashCommandStatSupport.sumCommandCountByUser(msgFrom, msgTo, activeTypes)
                : Map.of();

        List<Employee> employees = employeeRepository.findByStatusOrderByUserCodeAsc(Employee.STATUS_ACTIVE);
        List<PeopleSummaryDto> out = new ArrayList<>(employees.size());
        for (Employee emp : employees) {
            List<DailySummary> rows = byUser.getOrDefault(emp.getUserCode(), List.of());
            long[] ua = userAssistantByCode.getOrDefault(emp.getUserCode(), new long[]{0L, 0L});
            PeopleSummaryDto dto = buildSummary(
                    emp.getUserCode(), rows, window[0], window[1], ua[0], ua[1], applyAiDailySummary);
            dto.setToolCallCountTotal(toInt(slashCommandsByUser.getOrDefault(emp.getUserCode(), 0L)));
            dto.setGitCommitWindowCount(gitCommitsByUser.getOrDefault(emp.getUserCode(), 0L));
            out.add(dto);
        }

        // 一级排序：窗口内 token 总量降序，与「Token 总量」列口径一致；
        // 二级排序：user_code 升序，保证同 token 区段顺序稳定不抖动。
        out.sort(Comparator
                .comparingLong(PeopleSummaryDto::getTotalTokens).reversed()
                .thenComparing(PeopleSummaryDto::getUserCode));
        return R.ok(out);
    }

    @GetMapping("/{userCode}")
    public R<PeopleDetailDto> detail(
            @PathVariable String userCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return R.ok(buildDetail(userCode, from, to));
    }

    /**
     * 员工数据：时间窗内该员工在 {@code git_commit} 上的提交明细（可跨仓库），供表格链接弹框；
     * 口径与项目透视 Git 弹框一致：<code>[t0, t1)</code>、按提交时间倒序。
     */
    @GetMapping("/{userCode}/git-commits")
    public R<List<ProjectGitCommitRowDto>> gitCommitsForPerson(
            @PathVariable String userCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "200") Integer limit) {
        LocalDate[] window = resolveWindow(from, to);
        LocalDateTime t0 = window[0].atStartOfDay();
        LocalDateTime t1 = windowElapsedEndExclusive(window[1]);

        int cap = limit == null ? 200 : limit;
        if (cap < 1) {
            cap = 1;
        }
        if (cap > 500) {
            cap = 500;
        }

        List<GitCommit> rows = gitCommitRepository.findByUserCodeAndCommitWindowOrderByCommitTimeDesc(
                userCode, t0, t1, PageRequest.of(0, cap));
        List<ProjectGitCommitRowDto> out = new ArrayList<>(rows.size());
        for (GitCommit g : rows) {
            out.add(GitCommitRowMapper.toRow(g, employeeDisplayService, objectMapper));
        }
        return R.ok(out);
    }

    /**
     * 员工数据：时间窗内 Slash Commands 明细（命令 token → 次数），供列表数字点击弹框。
     */
    @GetMapping("/{userCode}/slash-commands")
    public R<List<PeopleDetailDto.NameValuePair>> slashCommandsForPerson(
            @PathVariable String userCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        LocalDate[] window = resolveWindow(from, to);
        LocalDateTime t0 = window[0].atStartOfDay();
        LocalDateTime t1 = windowElapsedEndExclusive(window[1]);
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            return R.ok(List.of());
        }
        int cap = limit == null ? 100 : limit;
        cap = Math.min(Math.max(cap, 1), 200);
        return R.ok(slashCommandStatSupport.topCommandTokensForUser(userCode, t0, t1, activeTypes, cap));
    }

    private PeopleDetailDto buildDetail(String userCode, LocalDate from, LocalDate to) {
        LocalDate[] window = resolveWindow(from, to);
        ensureWindowFreshIfStale(window);

        List<DailySummary> rows = summaryRepository
                .findByUserCodeAndWorkDateBetweenOrderByWorkDateDesc(userCode, window[0], window[1]);
        LocalDateTime t0 = window[0].atStartOfDay();
        LocalDateTime t1 = windowElapsedEndExclusive(window[1]);
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        boolean applyAiDailySummary = !activeTypes.isEmpty();
        long[] ua = userAssistantCountsForUser(userCode, t0, t1, activeTypes);
        PeopleSummaryDto summary = buildSummary(userCode, rows, window[0], window[1], ua[0], ua[1], applyAiDailySummary);
        if (applyAiDailySummary) {
            summary.setToolCallCountTotal(toInt(
                    slashCommandStatSupport.sumCommandCountForUser(userCode, t0, t1, activeTypes)));
        }
        summary.setGitCommitWindowCount(gitCommitRepository.countByUserCodeAndCommitWindow(userCode, t0, t1));

        Map<LocalDate, Long> slashByDay = applyAiDailySummary
                ? slashCommandStatSupport.sumCommandCountByDayForUser(userCode, t0, t1, activeTypes)
                : Map.of();

        // dailyTimeline 升序展示
        List<PeopleDetailDto.DailyPoint> timeline = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            DailySummary r = rows.get(i);
            if (applyAiDailySummary) {
                timeline.add(new PeopleDetailDto.DailyPoint(
                        r.getWorkDate().toString(),
                        nz(r.getAiSessionCount()),
                        nz(r.getAiActiveSeconds()),
                        nz(r.getAiActiveSecondsUnion()),
                        nz(r.getAiMessageCount()),
                        toInt(slashByDay.getOrDefault(r.getWorkDate(), 0L)),
                        nz(r.getAiRetryCount()),
                        nz(r.getTotalInputTokens()),
                        nz(r.getTotalOutputTokens()),
                        nz(r.getAiFirstResponseAvgMs()),
                        nz(r.getAiThinkingSeconds()),
                        r.getActiveModelTop()
                ));
            } else {
                timeline.add(new PeopleDetailDto.DailyPoint(
                        r.getWorkDate().toString(),
                        0, 0L, 0L, 0, 0, 0, 0L, 0L, 0, 0L, null
                ));
            }
        }

        // v2.4：Top 模型 / Top 项目 / Top 工具 全部基于 ai_session_event.event_time 切片，
        // 不再用 ai_session 累积字段（长会话被命中后历史 token 全算进窗口的旧 bug）。
        // event 表是全 provider 兼容的真相源（每次 reporter 上报都会写 token / message 增量事件）。

        // v2.10：所有 Top 聚合改用按 active target_type 过滤的版本，
        // 系统设置里禁用的 agent 不进入员工数据 Top。
        List<PeopleDetailDto.NameValuePair> topModels = new ArrayList<>();
        List<PeopleDetailDto.NameValuePair> topProjects = new ArrayList<>();
        List<PeopleDetailDto.NameValuePair> topTools = new ArrayList<>();
        if (!activeTypes.isEmpty()) {
            for (Object[] r : eventRepository.aggregateModelsForUserInWindowAndTargetTypeIn(userCode, t0, t1, activeTypes)) {
                String model = asString(r[0]);
                if (model == null) continue;
                topModels.add(new PeopleDetailDto.NameValuePair(model, toLong(r[1])));
                if (topModels.size() >= TOP_N) break;
            }

            for (Object[] r : eventRepository.aggregateProjectsForUserInWindowAndTargetTypeIn(userCode, t0, t1, activeTypes)) {
                String project = asString(r[0]);
                if (project == null) continue;
                topProjects.add(new PeopleDetailDto.NameValuePair(project, toLong(r[1])));
                if (topProjects.size() >= TOP_N) break;
            }

            topTools.addAll(slashCommandStatSupport.topCommandTokensForUser(
                    userCode, t0, t1, activeTypes, TOP_N));
        }

        return new PeopleDetailDto(
                summary,
                timeline,
                topModels,
                topTools,
                topProjects,
                computePeriodComparison(userCode, window[0], window[1])
        );
    }

    // ---------- 窗口期环比（嵌入员工详情顶部横条） ----------

    /**
     * 计算选定时间窗 vs 上一自然周的环比，基于 daily_summary 累加（Git 提交走 git_commit 表）。
     * <p>本期 = RangePicker [from, min(to, today)]；上周 = ISO 周一 ~ 周日（上一完整自然周，与本期窗口长度无关）。
     */
    private PeopleDetailDto.WeekOverWeek computePeriodComparison(String userCode, LocalDate from, LocalDate to) {
        LocalDate elapsedEnd = windowElapsedEnd(to);
        LocalDate today = LocalDate.now();
        LocalDate thisMon = today.with(DayOfWeek.MONDAY);
        LocalDate lastMon = thisMon.minusDays(7);
        LocalDate lastSun = thisMon.minusDays(1);

        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        List<DailySummary> currentRows = activeTypes.isEmpty()
                ? List.of()
                : summaryRepository.findByUserCodeAndWorkDateBetweenOrderByWorkDateDesc(userCode, from, elapsedEnd);
        List<DailySummary> prevRows = activeTypes.isEmpty()
                ? List.of()
                : summaryRepository.findByUserCodeAndWorkDateBetweenOrderByWorkDateDesc(userCode, lastMon, lastSun);

        long[] current = sumKeyMetrics(currentRows);
        long[] previous = sumKeyMetrics(prevRows);

        LocalDateTime curT0 = from.atStartOfDay();
        LocalDateTime curT1 = windowElapsedEndExclusive(to);
        LocalDateTime prevT0 = lastMon.atStartOfDay();
        LocalDateTime prevT1 = lastSun.plusDays(1).atStartOfDay();
        long curGit = activeTypes.isEmpty()
                ? 0L
                : gitCommitRepository.countByUserCodeAndCommitWindow(userCode, curT0, curT1);
        long prevGit = activeTypes.isEmpty()
                ? 0L
                : gitCommitRepository.countByUserCodeAndCommitWindow(userCode, prevT0, prevT1);

        PeopleDetailDto.WeekOverWeek wow = new PeopleDetailDto.WeekOverWeek();
        wow.setThisWeekFrom(from.toString());
        wow.setThisWeekTo(elapsedEnd.toString());
        wow.setLastWeekFrom(lastMon.toString());
        wow.setLastWeekTo(lastSun.toString());
        wow.setActiveSecondsUnion(metric(current[0], previous[0]));
        wow.setTokens(metric(current[1], previous[1]));
        wow.setSessions(metric(current[2], previous[2]));
        wow.setMessages(metric(current[3], previous[3]));
        wow.setRetries(metric(current[4], previous[4]));
        wow.setAiCommits(metric(current[5], previous[5]));
        wow.setGitCommits(metric(curGit, prevGit));
        return wow;
    }

    /**
     * 把一段窗口内的 daily_summary 累加成 6 元组（与 WeekOverWeek 字段一一对应）。
     * 顺序：[activeSecondsUnion, tokens, sessions, messages, retries, aiCommits]
     */
    private static long[] sumKeyMetrics(List<DailySummary> rows) {
        long activeUnion = 0, tokens = 0, sessions = 0, msg = 0, retries = 0, commits = 0;
        for (DailySummary r : rows) {
            activeUnion += nz(r.getAiActiveSecondsUnion());
            tokens += nz(r.getTotalInputTokens()) + nz(r.getTotalOutputTokens());
            sessions += nz(r.getAiSessionCount());
            msg += nz(r.getAiMessageCount());
            retries += nz(r.getAiRetryCount());
            commits += nz(r.getAiCommitCount());
        }
        return new long[]{activeUnion, tokens, sessions, msg, retries, commits};
    }

    private static PeopleDetailDto.Metric metric(long thisWeek, long lastWeek) {
        Integer pct = lastWeek == 0 ? null
                : (int) Math.round((thisWeek - lastWeek) * 100.0 / lastWeek);
        return new PeopleDetailDto.Metric(thisWeek, lastWeek, pct);
    }

    // ---------- 内部 ----------

    /**
     * @param applyAiDailySummary false 表示当前没有任何启用的 Agent（{@link ActiveTargetTypesProvider}
     *                            白名单为空），此时不从 daily_summary 读取 AI 汇总——展示全 0，
     *                            避免 aggregate 跳过写入而遗留旧快照。
     */
    private PeopleSummaryDto buildSummary(String userCode, List<DailySummary> rows,
                                          LocalDate windowFrom, LocalDate windowTo,
                                          long userMessageWindowCount, long assistantMessageWindowCount,
                                          boolean applyAiDailySummary) {
        PeopleSummaryDto d = new PeopleSummaryDto();
        d.setUserCode(userCode);
        d.setUserDisplay(employeeDisplayService.displayOf(userCode));
        d.setUserMessageCount(userMessageWindowCount);
        d.setAssistantMessageCount(assistantMessageWindowCount);
        d.setQaRatio(userMessageWindowCount > 0
                ? assistantMessageWindowCount / (double) userMessageWindowCount
                : null);

        if (!applyAiDailySummary) {
            d.setDaysWithData(0);
            d.setWindowElapsedDays(windowElapsedDays(windowFrom, windowTo));
            d.setAiActiveSecondsTotal(0);
            d.setAiActiveSecondsAvg(0);
            d.setAiActiveSecondsUnionAvg(0);
            d.setTotalInputTokens(0);
            d.setTotalOutputTokens(0);
            d.setTotalTokens(0);
            d.setAiMessageCountTotal(0);
            d.setAiSessionCountTotal(0);
            d.setToolCallCountTotal(0);
            d.setRetryCountTotal(0);
            d.setAiCommitCountTotal(0);
            d.setFirstResponseAvgMs(0);
            d.setTopModel(null);
            return d;
        }

        LocalDate elapsedEnd = windowElapsedEnd(windowTo);
        List<DailySummary> elapsedRows = rows.stream()
                .filter(r -> r.getWorkDate() != null
                        && !r.getWorkDate().isBefore(windowFrom)
                        && !r.getWorkDate().isAfter(elapsedEnd))
                .toList();
        int elapsedDays = windowElapsedDays(windowFrom, windowTo);

        d.setDaysWithData(elapsedRows.size());
        d.setWindowElapsedDays(elapsedDays);

        long activeTotal = 0L, activeUnionTotal = 0L;
        long inputTotal = 0L, outputTotal = 0L;
        int msg = 0, sessions = 0, retries = 0, commits = 0;
        long firstRespSum = 0L; int firstRespN = 0;

        Map<String, Long> tokensByModel = new HashMap<>();
        for (DailySummary r : elapsedRows) {
            activeTotal += nz(r.getAiActiveSeconds());
            activeUnionTotal += nz(r.getAiActiveSecondsUnion());
            inputTotal += nz(r.getTotalInputTokens());
            outputTotal += nz(r.getTotalOutputTokens());
            msg += nz(r.getAiMessageCount());
            sessions += nz(r.getAiSessionCount());
            retries += nz(r.getAiRetryCount());
            commits += nz(r.getAiCommitCount());
            int frMs = nz(r.getAiFirstResponseAvgMs());
            if (frMs > 0) { firstRespSum += frMs; firstRespN++; }
            String top = r.getActiveModelTop();
            if (top != null && !top.isBlank()) {
                tokensByModel.merge(top, nz(r.getTotalInputTokens()) + nz(r.getTotalOutputTokens()), Long::sum);
            }
        }

        d.setAiActiveSecondsTotal(activeTotal);
        d.setAiActiveSecondsAvg(elapsedDays <= 0 ? 0 : activeTotal / elapsedDays);
        d.setAiActiveSecondsUnionAvg(elapsedDays <= 0 ? 0 : activeUnionTotal / elapsedDays);
        d.setTotalInputTokens(inputTotal);
        d.setTotalOutputTokens(outputTotal);
        d.setTotalTokens(inputTotal + outputTotal);
        d.setAiMessageCountTotal(msg);
        d.setAiSessionCountTotal(sessions);
        d.setToolCallCountTotal(0);
        d.setRetryCountTotal(retries);
        d.setAiCommitCountTotal(commits);
        d.setFirstResponseAvgMs(firstRespN == 0 ? 0 : (int) (firstRespSum / firstRespN));

        if (!tokensByModel.isEmpty()) {
            d.setTopModel(tokensByModel.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).get().getKey());
        }

        return d;
    }

    private static int toInt(long v) {
        return (int) Math.min(v, Integer.MAX_VALUE);
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

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * 访问员工数据前，确保查询窗口里所有"已过期"的 daily_summary 都被同步重聚一次。
     *
     * <p><b>过期判定</b>：对窗口 [from, to] 内每一天，比较
     * <ul>
     *   <li>该天 ai_session_event 流的 MAX(event_time)（"事件真相源"的最新时刻）</li>
     *   <li>该天 daily_summary 全员行的 MAX(updated_time)（"快照"的最近一次刷新）</li>
     * </ul>
     * 若 max(event_time) &gt; max(updated_time)，或者 daily_summary 整天就没生成过——视为过期。
     *
     * <p><b>为什么要这样做</b>：客户端 backfill 历史数据时会分批 ingest，每批触发
     * {@link DailySummaryAggregator#enqueueRefresh} 异步 debounce 15s 重聚。用户在 backfill
     * 进行中访问画像，会看到"中间快照"——比如 5-08 还只聚到一半的 active_seconds。<br>
     * 改用 view-time 同步收口后：
     * <ul>
     *   <li>稳态：max(event) ≤ max(updated) → 触发 0 次 ensureFresh，零成本</li>
     *   <li>backfill / 实时 ingest 中：触发 N 次 ensureFresh，每个都带 60s TTL 节流，保护性强</li>
     *   <li>今天没事件但 hourlyJob 还没跑：兜底再 ensureFresh(today)</li>
     * </ul>
     *
     * <p>原 v2.x 实现只对 today 单点 ensureFresh，历史天的"中间快照"问题无法收口——
     * 这次改进是体感修复（用户截图过的 1h14m → 真值 2h6m 这个 case）。
     *
     * <p><b>补充（员工数据口径）</b>：窗口内<strong>每一个自然日</strong>都尝试一次 {@link DailySummaryAggregator#ensureFresh}，
     * 仅靠「当日存在启用 Agent 的事件」拉日历会漏掉整日只有禁用 Agent / 无效会话、却仍握着旧 daily_summary 的日期，
     * 导致列表上协作时长与问答次数脱节。
     */
    private void ensureWindowFreshIfStale(LocalDate[] window) {
        String debounceKey = window[0] + "|" + window[1];
        Instant now = Instant.now();
        Instant lastRun = ENSURE_WINDOW_RECENT.get(debounceKey);
        if (lastRun != null && Duration.between(lastRun, now).compareTo(ENSURE_FRESH_TTL) < 0) {
            return;
        }
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            return;
        }
        LocalDateTime eventFrom = window[0].atStartOfDay();
        LocalDateTime eventTo = window[1].plusDays(1).atStartOfDay();
        Map<LocalDate, LocalDateTime> maxEventByDay = toMaxTimeByWorkDate(
                eventRepository.findMaxEventTimePerDayAndTargetTypeIn(eventFrom, eventTo, activeTypes));
        Map<LocalDate, LocalDateTime> maxUpdatedByDay = toMaxTimeByWorkDate(
                summaryRepository.findMaxUpdatedTimePerDay(window[0], window[1]));

        for (LocalDate d = window[0]; !d.isAfter(window[1]); d = d.plusDays(1)) {
            LocalDateTime eventMax = maxEventByDay.get(d);
            LocalDateTime updatedMax = maxUpdatedByDay.get(d);
            if (eventMax == null && updatedMax == null) {
                continue;
            }
            if (eventMax != null && updatedMax != null && !eventMax.isAfter(updatedMax)) {
                continue;
            }
            dailySummaryAggregator.ensureFresh(d, ENSURE_FRESH_TTL);
        }
        ENSURE_WINDOW_RECENT.put(debounceKey, now);
    }

    private static Map<LocalDate, LocalDateTime> toMaxTimeByWorkDate(List<Object[]> rows) {
        Map<LocalDate, LocalDateTime> out = new HashMap<>();
        if (rows == null) {
            return out;
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            LocalDate day = toLocalDate(row[0]);
            LocalDateTime ts = toLocalDateTime(row[1]);
            if (day != null && ts != null) {
                out.put(day, ts);
            }
        }
        return out;
    }

    private static LocalDate toLocalDate(Object v) {
        if (v instanceof LocalDate ld) {
            return ld;
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        return null;
    }

    private static LocalDateTime toLocalDateTime(Object v) {
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    /**
     * 窗口 [from, to) 内各员工 user / assistant 消息条数（一次聚合查询）。
     * <p>v2.10：只统计 active target_type 的消息。activeTypes 为空意味着所有 agent
     * 都被关闭，直接返回空 map（问答比统一为 null）。
     */
    private Map<String, long[]> loadUserAssistantCountsBulk(LocalDateTime from, LocalDateTime to,
                                                            Collection<String> activeTypes) {
        Map<String, long[]> out = new HashMap<>();
        if (activeTypes.isEmpty()) {
            return out;
        }
        for (Object[] row : messageRepository
                .countGroupedByUserAndRoleLowerInWindowAndTargetTypeIn(from, to, activeTypes)) {
            if (row.length < 3 || row[0] == null) {
                continue;
            }
            String code = row[0].toString();
            String role = row[1] != null ? row[1].toString() : "";
            long cnt = ((Number) row[2]).longValue();
            long[] pair = out.computeIfAbsent(code, k -> new long[2]);
            if ("user".equals(role)) {
                pair[0] = cnt;
            } else if ("assistant".equals(role)) {
                pair[1] = cnt;
            }
        }
        return out;
    }

    /** {@code git_commit.commit_time ∈ [from,to)} 按员工聚合条数 */
    private Map<String, Long> loadGitCommitCountsByUser(LocalDateTime from, LocalDateTime to) {
        Map<String, Long> out = new HashMap<>();
        for (Object[] row : gitCommitRepository.countGroupedByUserCodeInCommitWindow(from, to)) {
            if (row.length < 2 || row[0] == null) {
                continue;
            }
            out.put(row[0].toString(), toLong(row[1]));
        }
        return out;
    }

    private long[] userAssistantCountsForUser(String userCode, LocalDateTime from, LocalDateTime to,
                                              Collection<String> activeTypes) {
        long userCnt = 0L;
        long assistantCnt = 0L;
        if (activeTypes.isEmpty()) {
            return new long[]{0L, 0L};
        }
        for (Object[] row : messageRepository
                .countGroupedByRoleLowerForUserInWindowAndTargetTypeIn(userCode, from, to, activeTypes)) {
            if (row.length < 2 || row[0] == null) {
                continue;
            }
            String role = row[0].toString();
            long cnt = ((Number) row[1]).longValue();
            if ("user".equals(role)) {
                userCnt = cnt;
            } else if ("assistant".equals(role)) {
                assistantCnt = cnt;
            }
        }
        return new long[]{userCnt, assistantCnt};
    }

    /**
     * 默认窗口口径：自然周（ISO 周一 ~ 周日），与分析报告页一致。
     * <p>调用方未传 from/to 时，按今天所在的自然周返回；前端 RangePicker 选了其他范围会带参数过来。
     */
    /** 窗口内已过的最后一天（含）：min(to, today)。 */
    private static LocalDate windowElapsedEnd(LocalDate to) {
        LocalDate today = LocalDate.now();
        return to.isAfter(today) ? today : to;
    }

    /** 窗口内已过自然日数（含首尾），例：5/18~5/24 且今天 5/19 → 2。 */
    private static int windowElapsedDays(LocalDate from, LocalDate to) {
        LocalDate end = windowElapsedEnd(to);
        if (from.isAfter(end)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(from, end) + 1;
    }

    /** [from 0:00, min(to,today)+1 0:00) —— 与 Git / message 窗口口径一致。 */
    private static LocalDateTime windowElapsedEndExclusive(LocalDate to) {
        return windowElapsedEnd(to).plusDays(1).atStartOfDay();
    }

    private static LocalDate[] resolveWindow(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            LocalDate today = LocalDate.now();
            LocalDate monday = today.with(DayOfWeek.MONDAY);
            LocalDate sunday = monday.plusDays(6);
            return new LocalDate[]{monday, sunday};
        }
        LocalDate today = LocalDate.now();
        LocalDate t = (to == null) ? today : to;
        LocalDate f = (from == null) ? t.with(DayOfWeek.MONDAY) : from;
        if (f.isAfter(t)) {
            LocalDate tmp = f; f = t; t = tmp;
        }
        return new LocalDate[]{f, t};
    }

    private static long nz(Long v) { return v == null ? 0 : v; }
    private static int nz(Integer v) { return v == null ? 0 : v; }
}
