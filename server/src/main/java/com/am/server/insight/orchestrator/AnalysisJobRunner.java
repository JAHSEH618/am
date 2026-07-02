package com.am.server.insight.orchestrator;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.insight.aggregate.ReportAggregator;
import com.am.server.insight.aggregate.ReportAggregator.TeamPayload;
import com.am.server.insight.audit.SessionAuditService;
import com.am.server.insight.audit.SessionAuditService.AuditOutcome;
import com.am.server.insight.config.InsightProperties;
import com.am.server.insight.domain.AiSessionAudit;
import com.am.server.insight.domain.AnalysisReport;
import com.am.server.insight.domain.AnalysisReportRepository;
import com.am.server.insight.domain.AnalysisReportUser;
import com.am.server.insight.domain.AnalysisReportUserRepository;
import com.am.server.insight.domain.ReportStatus;
import com.am.server.insight.narrative.ReportNarrativeService;
import com.am.server.system.ActiveTargetTypesProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 异步流水线 runner —— 与 {@link AnalysisReportOrchestrator} 分开以确保 {@code @Async} 经过
 * 正常 Spring AOP 代理（同类自调用会绕开代理）。
 *
 * <p>方法本身禁用环绕事务（NOT_SUPPORTED），避免长事务把 LLM 等待拖入 MySQL 连接。
 * 内部读 / 写步骤各自由 service 层的小事务包裹。
 * gz
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisJobRunner {

    private final InsightProperties properties;
    private final AnalysisReportRepository reportRepository;
    private final AiSessionRepository sessionRepository;
    private final SessionAuditService auditService;
    private final ReportAggregator aggregator;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final ReportNarrativeService narrativeService;
    private final AnalysisReportUserRepository reportUserRepository;
    private final com.am.server.service.EmployeeDisplayService employeeDisplayService;

    @Async
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runAsync(Long reportId, boolean force) {
        log.info("Analysis report runAsync: reportId={} force={}", reportId, force);
        Optional<AnalysisReport> opt = reportRepository.findById(reportId);
        if (opt.isEmpty()) {
            log.warn("report not found: {}", reportId);
            return;
        }
        AnalysisReport report = opt.get();
        try {
            report.setStatus(ReportStatus.RUNNING);
            report.setStartedTime(LocalDateTime.now());
            reportRepository.save(report);

            LocalDateTime t0 = report.getWindowFrom().atStartOfDay();
            LocalDateTime t1 = report.getWindowTo().plusDays(1).atStartOfDay();
            // v2.10 起：仅审计 active target_type 的会话；客户端继续采集所有 6 种 agent，
            // 但 monitor_target.enabled=0 的 agent 不进入分析报告口径。
            java.util.Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
            List<AiSession> sessions = activeTypes.isEmpty()
                    ? java.util.List.of()
                    : sessionRepository.findOverlappingByStartedOrLastActivityAndTargetTypeIn(t0, t1, activeTypes);

            report.setTotalCount(sessions.size());
            reportRepository.save(report);

            AuditOutcome auditOutcome = auditService.auditAll(
                    sessions,
                    properties.getMaxLlmCallsPerReport(),
                    force,
                    done -> {
                        if (done % 5 == 0 || done == sessions.size()) {
                            updateAuditedCount(reportId, done);
                        }
                    });
            updateAuditedCount(reportId, auditOutcome.byId().size());

            ReportAggregator.AggregateOutcome agg = aggregator.aggregate(report, sessions, auditOutcome.byId());
            TeamPayload payload = agg.teamPayload();

            report.setActiveUserCount(payload.activeUserCount);
            report.setTotalSessionCount(payload.totalSessionCount);
            report.setTotalActiveHours(payload.totalActiveHours);
            report.setTotalAiCommit(payload.totalAiCommit);
            report.setTeamDifficultyDistJson(payload.teamDifficultyDistJson);
            report.setTeamModeDistJson(payload.teamModeDistJson);
            report.setTeamPercentilesJson(payload.teamPercentilesJson);
            report.setTeamCapabilityPercentilesJson(payload.teamCapabilityPercentilesJson);
            report.setWatchlistSummaryJson(payload.watchlistSummaryJson);
            report.setTeamToolBreakdownJson(payload.teamToolBreakdownJson);
            report.setTeamGradeDistJson(payload.teamGradeDistJson);
            report.setJudgeDisagreementRatio(BigDecimal.valueOf(auditOutcome.disagreementRatio())
                    .setScale(4, RoundingMode.HALF_UP));
            try {
                generateNarratives(report, sessions, auditOutcome.byId());
            } catch (Throwable t) {
                log.warn("narrative stage failed, report continues: reportId={}", reportId, t);
            }
            report.setStatus(ReportStatus.COMPLETED);
            report.setCompletedTime(LocalDateTime.now());
            reportRepository.save(report);

            log.info("Analysis report completed: reportId={} users={} sessions={} new-audits={} fail={} disagreeRatio={}",
                    reportId, agg.userMetrics().size(), sessions.size(),
                    auditOutcome.newAudits(), auditOutcome.failedCount(), auditOutcome.disagreementRatio());

        } catch (Throwable e) {
            log.error("Analysis report failed: reportId={}", reportId, e);
            report.setStatus(ReportStatus.FAILED);
            report.setErrorText(truncate(e.toString(), 4000));
            report.setCompletedTime(LocalDateTime.now());
            reportRepository.save(report);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAuditedCount(Long reportId, int audited) {
        reportRepository.findById(reportId).ifPresent(r -> {
            r.setAuditedCount(audited);
            reportRepository.save(r);
        });
    }

    /** 叙事阶段：每个有等级的员工一段评语 + 团队一段总评；失败只记日志。 */
    private void generateNarratives(AnalysisReport report, List<AiSession> sessions,
                                    Map<Long, AiSessionAudit> auditsBySession) {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, List<AiSession>> sessionByUser = new LinkedHashMap<>();
        for (AiSession s : sessions) {
            sessionByUser.computeIfAbsent(s.getUserCode(), k -> new ArrayList<>()).add(s);
        }
        List<AnalysisReportUser> rows =
                reportUserRepository.findByReportIdOrderByCompositePercentileDesc(report.getId());
        List<Map<String, Object>> topForTeam = new ArrayList<>();
        for (AnalysisReportUser row : rows) {
            if (row.getCompositeGrade() == null) {
                continue;   // insufficient_data 不写评语
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("display_name", employeeDisplayService.displayOf(row.getUserCode()));
            payload.put("window", report.getWindowFrom() + " ~ " + report.getWindowTo());
            payload.put("grade", row.getCompositeGrade());
            payload.put("composite_score", row.getCompositeScore());
            payload.put("composite_percentile", row.getCompositePercentile());
            payload.put("confidence", row.getCompositeConfidence());
            payload.put("breakdown", rawJson(mapper, row.getCompositeBreakdownJson()));
            payload.put("session_count", row.getSessionCount());
            payload.put("ai_active_hours", row.getAiActiveHours());
            payload.put("completion_rate", row.getCompletionRate());
            payload.put("commit_revert_rate", row.getCommitRevertRate());
            payload.put("retry_count", row.getRetryCount());
            payload.put("watchlist_flags", rawJson(mapper, row.getWatchlistFlagsJson()));
            payload.put("top_sessions", topSessionCards(
                    sessionByUser.getOrDefault(row.getUserCode(), List.of()), auditsBySession));
            String narrative = narrativeService.generateUserNarrative(writeJson(mapper, payload));
            if (narrative != null) {
                row.setNarrativeJson(narrative);
            }
            if (topForTeam.size() < 5) {
                Map<String, Object> brief = new LinkedHashMap<>();
                brief.put("display_name", payload.get("display_name"));
                brief.put("grade", row.getCompositeGrade());
                brief.put("composite_score", row.getCompositeScore());
                brief.put("top_sessions", payload.get("top_sessions"));
                topForTeam.add(brief);
            }
        }
        reportUserRepository.saveAll(rows);

        Map<String, Object> teamPayload = new LinkedHashMap<>();
        teamPayload.put("window", report.getWindowFrom() + " ~ " + report.getWindowTo());
        teamPayload.put("active_user_count", report.getActiveUserCount());
        teamPayload.put("total_session_count", report.getTotalSessionCount());
        teamPayload.put("total_active_hours", report.getTotalActiveHours());
        teamPayload.put("grade_dist", rawJson(mapper, report.getTeamGradeDistJson()));
        teamPayload.put("difficulty_dist", rawJson(mapper, report.getTeamDifficultyDistJson()));
        teamPayload.put("mode_dist", rawJson(mapper, report.getTeamModeDistJson()));
        teamPayload.put("watchlist_summary", rawJson(mapper, report.getWatchlistSummaryJson()));
        teamPayload.put("top_users", topForTeam);
        String teamNarrative = narrativeService.generateTeamNarrative(writeJson(mapper, teamPayload));
        if (teamNarrative != null) {
            report.setTeamNarrativeJson(teamNarrative);
        }
    }

    /** 每人 Top 5 典型会话上下文卡：按难度、消息数倒序。 */
    private static List<Map<String, Object>> topSessionCards(List<AiSession> userSessions,
                                                             Map<Long, AiSessionAudit> auditsBySession) {
        return userSessions.stream()
                .filter(s -> auditsBySession.get(s.getId()) != null)
                .sorted(Comparator
                        .comparingInt((AiSession s) -> auditsBySession.get(s.getId()).getDifficulty()).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (AiSession s) -> s.getTotalMessages() == null ? 0 : s.getTotalMessages()).reversed()))
                .limit(5)
                .map(s -> {
                    AiSessionAudit a = auditsBySession.get(s.getId());
                    Map<String, Object> card = new LinkedHashMap<>();
                    card.put("date", s.getStartedAt() == null ? null : s.getStartedAt().toLocalDate().toString());
                    card.put("project", s.getProjectName() != null ? s.getProjectName() : s.getRepoUrl());
                    card.put("agent", s.getTargetType());
                    card.put("difficulty", a.getDifficulty());
                    card.put("outcome", a.getOutcome());
                    card.put("mode", a.getMode());
                    card.put("judge_reason", a.getJudgeReasonText());
                    card.put("messages", s.getTotalMessages());
                    return card;
                })
                .toList();
    }

    private static Object rawJson(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String writeJson(ObjectMapper mapper, Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
