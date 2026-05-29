package com.am.server.insight.audit;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.insight.config.InsightProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 将后台审计进度写回 {@code ai_session}（与 {@code ai_session_audit} 明细表配合）。
 * gz
 */
@Service
@RequiredArgsConstructor
public class InsightSessionAuditStateService {

    private final AiSessionRepository sessionRepository;
    private final InsightProperties insightProperties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(Long sessionId, LocalDateTime leaseUntil) {
        touch(sessionId, s -> {
            s.setInsightAuditStatus(InsightAuditStatus.RUNNING.code());
            s.setInsightAuditLeaseUntil(leaseUntil);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDoneAfterAudit(Long sessionId) {
        touch(sessionId, s -> {
            s.setInsightAuditStatus(InsightAuditStatus.DONE.code());
            s.setInsightAuditRubricVersion(insightProperties.getAuditVersion());
            s.setInsightReauditRequired(0);
            s.setInsightAuditLeaseUntil(null);
        });
    }

    /**
     * 扫描器取出来后发现无需再调 LLM（与缓存命中 race），将状态对齐为 DONE。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDoneIfAlreadySatisfied(Long sessionId) {
        AiSession s = sessionRepository.findById(sessionId).orElse(null);
        if (s == null) {
            return;
        }
        s.setInsightAuditStatus(InsightAuditStatus.DONE.code());
        s.setInsightAuditLeaseUntil(null);
        sessionRepository.save(s);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long sessionId) {
        touch(sessionId, s -> {
            s.setInsightAuditStatus(InsightAuditStatus.FAILED.code());
            s.setInsightAuditLeaseUntil(null);
        });
    }

    private void touch(Long sessionId, java.util.function.Consumer<AiSession> fn) {
        AiSession s = sessionRepository.findById(sessionId).orElse(null);
        if (s == null) {
            return;
        }
        fn.accept(s);
        sessionRepository.save(s);
    }
}
