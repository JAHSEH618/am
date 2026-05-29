package com.am.server.insight.orchestrator;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.insight.aggregate.ReportAggregator;
import com.am.server.insight.aggregate.ReportAggregator.TeamPayload;
import com.am.server.insight.audit.SessionAuditService;
import com.am.server.insight.audit.SessionAuditService.AuditOutcome;
import com.am.server.insight.config.InsightProperties;
import com.am.server.insight.domain.AnalysisReport;
import com.am.server.insight.domain.AnalysisReportRepository;
import com.am.server.insight.domain.ReportStatus;
import com.am.server.system.ActiveTargetTypesProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
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
            report.setJudgeDisagreementRatio(BigDecimal.valueOf(auditOutcome.disagreementRatio())
                    .setScale(4, RoundingMode.HALF_UP));
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
