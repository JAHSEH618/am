package com.am.server.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LogThrottleTest {

    @Test
    void firstEventEmitsThenSuppressesUntilTheIntervalPasses() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LogThrottle t = new LogThrottle(30_000L, clock::get);

        assertThat(t.tryEmit()).as("first event logs, count 1").isEqualTo(1);
        assertThat(t.tryEmit()).isZero();
        assertThat(t.tryEmit()).isZero();
        clock.addAndGet(29_999);
        assertThat(t.tryEmit()).isZero();

        clock.addAndGet(1);
        assertThat(t.tryEmit()).as("3 suppressed + this one").isEqualTo(4);
        assertThat(t.tryEmit()).isZero();
    }

    @Test
    void keyedThrottlesAreIndependentPerKey() {
        AtomicLong clock = new AtomicLong(0L);
        LogThrottle.Keyed k = new LogThrottle.Keyed(600_000L, 100, clock::get);

        assertThat(k.tryEmit("agent-1")).isEqualTo(1);
        assertThat(k.tryEmit("agent-2")).isEqualTo(1);
        assertThat(k.tryEmit("agent-1")).isZero();
        clock.addAndGet(600_000L);
        assertThat(k.tryEmit("agent-1")).isEqualTo(2);
        assertThat(k.tryEmit(null)).as("null key is tolerated").isEqualTo(1);
    }

    @Test
    void keyedThrottleIsBoundedSoForgedKeysCannotGrowIt() {
        AtomicLong clock = new AtomicLong(0L);
        LogThrottle.Keyed k = new LogThrottle.Keyed(600_000L, 10, clock::get);
        for (int i = 0; i < 1_000; i++) {
            assertThat(k.tryEmit("forged-" + i)).isEqualTo(1);
        }
    }
}
