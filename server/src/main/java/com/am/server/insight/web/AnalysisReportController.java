package com.am.server.insight.web;

import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import com.am.server.insight.domain.AnalysisReport;
import com.am.server.insight.domain.AnalysisReportRepository;
import com.am.server.insight.domain.AnalysisReportUser;
import com.am.server.insight.domain.AnalysisReportUserRepository;
import com.am.server.insight.domain.ReportStatus;
import com.am.server.insight.orchestrator.AnalysisReportOrchestrator;
import com.am.server.insight.web.dto.AnalysisReportDto;
import com.am.server.insight.web.dto.AnalysisReportListItemDto;
import com.am.server.insight.web.dto.AnalysisReportProgressDto;
import com.am.server.insight.web.dto.AnalysisReportUserDto;
import com.am.server.service.EmployeeDisplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 分析报告 API（v3.0）—— 全部走 /api/v1/admin/**，由 AdminTokenInterceptor 鉴权。
 *
 * <p>对应 docs/design/employee-insight-from-ai-sessions-v1.0.md §7.1。
 * 流水线会话源与员工数据一致：不含 {@code invalid_reason} 非空的无效会话。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/analysis")
public class AnalysisReportController {

    private static final DateTimeFormatter DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final AnalysisReportOrchestrator orchestrator;
    private final AnalysisReportRepository reportRepository;
    private final AnalysisReportUserRepository userRepository;
    private final EmployeeDisplayService employeeDisplayService;

    /**
     * 触发生成：同窗口已有 completed/running/pending 报告 → 直接返回；
     * force=true 或命中 failed → 重置并触发。返回 report id 与当前状态。
     */
    @PostMapping("/generate")
    public R<AnalysisReportProgressDto> generate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean force) {
        AnalysisReport r = orchestrator.findOrCreate(from, to, force);
        return R.ok(toProgress(r));
    }

    /** 查找现有报告（不触发生成）。前端打开页面时探测用。 */
    @GetMapping
    public R<AnalysisReportProgressDto> find(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return R.ok(reportRepository.findByWindowFromAndWindowTo(from, to)
                .map(this::toProgress)
                .orElse(null));
    }

    /** 历史报告列表（最近 50 条，倒序）。 */
    @GetMapping("/history")
    public R<List<AnalysisReportListItemDto>> history() {
        List<AnalysisReportListItemDto> out = new ArrayList<>();
        for (AnalysisReport r : reportRepository.findTop50ByOrderByCreatedTimeDesc()) {
            out.add(new AnalysisReportListItemDto(
                    r.getId(),
                    r.getWindowFrom().toString(),
                    r.getWindowTo().toString(),
                    r.getStatus(),
                    r.getAuditedCount(),
                    r.getTotalCount(),
                    r.getActiveUserCount(),
                    r.getTotalSessionCount(),
                    fmt(r.getCreatedTime()),
                    fmt(r.getCompletedTime())));
        }
        return R.ok(out);
    }

    /** 详情：团队摘要 + 员工列表。 */
    @GetMapping("/{reportId}")
    public R<AnalysisReportDto> detail(@PathVariable Long reportId) {
        AnalysisReport r = requireReport(reportId);
        AnalysisReportDto dto = new AnalysisReportDto();
        dto.setId(r.getId());
        dto.setWindowFrom(r.getWindowFrom().toString());
        dto.setWindowTo(r.getWindowTo().toString());
        dto.setStatus(r.getStatus());
        dto.setAuditedCount(r.getAuditedCount());
        dto.setTotalCount(r.getTotalCount());
        dto.setProgressPercent(computeProgress(r));
        dto.setErrorText(r.getErrorText());
        dto.setJudgeDisagreementRatio(r.getJudgeDisagreementRatio());
        dto.setCreatedTime(fmt(r.getCreatedTime()));
        dto.setCompletedTime(fmt(r.getCompletedTime()));
        dto.setActiveUserCount(r.getActiveUserCount());
        dto.setTotalSessionCount(r.getTotalSessionCount());
        dto.setTotalActiveHours(r.getTotalActiveHours());
        dto.setTotalAiCommit(r.getTotalAiCommit());
        dto.setTeamDifficultyDist(nullOrJson(r.getTeamDifficultyDistJson()));
        dto.setTeamModeDist(nullOrJson(r.getTeamModeDistJson()));
        dto.setTeamPercentiles(nullOrJson(r.getTeamPercentilesJson()));
        dto.setTeamCapabilityPercentiles(nullOrJson(r.getTeamCapabilityPercentilesJson()));
        dto.setWatchlistSummary(nullOrJson(r.getWatchlistSummaryJson()));
        dto.setTeamToolBreakdown(nullOrJson(r.getTeamToolBreakdownJson()));

        List<AnalysisReportUserDto> users = new ArrayList<>();
        for (AnalysisReportUser u : userRepository.findByReportIdOrderByCompositePercentileDesc(reportId)) {
            users.add(toUserDto(u));
        }
        dto.setUsers(users);
        return R.ok(dto);
    }

    /** 单员工详情：与 detail 列表里的对象相同，但单独走一个 endpoint 方便缓存。 */
    @GetMapping("/{reportId}/users/{userCode}")
    public R<AnalysisReportUserDto> user(@PathVariable Long reportId,
                                         @PathVariable String userCode) {
        Optional<AnalysisReportUser> u = userRepository.findByReportIdAndUserCode(reportId, userCode);
        if (u.isEmpty()) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "no user found in report: " + reportId + " / " + userCode);
        }
        return R.ok(toUserDto(u.get()));
    }

    /** 进度查询：报告生成中前端 1-3s 轮询一次。 */
    @GetMapping("/{reportId}/progress")
    public R<AnalysisReportProgressDto> progress(@PathVariable Long reportId) {
        return R.ok(toProgress(requireReport(reportId)));
    }

    /** 删除历史报告（含 analysis_report_user，DB 级 ON DELETE CASCADE）。 */
    @DeleteMapping("/{reportId}")
    @Transactional
    public R<Void> delete(@PathVariable Long reportId) {
        AnalysisReport r = requireReport(reportId);
        if (ReportStatus.PENDING.equals(r.getStatus()) || ReportStatus.RUNNING.equals(r.getStatus())) {
            throw new BizException(ErrorCode.OPERATION_NOT_ALLOWED, "报告生成中，无法删除");
        }
        userRepository.deleteByReportId(reportId);
        reportRepository.deleteById(reportId);
        return R.ok();
    }

    // ------------------------------------------------------------------

    private AnalysisReport requireReport(Long id) {
        return reportRepository.findById(id).orElseThrow(() ->
                new BizException(ErrorCode.RESOURCE_NOT_FOUND, "report not found: " + id));
    }

    private AnalysisReportProgressDto toProgress(AnalysisReport r) {
        AnalysisReportProgressDto p = new AnalysisReportProgressDto();
        p.setId(r.getId());
        p.setStatus(r.getStatus());
        p.setAuditedCount(r.getAuditedCount());
        p.setTotalCount(r.getTotalCount());
        p.setProgressPercent(computeProgress(r));
        p.setErrorText(r.getErrorText());
        return p;
    }

    private int computeProgress(AnalysisReport r) {
        if ("completed".equals(r.getStatus())) return 100;
        if ("failed".equals(r.getStatus())) return 100;
        Integer total = r.getTotalCount();
        Integer done = r.getAuditedCount();
        if (total == null || total == 0 || done == null) return 0;
        return Math.min(99, (int) Math.floor(done * 100.0 / total));
    }

    private AnalysisReportUserDto toUserDto(AnalysisReportUser u) {
        AnalysisReportUserDto d = new AnalysisReportUserDto();
        d.setUserCode(u.getUserCode());
        d.setUserDisplay(employeeDisplayService.displayOf(u.getUserCode()));
        d.setSessionCount(u.getSessionCount());
        d.setAiActiveHours(u.getAiActiveHours());
        d.setTotalTokens(u.getTotalTokens());
        d.setAiCommitCount(u.getAiCommitCount());
        d.setAiLinesAdded(u.getAiLinesAdded());
        d.setDifficultyDist(nullOrJson(u.getDifficultyDistJson()));
        d.setAvgDifficulty(u.getAvgDifficulty());
        d.setHighDifficultyRatio(u.getHighDifficultyRatio());
        d.setCapProblemDecomposition(u.getCapProblemDecomposition());
        d.setCapContextManagement(u.getCapContextManagement());
        d.setCapDebuggingSkill(u.getCapDebuggingSkill());
        d.setCapToolOrchestration(u.getCapToolOrchestration());
        d.setCapSelfCorrection(u.getCapSelfCorrection());
        d.setModeDist(nullOrJson(u.getModeDistJson()));
        d.setAiCommitsPerActiveHour(u.getAiCommitsPerActiveHour());
        d.setAiLinesPer1kToken(u.getAiLinesPer1kToken());
        d.setCommitRevertRate(u.getCommitRevertRate());
        d.setHighDifficultyCommitRatio(u.getHighDifficultyCommitRatio());
        d.setCompositeScore(u.getCompositeScore());
        d.setCompositePercentile(u.getCompositePercentile());
        d.setCompositeBucket(bucketOf(u.getCompositePercentile()));
        d.setWatchlistFlags(nullOrJson(u.getWatchlistFlagsJson()));
        d.setHighlightSessionIds(nullOrJson(u.getHighlightSessionIdsJson()));
        d.setHighlightSessions(nullOrJson(u.getHighlightSessionsJson()));
        d.setInsufficientData(u.getInsufficientData() != null && u.getInsufficientData() == 1);
        d.setToolCommandCount(u.getToolCommandCount());
        d.setToolSkillCount(u.getToolSkillCount());
        String tj = u.getToolBreakdownJson();
        d.setToolBreakdown(tj == null || tj.isBlank() ? "[]" : tj);
        d.setTopModels(nullOrJson(u.getTopModelsJson()));
        d.setTopProjects(nullOrJson(u.getTopProjectsJson()));
        d.setAgentDist(nullOrJson(u.getAgentDistJson()));
        return d;
    }

    /** percentile → 分位标签。 */
    private static String bucketOf(BigDecimal p) {
        if (p == null) return null;
        double v = p.doubleValue();
        if (v >= 75) return "top25";
        if (v <= 25) return "bottom25";
        return "mid50";
    }

    /** JSON 字段为 null 时直接给 "null" 字面量避免 @JsonRawValue 序列化失败。 */
    private static String nullOrJson(String s) {
        if (s == null || s.isBlank()) return "null";
        return s;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(DT);
    }
}
