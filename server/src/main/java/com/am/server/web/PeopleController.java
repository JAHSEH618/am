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
 * 访问列表 / 详情前对窗口内每个自然日判一次过期，最近几天同步 {@link DailySummaryAggregator#ensureFresh}、
 * 更早的转后台补，避免摘要行滞后于该口径。
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
    private final com.am.server.insight.domain.AnalysisReportRepository analysisReportRepository;
    private final com.am.server.insight.domain.AnalysisReportUserRepository analysisReportUserRepository;

    /** 员工数据访问触发的 today 聚合 TTL：60s 内不重复算同一天 */
    private static final Duration ENSURE_FRESH_TTL = Duration.ofSeconds(60);

    /** 同一窗口 list/detail 连续访问时跳过重复的过期扫描与 ensureFresh（与 ENSURE_FRESH_TTL 对齐）。 */
    private static final ConcurrentHashMap<String, Instant> ENSURE_WINDOW_RECENT = new ConcurrentHashMap<>();

    /** {@link #ENSURE_WINDOW_RECENT} 的键数上限：超过就顺手清掉过了 TTL 的旧窗口，防止静态表随选择过的时间窗只增不减。 */
    private static final int ENSURE_WINDOW_KEYS_MAX = 256;

    /**
     * view-time 同步重聚的日期上限：只对最近的 N 个过期日阻塞请求，更早的交给
     * {@link DailySummaryAggregator#enqueueRefresh(Collection)} 在后台 debounce 补。
     * <p>没有上限时，"近30天"窗口撞上一次历史 backfill 就要在请求线程里连算 30 天
     * （每天 = 当日活跃人数 × 4 条聚合查询），必然打穿前端 15s 超时。
     */
    private static final int MAX_SYNC_ENSURE_DAYS = 2;

    /** 窗内消息统计的零值：{@code [userMsgCount, assistantMsgCount, slashCount]}。 */
    private static final long[] EMPTY_MESSAGE_STATS = new long[]{0L, 0L, 0L};

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
        Map<String, long[]> msgStatsByCode = loadWindowMessageStatsBulk(msgFrom, msgTo, activeTypes);

        Map<String, Long> gitCommitsByUser = loadGitCommitCountsByUser(msgFrom, msgTo);

        List<Employee> employees = employeeRepository.findByStatusOrderByUserCodeAsc(Employee.STATUS_ACTIVE);
        List<PeopleSummaryDto> out = new ArrayList<>(employees.size());
        for (Employee emp : employees) {
            List<DailySummary> rows = byUser.getOrDefault(emp.getUserCode(), List.of());
            long[] ms = msgStatsByCode.getOrDefault(emp.getUserCode(), EMPTY_MESSAGE_STATS);
            PeopleSummaryDto dto = buildSummary(
                    emp.getUserCode(), rows, window[0], window[1], ms[0], ms[1], applyAiDailySummary);
            dto.setToolCallCountTotal(toInt(ms[2]));
            dto.setGitCommitWindowCount(gitCommitsByUser.getOrDefault(emp.getUserCode(), 0L));
            out.add(dto);
        }

        attachGrades(out);

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
        // 问答比、Slash 合计、Slash 按天三个数出自同一批 message 行：按天聚合一次查完，
        // 合计由各天相加得到（窗口两端都是自然日边界，逐日相加与整窗聚合等价）。
        Map<LocalDate, long[]> msgStatsByDay = loadWindowMessageStatsByDay(userCode, t0, t1, activeTypes);
        long userMsgTotal = 0L;
        long assistantMsgTotal = 0L;
        long slashTotal = 0L;
        for (long[] v : msgStatsByDay.values()) {
            userMsgTotal += v[0];
            assistantMsgTotal += v[1];
            slashTotal += v[2];
        }
        PeopleSummaryDto summary = buildSummary(
                userCode, rows, window[0], window[1], userMsgTotal, assistantMsgTotal, applyAiDailySummary);
        if (applyAiDailySummary) {
            summary.setToolCallCountTotal(toInt(slashTotal));
        }
        long curGitCommits = gitCommitRepository.countByUserCodeAndCommitWindow(userCode, t0, t1);
        summary.setGitCommitWindowCount(curGitCommits);
        attachGrades(List.of(summary));

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
                        toInt(msgStatsByDay.getOrDefault(r.getWorkDate(), EMPTY_MESSAGE_STATS)[2]),
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
                computePeriodComparison(userCode, window[0], window[1], rows, curGitCommits, applyAiDailySummary)
        );
    }

    // ---------- 窗口期环比（嵌入员工详情顶部横条） ----------

    /**
     * 计算选定时间窗 vs 上一自然周的环比，基于 daily_summary 累加（Git 提交走 git_commit 表）。
     * <p>本期 = RangePicker [from, min(to, today)]；上周 = ISO 周一 ~ 周日（上一完整自然周，与本期窗口长度无关）。
     */
    private PeopleDetailDto.WeekOverWeek computePeriodComparison(
            String userCode, LocalDate from, LocalDate to,
            List<DailySummary> windowRows, long curGitCommits, boolean applyAiDailySummary) {
        LocalDate elapsedEnd = windowElapsedEnd(to);
        LocalDate today = LocalDate.now();
        LocalDate thisMon = today.with(DayOfWeek.MONDAY);
        LocalDate lastMon = thisMon.minusDays(7);
        LocalDate lastSun = thisMon.minusDays(1);

        // 本期行 = 调用方已经查过的 [from, to] 窗口行裁到已过日期，不再为同一段重发一次查询。
        List<DailySummary> currentRows = applyAiDailySummary
                ? windowRows.stream()
                        .filter(r -> r.getWorkDate() != null
                                && !r.getWorkDate().isBefore(from)
                                && !r.getWorkDate().isAfter(elapsedEnd))
                        .toList()
                : List.of();
        List<DailySummary> prevRows = applyAiDailySummary
                ? summaryRepository.findByUserCodeAndWorkDateBetweenOrderByWorkDateDesc(userCode, lastMon, lastSun)
                : List.of();

        long[] current = sumKeyMetrics(currentRows);
        long[] previous = sumKeyMetrics(prevRows);

        LocalDateTime prevT0 = lastMon.atStartOfDay();
        LocalDateTime prevT1 = lastSun.plusDays(1).atStartOfDay();
        long curGit = applyAiDailySummary ? curGitCommits : 0L;
        long prevGit = applyAiDailySummary
                ? gitCommitRepository.countByUserCodeAndCommitWindow(userCode, prevT0, prevT1)
                : 0L;

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

    /** 批量回填最近 completed 报告的等级徽章；无报告时全部保持 null。 */
    private void attachGrades(List<PeopleSummaryDto> dtos) {
        if (dtos.isEmpty()) {
            return;
        }
        var reportOpt = analysisReportRepository
                .findFirstByStatusOrderByWindowToDescIdDesc("completed");
        if (reportOpt.isEmpty()) {
            return;
        }
        var report = reportOpt.get();
        String window = report.getWindowFrom() + " ~ " + report.getWindowTo();
        var codes = dtos.stream().map(PeopleSummaryDto::getUserCode).toList();
        Map<String, com.am.server.insight.domain.AnalysisReportUser> byCode = new HashMap<>();
        for (var u : analysisReportUserRepository.findByReportIdAndUserCodeIn(report.getId(), codes)) {
            byCode.put(u.getUserCode(), u);
        }
        for (PeopleSummaryDto d : dtos) {
            var u = byCode.get(d.getUserCode());
            if (u == null || u.getCompositeGrade() == null) {
                continue;
            }
            d.setCompositeGrade(u.getCompositeGrade());
            d.setCompositeScore(u.getCompositeScore());
            d.setCompositeConfidence(u.getCompositeConfidence());
            d.setGradeWindow(window);
        }
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
     * <p><b>过期判定</b>：对窗口 [from, to] 内每一天，看该天 daily_summary 全员行的
     * MAX(updated_time)（"快照"的最近一次刷新）之后，事件真相源里是否还有新的有效事件
     * ——{@link #isStale} 用两次索引探针回答，判定与原先「按天 MAX(event_time) vs MAX(updated_time)」等价。
     * <p>v1.3.3 之前是一条 {@code GROUP BY DATE(event_time)} 一次性算出整窗每天的 MAX(event_time)：
     * 那等于为了回答一个是/否问题把整个窗口的事件流全扫一遍，"近30天"预设下这一步本身就要好几秒。
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
     * <p><b>补充（员工数据口径）</b>：窗口内<strong>每一个自然日</strong>都参与过期判定，
     * 仅靠「当日存在启用 Agent 的事件」拉日历会漏掉整日只有禁用 Agent / 无效会话、却仍握着旧 daily_summary 的日期，
     * 导致列表上协作时长与问答次数脱节。
     * <p>但<strong>同步</strong>重聚只给最近的 {@link #MAX_SYNC_ENSURE_DAYS} 天（稳态下过期的本来也只有今天），
     * 更早的过期日改走 {@link DailySummaryAggregator#enqueueRefresh(Collection)} 在后台补，
     * 免得一次历史 backfill 把请求线程按在 30 天的重算上。
     */
    private void ensureWindowFreshIfStale(LocalDate[] window) {
        if (!claimEnsureWindow(window)) {
            return;
        }
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            return;
        }
        Map<LocalDate, LocalDateTime> maxUpdatedByDay = toMaxTimeByWorkDate(
                summaryRepository.findMaxUpdatedTimePerDay(window[0], window[1]));

        // 倒序扫：离今天最近的日期先拿到同步名额——那正是用户盯着看的几天。
        List<LocalDate> stale = new ArrayList<>();
        for (LocalDate d = window[1]; !d.isBefore(window[0]); d = d.minusDays(1)) {
            if (isStale(d, maxUpdatedByDay.get(d), activeTypes)) {
                stale.add(d);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        for (int i = 0; i < Math.min(stale.size(), MAX_SYNC_ENSURE_DAYS); i++) {
            dailySummaryAggregator.ensureFresh(stale.get(i), ENSURE_FRESH_TTL);
        }
        if (stale.size() > MAX_SYNC_ENSURE_DAYS) {
            dailySummaryAggregator.enqueueRefresh(stale.subList(MAX_SYNC_ENSURE_DAYS, stale.size()));
        }
    }

    /**
     * 某个自然日的 daily_summary 快照是否已过期。判定与原「按天 MAX(event_time) vs MAX(updated_time)」
     * 完全等价，只是换成两次索引探针，代价不再随窗口长度线性增长：
     * <ul>
     *   <li>快照之后还有有效事件 → 过期（backfill / 实时上报没追上）</li>
     *   <li>有快照但该日事件流已空 → 过期（口径收窄后旧快照要被清成 0，见 v2.11）</li>
     *   <li>既没有快照也没有事件 → 无事可做</li>
     * </ul>
     */
    private boolean isStale(LocalDate day, LocalDateTime updatedMax, Collection<String> activeTypes) {
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
        // after 取 dayStart 之前一天 = "不设新旧门槛"，配合 event_time >= dayStart 就是整日范围
        LocalDateTime anyEventFloor = dayStart.minusDays(1);
        LocalDateTime after = updatedMax != null ? updatedMax : anyEventFloor;
        if (!eventRepository.probeActiveEventAfter(dayStart, dayEnd, after, activeTypes).isEmpty()) {
            return true;
        }
        if (updatedMax == null) {
            // after 就是 anyEventFloor，上面那次探针已经回答了"当日没有任何有效事件"
            return false;
        }
        return eventRepository.probeActiveEventAfter(dayStart, dayEnd, anyEventFloor, activeTypes).isEmpty();
    }

    /**
     * 抢占一个窗口的过期扫描名额：同一窗口 {@link #ENSURE_FRESH_TTL} 内只允许一次。
     * <p>用 {@code compute} 原子占位，而不是旧版的「先读、扫完再写」：员工数据的列表与详情
     * 几乎总是同一拍并发发出，标记落得太晚会让两条请求各扫一遍窗口、再各自去抢 ensureFresh 的日期锁。
     */
    private static boolean claimEnsureWindow(LocalDate[] window) {
        Instant now = Instant.now();
        if (ENSURE_WINDOW_RECENT.size() > ENSURE_WINDOW_KEYS_MAX) {
            ENSURE_WINDOW_RECENT.entrySet().removeIf(
                    e -> Duration.between(e.getValue(), now).compareTo(ENSURE_FRESH_TTL) >= 0);
        }
        boolean[] claimed = {false};
        ENSURE_WINDOW_RECENT.compute(window[0] + "|" + window[1], (k, last) -> {
            if (last != null && Duration.between(last, now).compareTo(ENSURE_FRESH_TTL) < 0) {
                return last;
            }
            claimed[0] = true;
            return now;
        });
        return claimed[0];
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
     * 窗口 [from, to) 内各员工的 user / assistant 消息条数与 Slash 调用数（一次聚合查询）。
     * <p>v2.10：只统计 active target_type 的消息。activeTypes 为空意味着所有 agent
     * 都被关闭，直接返回空 map（问答比统一为 null、Slash 计 0）。
     * <p>值为 {@code [userMsgCount, assistantMsgCount, slashCount]}。
     */
    private Map<String, long[]> loadWindowMessageStatsBulk(LocalDateTime from, LocalDateTime to,
                                                           Collection<String> activeTypes) {
        Map<String, long[]> out = new HashMap<>();
        if (activeTypes.isEmpty()) {
            return out;
        }
        for (Object[] row : messageRepository
                .aggregatePeopleMessageStatsByUserInWindow(from, to, activeTypes)) {
            if (row.length < 4 || row[0] == null) {
                continue;
            }
            out.put(row[0].toString(), new long[]{toLong(row[1]), toLong(row[2]), toLong(row[3])});
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

    /**
     * 单员工窗口 [from, to) 内按自然日的 user / assistant 消息条数与 Slash 调用数（一次聚合查询）。
     * <p>值为 {@code [userMsgCount, assistantMsgCount, slashCount]}；调用方相加即得窗口合计。
     */
    private Map<LocalDate, long[]> loadWindowMessageStatsByDay(String userCode,
                                                               LocalDateTime from, LocalDateTime to,
                                                               Collection<String> activeTypes) {
        Map<LocalDate, long[]> out = new HashMap<>();
        if (activeTypes.isEmpty()) {
            return out;
        }
        for (Object[] row : messageRepository
                .aggregatePeopleMessageStatsByDayForUserInWindow(userCode, from, to, activeTypes)) {
            if (row.length < 4) {
                continue;
            }
            LocalDate day = toLocalDate(row[0]);
            if (day == null) {
                continue;
            }
            out.put(day, new long[]{toLong(row[1]), toLong(row[2]), toLong(row[3])});
        }
        return out;
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
