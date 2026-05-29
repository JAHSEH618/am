package com.am.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.am.server.common.R;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.PeopleDetailDto;
import com.am.server.web.dto.ProjectDetailDto;
import com.am.server.web.dto.ProjectGitCommitRowDto;
import com.am.server.web.dto.ProjectSummaryDto;
import com.am.server.web.support.GitCommitRowMapper;
import com.am.server.web.support.ProjectsWindowSnapshotCache;
import com.am.server.web.support.SlashCommandStatSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目透视（v2.4 重构：窗内统计基于 event_time 切片 ai_session_event 流水）
 *
 * <p>接口：
 * <ul>
 *   <li>GET /api/v1/projects?from&to                      列表</li>
 *   <li>GET /api/v1/projects/{projectName}?from&to        详情（含贡献者矩阵 / 时间线）</li>
 * </ul>
 *
 * <p><b>窗内语义</b>：所有数字都基于 ai_session_event.event_time ∈ [from, to+1day) 切片。
 * 这意味着一个跨越 4 月 1 日 → 5 月 7 日的长会话，当用户筛选 5 月 5 日 ~ 5 月 7 日时，
 * 只会把这 3 天内产生的 token / 消息 / 工具调用算进去，而不是会话累积值。
 *
 * <p><b>为什么用 event 而不是 message</b>：ai_session_message 行只在 provider 上报了
 * recentMessages 时才写（Cursor / Claude Code 会，Hermes / OpenHarness / OpenClaw 不会），
 * 而 ai_session_event 上的 TOKEN_DELTA / MESSAGE_DELTA 是 ingest 端按累积差额无条件写入的，
 * 所以 event 流是 100% 完整的"窗内增量真相"。
 *
 * <p><b>历史问题</b>：v2.3 及更早版本直接对 ai_session 列做 SUM(s.inputTokens) 等聚合，
 * 长会话被命中后会把整个生命周期的消耗全部算进当前窗口，造成数据严重失真。
 *
 * <p><b>无效会话</b>：与员工数据页一致，{@code ai_session.invalid_reason IS NULL} 且
 * {@code target_type} 在当前启用白名单内的会话才进入本页数字；禁用 Agent 与无效会话均不入统计。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects")
public class ProjectsController {

    private static final int TOP_N = 5;

    private final AiSessionEventRepository eventRepository;
    private final AiSessionRepository aiSessionRepository;
    private final GitCommitRepository gitCommitRepository;
    private final EmployeeDisplayService employeeDisplayService;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final SlashCommandStatSupport slashCommandStatSupport;
    private final ObjectMapper objectMapper;
    private final ProjectsWindowSnapshotCache projectsWindowSnapshotCache;

    @GetMapping
    public R<List<ProjectSummaryDto>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTime[] window = resolveWindow(from, to);
        LocalDateTime t0 = window[0];
        LocalDateTime t1 = window[1];
        // v2.10：所有聚合按 active target_type 白名单过滤。activeTypes 为空 = 系统所有 agent 都被禁用，
        // 返回空列表（与员工数据 / 大盘行为一致）。
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            return R.ok(new ArrayList<>());
        }

        ProjectsWindowSnapshotCache.Snapshot snapshot =
                projectsWindowSnapshotCache.getOrLoad(t0, t1, activeTypes);
        return R.ok(snapshot.allSummaries());
    }

    @GetMapping("/{projectName}")
    public R<ProjectDetailDto> detail(
            @PathVariable String projectName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTime[] window = resolveWindow(from, to);
        LocalDateTime t0 = window[0];
        LocalDateTime t1 = window[1];
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        ProjectSummaryDto emptySummary = new ProjectSummaryDto();
        emptySummary.setProjectName(projectName);
        emptySummary.setTotalTokens(0L);
        emptySummary.setMessageCount(0);
        emptySummary.setUserMessageCount(0);
        emptySummary.setAssistantMessageCount(0);
        emptySummary.setInputTokens(0L);
        emptySummary.setOutputTokens(0L);
        emptySummary.setSessionCount(0);
        emptySummary.setUserCount(0);
        if (activeTypes.isEmpty()) {
            return R.ok(new ProjectDetailDto(emptySummary,
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
        }

        ProjectsWindowSnapshotCache.Snapshot snapshot =
                projectsWindowSnapshotCache.getOrLoad(t0, t1, activeTypes);
        ProjectSummaryDto summary = snapshot.summary(projectName).orElse(null);
        if (summary == null) {
            return R.ok(new ProjectDetailDto(emptySummary,
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
        }

        Map<String, long[]> contributorRollups = contributorRollupsByUser(
                aiSessionRepository.aggregateContributorRollupsByProjectAndTargetTypeIn(
                        projectName, t0, t1, activeTypes));

        // 贡献者矩阵：[userCode, totalTokens, messageCount, sessionCount]
        List<ProjectDetailDto.Contributor> contributors = new ArrayList<>();
        for (Object[] r : eventRepository.aggregateContributorsByProjectAndTargetTypeIn(projectName, t0, t1, activeTypes)) {
            String userCode = asString(r[0]);
            if (userCode == null) continue;
            long eventTokens = toLong(r[1]);
            int eventMessages = (int) toLong(r[2]);
            int sessions = (int) toLong(r[3]);
            long[] roll = contributorRollups.get(userCode);
            int userMsg;
            int assistantMsg;
            long inTok;
            long outTok;
            if (roll != null) {
                userMsg = (int) roll[0];
                assistantMsg = (int) roll[1];
                inTok = roll[2];
                outTok = roll[3];
            } else {
                userMsg = 0;
                assistantMsg = eventMessages;
                inTok = eventTokens;
                outTok = 0L;
            }
            contributors.add(new ProjectDetailDto.Contributor(
                    userCode,
                    employeeDisplayService.displayOf(userCode),
                    sessions,
                    eventTokens,
                    eventMessages,
                    userMsg,
                    assistantMsg,
                    inTok,
                    outTok));
        }

        // 每日时间线：[date, totalTokens, messageCount, sessionCount, userCount]
        // 关键改动：date 来自 message_time 而非 lastActivity，跨期会话每天的真实贡献分别记入对应日。
        List<ProjectDetailDto.DailyPoint> timeline = new ArrayList<>();
        for (Object[] r : eventRepository.aggregateProjectDailyTimelineAndTargetTypeIn(projectName, t0, t1, activeTypes)) {
            LocalDate d = asLocalDate(r[0]);
            if (d == null) continue;
            timeline.add(new ProjectDetailDto.DailyPoint(
                    d.toString(),
                    (int) toLong(r[3]),
                    toLong(r[1]),
                    (int) toLong(r[4])));
        }

        // Top 模型：[model, totalTokens, sessionCount]
        List<ProjectDetailDto.NameValuePair> topModels = new ArrayList<>();
        List<Object[]> modelRows = eventRepository.aggregateModelsByProjectAndTargetTypeIn(projectName, t0, t1, activeTypes);
        String topModel = null;
        for (int i = 0; i < modelRows.size() && i < TOP_N; i++) {
            Object[] r = modelRows.get(i);
            String model = asString(r[0]);
            if (model == null) continue;
            topModels.add(new ProjectDetailDto.NameValuePair(model, toLong(r[1])));
            if (i == 0) topModel = model;
        }
        summary.setTopModel(topModel);

        List<ProjectDetailDto.NameValuePair> topSlashCommands = new ArrayList<>();
        for (PeopleDetailDto.NameValuePair p : slashCommandStatSupport.topCommandTokensForProject(
                projectName, t0, t1, activeTypes, TOP_N)) {
            topSlashCommands.add(new ProjectDetailDto.NameValuePair(p.getName(), p.getValue()));
        }

        return R.ok(new ProjectDetailDto(summary, contributors, timeline, topSlashCommands, topModels));
    }

    /**
     * 项目透视：时间窗内与该项目 {@code repo_url} 对齐的 Git 提交明细（供弹框列表，最多 500 条）。
     */
    @GetMapping("/{projectName}/git-commits")
    public R<List<ProjectGitCommitRowDto>> gitCommitsForProject(
            @PathVariable String projectName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "200") Integer limit) {
        LocalDateTime[] window = resolveWindow(from, to);
        LocalDateTime t0 = window[0];
        LocalDateTime t1 = window[1];
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            return R.ok(new ArrayList<>());
        }

        String repoUrl = projectsWindowSnapshotCache.getOrLoad(t0, t1, activeTypes)
                .meta(projectName)
                .map(ProjectsWindowSnapshotCache.ProjectMeta::repoUrl)
                .orElse(null);
        if (repoUrl == null || repoUrl.isBlank()) {
            return R.ok(new ArrayList<>());
        }

        int cap = limit == null ? 200 : limit;
        if (cap < 1) {
            cap = 1;
        }
        if (cap > 500) {
            cap = 500;
        }

        List<GitCommit> rows = gitCommitRepository.findByRepoUrlAndCommitWindowOrderByCommitTimeDesc(
                repoUrl, t0, t1, PageRequest.of(0, cap));
        List<ProjectGitCommitRowDto> out = new ArrayList<>(rows.size());
        for (GitCommit g : rows) {
            out.add(GitCommitRowMapper.toRow(g, employeeDisplayService, objectMapper));
        }
        return R.ok(out);
    }

    // ---------- 内部 ----------

    private static Map<String, long[]> contributorRollupsByUser(List<Object[]> rows) {
        Map<String, long[]> out = new HashMap<>(Math.max(8, rows.size() * 2));
        for (Object[] r : rows) {
            String u = asString(r[0]);
            if (u == null) continue;
            out.put(u, new long[]{toLong(r[1]), toLong(r[2]), toLong(r[3]), toLong(r[4])});
        }
        return out;
    }

    /**
     * 默认窗口口径：自然周（ISO 周一 ~ 周日），与员工数据 / 分析报告页一致。
     * <p>调用方未传 from/to 时按今天所在的自然周返回；前端 RangePicker 选了其他范围会带参数过来。
     * <p>v2.10 起从"近 N 天"统一切到"自然周"，让首页、项目透视、员工数据、报告页彼此可直接对照。
     */
    private static LocalDateTime[] resolveWindow(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            LocalDate today = LocalDate.now();
            LocalDate monday = today.with(DayOfWeek.MONDAY);
            LocalDate sunday = monday.plusDays(6);
            return new LocalDateTime[]{monday.atStartOfDay(), sunday.plusDays(1).atStartOfDay()};
        }
        LocalDate today = LocalDate.now();
        LocalDate t = (to == null) ? today : to;
        LocalDate f = (from == null) ? t.with(DayOfWeek.MONDAY) : from;
        if (f.isAfter(t)) {
            LocalDate tmp = f; f = t; t = tmp;
        }
        return new LocalDateTime[]{f.atStartOfDay(), t.plusDays(1).atStartOfDay()};
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

    /** Hibernate 在 MySQL 下 FUNCTION('DATE', ...) 通常返回 java.sql.Date，这里兜底兼容 LocalDate / String */
    private static LocalDate asLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date sd) return sd.toLocalDate();
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        try {
            return LocalDate.parse(v.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
