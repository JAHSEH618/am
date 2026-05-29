package com.am.server.insight.audit;

import com.am.server.domain.ai.AiSession;
import com.am.server.insight.config.InsightProperties;
import com.am.server.insight.domain.AiSessionAudit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightAuditPolicyTest {

    @Test
    void forceAlwaysAudits() {
        InsightProperties p = new InsightProperties();
        AiSession s = new AiSession();
        AiSessionAudit c = new AiSessionAudit();
        assertTrue(InsightAuditPolicy.needsIncrementalAiAudit(s, c, p, true));
    }

    @Test
    void noCachedNeedsAudit() {
        InsightProperties p = new InsightProperties();
        AiSession s = new AiSession();
        assertTrue(InsightAuditPolicy.needsIncrementalAiAudit(s, null, p, false));
    }

    @Test
    void reauditFlagBypassesCache() {
        InsightProperties p = new InsightProperties();
        AiSession s = new AiSession();
        s.setTotalMessages(10);
        s.setInsightReauditRequired(1);
        AiSessionAudit c = new AiSessionAudit();
        c.setMessageCountAtAudit(10);
        assertTrue(InsightAuditPolicy.needsIncrementalAiAudit(s, c, p, false));
    }

    @Test
    void rubricVersionDriftDoesNotAutoReaudit() {
        InsightProperties p = new InsightProperties();
        p.setAuditVersion("v9.0");
        AiSession s = new AiSession();
        s.setTotalMessages(10);
        AiSessionAudit c = new AiSessionAudit();
        c.setMessageCountAtAudit(10);
        c.setAuditVersion("v3.0");
        assertFalse(InsightAuditPolicy.needsIncrementalAiAudit(s, c, p, false));
    }

    @Test
    void messageGrowthTriggersReaudit() {
        InsightProperties p = new InsightProperties();
        p.setReauditMessageThreshold(5);
        AiSession s = new AiSession();
        s.setTotalMessages(20);
        AiSessionAudit c = new AiSessionAudit();
        c.setMessageCountAtAudit(10);
        assertTrue(InsightAuditPolicy.needsIncrementalAiAudit(s, c, p, false));
    }
}
