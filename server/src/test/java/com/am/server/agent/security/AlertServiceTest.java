package com.am.server.agent.security;

import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentAlert;
import com.am.server.domain.agent.AgentAlertRepository;
import com.am.server.domain.agent.AlertType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertServiceTest {

    /** 可拨动的时钟。 */
    private static final class TestClock extends Clock {
        final AtomicLong now = new AtomicLong(Instant.parse("2026-09-29T10:00:00Z").toEpochMilli());

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }

        @Override
        public long millis() {
            return now.get();
        }

        void advanceMinutes(long m) {
            now.addAndGet(m * 60_000L);
        }

        void advanceSeconds(long s) {
            now.addAndGet(s * 1_000L);
        }
    }

    private final AgentAlertRepository repo = mock(AgentAlertRepository.class);
    private final TestClock clock = new TestClock();
    private final AgentProperties props = new AgentProperties();

    private AlertService service() {
        return new AlertService(repo, props, clock, Runnable::run);
    }

    private List<AgentAlert> saved(int expected) {
        ArgumentCaptor<AgentAlert> c = ArgumentCaptor.forClass(AgentAlert.class);
        verify(repo, times(expected)).save(c.capture());
        return c.getAllValues();
    }

    @Test
    void sameAgentAndTypeIsWrittenOncePerWindow() {
        AlertService s = service();
        for (int i = 0; i < 50; i++) {
            s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, "missing signature headers");
            clock.advanceSeconds(5);   // 4 分钟内一直在报
        }
        List<AgentAlert> rows = saved(1);
        assertThat(rows.get(0).getMessage()).isEqualTo("missing signature headers");
    }

    @Test
    void afterTheWindowTheNextRowCarriesTheSuppressedCount() {
        AlertService s = service();
        s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, "timestamp drift 400000ms");
        for (int i = 0; i < 9; i++) {
            clock.advanceSeconds(20);
            s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, "timestamp drift 400000ms");   // 被压掉 9 次
        }
        clock.advanceMinutes(5);
        s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, "timestamp drift 400000ms");

        List<AgentAlert> rows = saved(2);
        assertThat(rows.get(0).getMessage()).doesNotContain("suppressed");
        assertThat(rows.get(1).getMessage()).contains("+9 similar suppressed in last 5min");
    }

    @Test
    void differentAgentsTypesAndLevelsAreIndependent() {
        AlertService s = service();
        s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, "a");
        s.warn("agent-2", null, null, AlertType.SIGNATURE_INVALID, "b");
        s.warn("agent-1", null, null, AlertType.NONCE_REPLAY, "c");
        s.error("agent-1", null, null, AlertType.SIGNATURE_INVALID, "d");   // 同 agent 同 type，但 ERROR 不该被先前的 WARN 压掉
        saved(4);
    }

    @Test
    void globalPerMinuteCapDropsTheExcessAndCountsIt() {
        props.setAlertMaxPerMinute(5);
        AlertService s = service();
        for (int i = 0; i < 20; i++) {
            s.warn("forged-" + i, null, null, AlertType.SIGNATURE_INVALID, "agent not found");
        }
        saved(5);
        assertThat(s.droppedCount()).isEqualTo(15);

        clock.advanceMinutes(1);   // 下一分钟额度恢复
        s.warn("forged-100", null, null, AlertType.SIGNATURE_INVALID, "agent not found");
        verify(repo, times(6)).save(any(AgentAlert.class));
    }

    @Test
    void repositoryFailureIsSwallowedAndDoesNotPoisonTheLimiter() {
        when(repo.save(any(AgentAlert.class))).thenThrow(new DataAccessResourceFailureException("db down"));
        AlertService s = service();
        s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, "x");   // 不抛
        verify(repo).save(any(AgentAlert.class));
    }

    @Test
    void fullWriteQueueDropsInsteadOfBlockingTheRequestThread() {
        Executor rejecting = r -> {
            throw new RejectedExecutionException("queue full");
        };
        AlertService s = new AlertService(repo, props, clock, rejecting);
        s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, "x");
        assertThat(s.droppedCount()).isEqualTo(1);
        verify(repo, never()).save(any());
    }

    @Test
    void writeHappensOnTheWriterThreadNotTheCaller() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Thread> writerThread = new java.util.concurrent.atomic.AtomicReference<>();
        when(repo.save(any(AgentAlert.class))).thenAnswer(inv -> {
            writerThread.set(Thread.currentThread());
            return inv.getArgument(0);
        });
        AlertService s = new AlertService(repo, props, Clock.systemDefaultZone(), null);   // 自带后台线程
        try {
            s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, "x");
            long deadline = System.currentTimeMillis() + 5_000;
            while (writerThread.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(writerThread.get()).isNotNull().isNotSameAs(Thread.currentThread());
            assertThat(writerThread.get().getName()).isEqualTo("agent-alert-writer");
        } finally {
            s.shutdown();
        }
    }

    @Test
    void nullAndOverlongFieldsNeverBreakTheWrite() {
        AlertService s = service();
        String longId = "x".repeat(500);
        s.warn(null, null, null, AlertType.SIGNATURE_INVALID, null);
        s.error(longId, "u".repeat(300), "h".repeat(300), AlertType.SIGNATURE_INVALID, "m".repeat(2_000));

        List<AgentAlert> rows = saved(2);
        assertThat(rows.get(0).getAgentId()).isNull();
        assertThat(rows.get(1).getAgentId()).hasSize(64);
        assertThat(rows.get(1).getUserCode()).hasSize(64);
        assertThat(rows.get(1).getHostHash()).hasSize(128);
        assertThat(rows.get(1).getMessage()).hasSize(510);
    }

    @Test
    void suppressedSuffixSurvivesTruncationOfLongMessages() {
        AlertService s = service();
        String longMsg = "m".repeat(2_000);
        s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, longMsg);
        s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, longMsg);
        clock.advanceMinutes(6);
        s.warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, longMsg);

        List<AgentAlert> rows = saved(2);
        assertThat(rows.get(1).getMessage()).hasSize(510).contains("+1 similar suppressed");
    }

    @Test
    void trackedKeysAreBoundedSoForgedAgentIdsCannotGrowMemory() {
        props.setAlertMaxPerMinute(Integer.MAX_VALUE);
        AlertService s = service();
        for (int i = 0; i < 10_000; i++) {
            s.warn("forged-" + i, null, null, AlertType.SIGNATURE_INVALID, "x");
        }
        verify(repo, times(10_000)).save(any(AgentAlert.class));   // 每个都是新 key，都放行；重点是不 OOM、不抛
    }
}
