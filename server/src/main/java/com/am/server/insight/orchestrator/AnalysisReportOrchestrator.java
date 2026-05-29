package com.am.server.insight.orchestrator;

import com.am.server.insight.config.InsightProperties;
import com.am.server.insight.domain.AnalysisReport;
import com.am.server.insight.domain.AnalysisReportRepository;
import com.am.server.insight.domain.AnalysisReportUserRepository;
import com.am.server.insight.domain.ReportStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 分析报告编排器：把"用户点 [生成报告]"翻译成
 *   pending → running (audit + aggregate) → completed / failed 状态转移。
 *
 * <p>本类负责状态机入口（{@link #findOrCreate}），真正的异步流水线在
 * {@link AnalysisJobRunner#runAsync(Long, boolean)} —— 拆开两个类是为了让 {@code @Async} 经过正常
 * Spring AOP 代理（同类自调用会绕开代理）。
 * gz
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisReportOrchestrator {

    private final InsightProperties properties;
    private final AnalysisReportRepository reportRepository;
    private final AnalysisReportUserRepository userRepository;
    private final AnalysisJobRunner jobRunner;

    /**
     * 找现有报告或创建新报告（幂等）。
     *
     * <p>命中 completed / running / pending → 直接返回（不重新触发）；
     * force=true 或命中 failed → 删除旧 user 行，状态回到 pending，重新触发异步任务。
     */
    @Transactional
    public AnalysisReport findOrCreate(LocalDate from, LocalDate to, boolean force) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("invalid window: from=" + from + " to=" + to);
        }
        Optional<AnalysisReport> existing = reportRepository.findByWindowFromAndWindowTo(from, to);

        if (existing.isPresent()) {
            AnalysisReport r = existing.get();
            boolean shouldRerun = force || ReportStatus.FAILED.equals(r.getStatus());
            if (!shouldRerun) {
                // 事务未提交就调度 @Async 时，worker 可能读不到行并提前返回，报告会永久停在 pending。
                // 用户再次点「生成」会命中幂等直接返回，不再触发 —— 此处对孤儿 pending 补调度一次。
                recoverStuckPendingIfNeeded(r, false);
                return r;
            }
            userRepository.deleteByReportId(r.getId());
            r.setStatus(ReportStatus.PENDING);
            r.setAuditedCount(0);
            r.setTotalCount(0);
            r.setErrorText(null);
            r.setStartedTime(null);
            r.setCompletedTime(null);
            // 清空上一轮聚合结果，避免异步任务前半段失败时 UI 仍显示旧图表 / 旧人数。
            r.setActiveUserCount(null);
            r.setTotalSessionCount(null);
            r.setTotalActiveHours(null);
            r.setTotalAiCommit(null);
            r.setTeamDifficultyDistJson(null);
            r.setTeamModeDistJson(null);
            r.setTeamPercentilesJson(null);
            r.setTeamCapabilityPercentilesJson(null);
            r.setWatchlistSummaryJson(null);
            r.setJudgeDisagreementRatio(null);
            r.setTeamToolBreakdownJson(null);
            r.setReportVersion(properties.getAuditVersion());
            r.setRubricVersion(properties.getRubricVersion());
            AnalysisReport saved = reportRepository.save(r);
            // 仅 force=true 时忽略 ai_session_audit 缓存并对该窗口会话全量重审（管理员显式意图）。
            scheduleRunAsyncAfterCommit(saved.getId(), force);
            return saved;
        }

        AnalysisReport r = new AnalysisReport();
        r.setWindowFrom(from);
        r.setWindowTo(to);
        r.setReportVersion(properties.getAuditVersion());
        r.setRubricVersion(properties.getRubricVersion());
        r.setStatus(ReportStatus.PENDING);
        AnalysisReport saved = reportRepository.save(r);
        // 新建报告天然没有任何缓存可命中，force 标志这里给 false 即可。
        scheduleRunAsyncAfterCommit(saved.getId(), false);
        return saved;
    }

    /**
     * 在事务提交后再调度异步流水线，避免 worker 在 INSERT 未可见时 findById 落空并永久停在 pending。
     */
    private void scheduleRunAsyncAfterCommit(Long reportId, boolean force) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    jobRunner.runAsync(reportId, force);
                }
            });
        } else {
            jobRunner.runAsync(reportId, force);
        }
    }

    /** pending 且从未进入 running（started_time 为空）视为调度丢失，补一次异步任务。 */
    private void recoverStuckPendingIfNeeded(AnalysisReport r, boolean force) {
        if (!ReportStatus.PENDING.equals(r.getStatus()) || r.getStartedTime() != null) {
            return;
        }
        log.warn("recovering stuck pending analysis report: id={} window={}..{}",
                r.getId(), r.getWindowFrom(), r.getWindowTo());
        scheduleRunAsyncAfterCommit(r.getId(), force);
    }
}
