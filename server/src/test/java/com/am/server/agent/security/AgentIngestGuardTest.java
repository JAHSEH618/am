package com.am.server.agent.security;

import com.am.server.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentIngestGuardTest {

    private AgentIngestGuard guard(int legacyMax) {
        AgentProperties p = new AgentProperties();
        p.setIngestLegacyMaxConcurrency(legacyMax);
        return new AgentIngestGuard(p);
    }

    @Test
    void onlyOneHeavyRequestPerAgentAtATime() {
        AgentIngestGuard g = guard(2);
        AgentIngestGuard.Admission first = g.tryAdmit("a1", "1.3.3");
        assertThat(first.denied()).isNull();

        assertThat(g.tryAdmit("a1", "1.3.3").denied()).isEqualTo(AgentIngestMetrics.REASON_PER_AGENT);
        assertThat(g.tryAdmit("a2", "1.3.3").denied()).as("other agents are independent").isNull();

        first.close();
        assertThat(g.tryAdmit("a1", "1.3.3").denied()).as("released").isNull();
    }

    @Test
    void closeIsIdempotent() {
        AgentIngestGuard g = guard(2);
        AgentIngestGuard.Admission a = g.tryAdmit("a1", "1.3.2");
        AgentIngestGuard.Admission b = g.tryAdmit("a1", "1.3.2");   // 被拒（per-agent）
        assertThat(b.denied()).isNotNull();
        b.close();   // 被拒的 admission close 不能把 a 的名额释放掉
        assertThat(g.tryAdmit("a1", "1.3.2").denied()).isEqualTo(AgentIngestMetrics.REASON_PER_AGENT);
        a.close();
        a.close();
        assertThat(g.inflightAgentCount()).isZero();
        assertThat(g.legacyAvailablePermits()).isEqualTo(2);
    }

    @Test
    void legacyClientsShareASmallQuota() {
        AgentIngestGuard g = guard(2);
        AgentIngestGuard.Admission l1 = g.tryAdmit("old-1", "1.3.2");
        AgentIngestGuard.Admission l2 = g.tryAdmit("old-2", "1.0.0");
        assertThat(l1.denied()).isNull();
        assertThat(l2.denied()).isNull();

        AgentIngestGuard.Admission l3 = g.tryAdmit("old-3", "1.2.9");
        assertThat(l3.denied()).isEqualTo(AgentIngestMetrics.REASON_LEGACY);
        assertThat(g.inflightAgentCount()).as("a rejected legacy request leaves no per-agent residue").isEqualTo(2);

        assertThat(g.tryAdmit("new-1", "1.3.3").denied()).as("new clients are not counted against it").isNull();
        assertThat(g.tryAdmit("new-2", "1.4.0").denied()).isNull();

        l1.close();
        assertThat(g.tryAdmit("old-3", "1.2.9").denied()).isNull();
    }

    @Test
    void legacyLimitCanBeDisabled() {
        AgentIngestGuard g = guard(0);
        for (int i = 0; i < 10; i++) {
            assertThat(g.tryAdmit("old-" + i, "1.0.0").denied()).isNull();
        }
        assertThat(g.legacyAvailablePermits()).isEqualTo(-1);
    }

    @Test
    void versionParsingIsLenientAndUnparseableMeansNotLegacy() {
        AgentIngestGuard g = guard(2);
        assertThat(g.isLegacy("1.3.2")).isTrue();
        assertThat(g.isLegacy("v1.3.2")).isTrue();
        assertThat(g.isLegacy("1.3.2-rc1")).isTrue();
        assertThat(g.isLegacy("1.3")).isTrue();
        assertThat(g.isLegacy("0.9.9+build5")).isTrue();
        assertThat(g.isLegacy("1.3.3")).isFalse();
        assertThat(g.isLegacy("1.3.3-beta")).isFalse();
        assertThat(g.isLegacy("1.10.0")).as("numeric, not lexicographic").isFalse();
        assertThat(g.isLegacy("2.0.0")).isFalse();
        assertThat(g.isLegacy("dev")).isFalse();
        assertThat(g.isLegacy("")).isFalse();
        assertThat(g.isLegacy(null)).isFalse();
        assertThat(g.isLegacy("unknown-build")).isFalse();
    }

    @Test
    void parseVersionShapes() {
        assertThat(AgentIngestGuard.parseVersion("1.3.3")).containsExactly(1, 3, 3);
        assertThat(AgentIngestGuard.parseVersion("V2")).containsExactly(2, 0, 0);
        assertThat(AgentIngestGuard.parseVersion("1.2.3.4")).containsExactly(1, 2, 3);
        assertThat(AgentIngestGuard.parseVersion("abc")).isNull();
        assertThat(AgentIngestGuard.parseVersion(".1")).isNull();
        assertThat(AgentIngestGuard.parseVersion(null)).isNull();
    }

    @Test
    void customThresholdIsHonoured() {
        AgentProperties p = new AgentProperties();
        p.setLegacyClientBelowVersion("1.4.0");
        AgentIngestGuard g = new AgentIngestGuard(p);
        assertThat(g.isLegacy("1.3.9")).isTrue();
        assertThat(g.isLegacy("1.4.0")).isFalse();
    }
}
