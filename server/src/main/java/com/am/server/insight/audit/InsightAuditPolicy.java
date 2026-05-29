package com.am.server.insight.audit;

import com.am.server.domain.ai.AiSession;
import com.am.server.insight.config.InsightProperties;
import com.am.server.insight.domain.AiSessionAudit;

/**
 * 判断会话是否需要一次（非强制）LLM 审计：不含「全局 rubric 版本升级自动重审」，
 * 该类重审仅因从未审计、管理员 {@code insight_reaudit_required}、或消息数越过阈值。
 * gz
 */
public final class InsightAuditPolicy {

    private InsightAuditPolicy() {
    }

    public static boolean needsIncrementalAiAudit(AiSession session,
                                                  AiSessionAudit cached,
                                                  InsightProperties properties,
                                                  boolean force) {
        if (force) {
            return true;
        }
        if (cached == null) {
            return true;
        }
        if (session.getInsightReauditRequired() != null && session.getInsightReauditRequired() != 0) {
            return true;
        }
        int currentMessages = nz(session.getTotalMessages());
        int audited = nz(cached.getMessageCountAtAudit());
        return currentMessages > audited + properties.getReauditMessageThreshold();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
