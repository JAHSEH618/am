package com.am.server.agent.service;

import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.session.WorkSession;
import com.am.server.domain.session.WorkSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WorkSessionService 并发：心跳与数据上报同时到达时，同一段时间不许被记两遍、不许重复开 OPEN 会话，
 * 且等 agent 锁的线程不占事务（连接）。
 *
 * <p>仓库用“带拷贝语义 + 人为拉长读改写窗口”的内存替身：find 返回副本、save 回写副本，
 * 与 JPA 每事务独立加载实体一致，没有互斥就一定会丢更新 / 重复记时。
 */
class WorkSessionServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 29, 10, 0, 0);

    // ------------------------------------------------------------------ 内存替身

    /** work_session 表：id → 行副本。 */
    private static final class FakeWorkSessionTable {
        private final Map<Long, WorkSession> rows = new ConcurrentHashMap<>();
        private final AtomicInteger ids = new AtomicInteger();

        WorkSessionRepository repository() {
            WorkSessionRepository repo = mock(WorkSessionRepository.class);
            when(repo.findFirstByAgentIdAndStatusOrderByStartTimeDesc(anyString(), eq(WorkSession.STATUS_OPEN)))
                    .thenAnswer(inv -> {
                        String agentId = inv.getArgument(0);
                        Optional<WorkSession> latest = rows.values().stream()
                                .filter(r -> agentId.equals(r.getAgentId()) && WorkSession.STATUS_OPEN.equals(r.getStatus()))
                                .max((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                                .map(FakeWorkSessionTable::copy);
                        pause(); // 读改写窗口
                        return latest;
                    });
            when(repo.save(any(WorkSession.class))).thenAnswer(inv -> {
                WorkSession s = inv.getArgument(0);
                pause();
                if (s.getId() == null) {
                    s.setId((long) ids.incrementAndGet());
                }
                rows.put(s.getId(), copy(s));
                return s;
            });
            return repo;
        }

        void seed(WorkSession s) {
            s.setId((long) ids.incrementAndGet());
            rows.put(s.getId(), copy(s));
        }

        List<WorkSession> forAgent(String agentId) {
            return rows.values().stream().filter(r -> agentId.equals(r.getAgentId())).toList();
        }

        List<WorkSession> openForAgent(String agentId) {
            return forAgent(agentId).stream().filter(r -> WorkSession.STATUS_OPEN.equals(r.getStatus())).toList();
        }

        static WorkSession copy(WorkSession s) {
            WorkSession c = new WorkSession();
            c.setId(s.getId());
            c.setAgentId(s.getAgentId());
            c.setUserCode(s.getUserCode());
            c.setHostHash(s.getHostHash());
            c.setStartTime(s.getStartTime());
            c.setEndTime(s.getEndTime());
            c.setDurationSeconds(s.getDurationSeconds());
            c.setActiveSeconds(s.getActiveSeconds());
            c.setStatus(s.getStatus());
            return c;
        }

        private static void pause() {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static AgentDevice device(String agentId) {
        AgentDevice d = new AgentDevice();
        d.setAgentId(agentId);
        d.setUserCode("u-" + agentId);
        d.setHostHash("h-" + agentId);
        return d;
    }

    private static WorkSession open(String agentId, LocalDateTime start) {
        WorkSession s = new WorkSession();
        s.setAgentId(agentId);
        s.setUserCode("u-" + agentId);
        s.setHostHash("h-" + agentId);
        s.setStartTime(start);
        s.setStatus(WorkSession.STATUS_OPEN);
        s.setDurationSeconds(0L);
        s.setActiveSeconds(0L);
        return s;
    }

    private static WorkSessionService service(WorkSessionRepository repo, TransactionOperations tx) {
        AiSessionRepository ai = mock(AiSessionRepository.class);
        return new WorkSessionService(repo, ai, new AgentProperties(), tx);
    }

    // ------------------------------------------------------------------ 并发计时

    @Test
    void concurrentHeartbeatAndReportNeverCreditMoreThanTheWallClock() throws Exception {
        FakeWorkSessionTable table = new FakeWorkSessionTable();
        table.seed(open("a1", T0));
        WorkSessionService svc = service(table.repository(), TransactionOperations.withoutTransaction());
        AgentDevice dev = device("a1");
        int rounds = 40;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                LocalDateTime base = T0.plusSeconds(60L * i);
                // 两个请求在各自开头读到的 last_seen 都是 base（心跳与数据上报同时到达的现场）
                CyclicBarrier go = new CyclicBarrier(2);
                Future<?> heartbeat = pool.submit(() -> {
                    go.await();
                    svc.advance(dev, base, base.plusSeconds(30), false);
                    return null;
                });
                Future<?> report = pool.submit(() -> {
                    go.await();
                    svc.advance(dev, base, base.plusSeconds(60), true);
                    return null;
                });
                heartbeat.get(10, TimeUnit.SECONDS);
                report.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<WorkSession> all = table.forAgent("a1");
        assertThat(all).as("不得重复开会话").hasSize(1);
        assertThat(all.get(0).getDurationSeconds())
                .as("总时长 = 墙钟跨度 60s × %d 轮，不能被两个并发请求各记一遍", rounds)
                .isEqualTo(60L * rounds);
    }

    @Test
    void twoFirstReportsRacingOpenExactlyOneWorkSession() throws Exception {
        FakeWorkSessionTable table = new FakeWorkSessionTable();
        WorkSessionService svc = service(table.repository(), TransactionOperations.withoutTransaction());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 60; i++) {
                String agent = "fresh-" + i;
                AgentDevice dev = device(agent);
                CyclicBarrier go = new CyclicBarrier(2);
                List<Future<?>> fs = new ArrayList<>();
                for (int k = 0; k < 2; k++) {
                    int off = k * 10;
                    fs.add(pool.submit(() -> {
                        go.await();
                        svc.advance(dev, null, T0.plusSeconds(off), false);
                        return null;
                    }));
                }
                for (Future<?> f : fs) {
                    f.get(10, TimeUnit.SECONDS);
                }
                assertThat(table.openForAgent(agent)).as("agent %s", agent).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anOlderCapturedAtArrivingLateCreditsNothingAndDoesNotRewindTheClock() {
        FakeWorkSessionTable table = new FakeWorkSessionTable();
        table.seed(open("a1", T0));
        WorkSessionService svc = service(table.repository(), TransactionOperations.withoutTransaction());
        AgentDevice dev = device("a1");

        svc.advance(dev, T0, T0.plusSeconds(60), true);
        svc.advance(dev, T0, T0.plusSeconds(30), true);  // 乱序：旧的后到
        svc.advance(dev, T0.plusSeconds(60), T0.plusSeconds(90), true);

        assertThat(table.forAgent("a1").get(0).getDurationSeconds()).isEqualTo(90L);
        assertThat(table.forAgent("a1").get(0).getActiveSeconds()).isEqualTo(90L);
    }

    @Test
    void gapStillClosesAndReopensAndAStragglerWithTheStalePrevSeenDoesNotDoItTwice() {
        FakeWorkSessionTable table = new FakeWorkSessionTable();
        table.seed(open("a1", T0));
        WorkSessionService svc = service(table.repository(), TransactionOperations.withoutTransaction());
        AgentDevice dev = device("a1");

        svc.advance(dev, T0, T0.plusSeconds(400), false);      // 跨 >180s：关旧开新
        svc.advance(dev, T0, T0.plusSeconds(410), false);      // 并发的另一个请求，拿的还是旧 prevSeen

        List<WorkSession> all = table.forAgent("a1");
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(s -> WorkSession.STATUS_CLOSED.equals(s.getStatus()))).hasSize(1);
        WorkSession open = table.openForAgent("a1").get(0);
        assertThat(open.getStartTime()).isEqualTo(T0.plusSeconds(400));
        assertThat(open.getDurationSeconds()).as("只记 400→410 的 10s，不再从旧 prevSeen 重算").isEqualTo(10L);
    }

    @Test
    void perReportCapStillApplies() {
        FakeWorkSessionTable table = new FakeWorkSessionTable();
        table.seed(open("a1", T0));
        WorkSessionService svc = service(table.repository(), TransactionOperations.withoutTransaction());

        svc.advance(device("a1"), T0, T0.plusSeconds(170), true); // 170 < 180 不换会话，但封顶 120

        assertThat(table.forAgent("a1").get(0).getDurationSeconds()).isEqualTo(120L);
    }

    @Test
    void aRolledBackTransactionDoesNotMoveTheClockForward() {
        FakeWorkSessionTable table = new FakeWorkSessionTable();
        table.seed(open("a1", T0));
        boolean[] failNext = {true};
        TransactionOperations flaky = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                if (failNext[0]) {
                    failNext[0] = false;
                    throw new IllegalStateException("rolled back");
                }
                return action.doInTransaction(null);
            }
        };
        WorkSessionService svc = service(table.repository(), flaky);
        AgentDevice dev = device("a1");

        assertThatThrownBy(() -> svc.advance(dev, T0, T0.plusSeconds(60), false)).isInstanceOf(IllegalStateException.class);
        svc.advance(dev, T0, T0.plusSeconds(60), false); // 重试：这 60s 不能因为上一次回滚而丢

        assertThat(table.forAgent("a1").get(0).getDurationSeconds()).isEqualTo(60L);
    }

    // ------------------------------------------------------------------ 锁的位置

    /** 记录“同时处于事务内”的线程数；第一个进入事务的线程可被闩住。 */
    private static final class GatedTx implements TransactionOperations {
        final AtomicInteger inTx = new AtomicInteger();
        final AtomicInteger maxInTx = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            int now = inTx.incrementAndGet();
            maxInTx.accumulateAndGet(now, Math::max);
            try {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return action.doInTransaction(mock(TransactionStatus.class));
            } finally {
                inTx.decrementAndGet();
            }
        }

        @Override
        public void executeWithoutResult(Consumer<TransactionStatus> action) {
            execute(status -> {
                action.accept(status);
                return null;
            });
        }
    }

    @Test
    void aThreadWaitingForTheAgentLockHoldsNoTransactionAndTheDbConnectionThatComesWithIt() throws Exception {
        FakeWorkSessionTable table = new FakeWorkSessionTable();
        table.seed(open("a1", T0));
        GatedTx tx = new GatedTx();
        WorkSessionService svc = service(table.repository(), tx);
        AgentDevice dev = device("a1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> svc.advance(dev, T0, T0.plusSeconds(10), false));
            assertThat(tx.entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> svc.advance(dev, T0, T0.plusSeconds(20), false));

            Thread.sleep(300); // 给第二个线程足够时间“错误地”抢先开事务
            assertThat(tx.maxInTx.get())
                    .as("第二个请求应卡在锁外，不能已经开了事务（事务 = 占着一条 Hikari 连接等锁）")
                    .isEqualTo(1);
            assertThat(second.isDone()).isFalse();

            tx.release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            tx.release.countDown();
            pool.shutdownNow();
        }
        assertThat(table.forAgent("a1").get(0).getDurationSeconds()).isEqualTo(20L);
    }

    @Test
    void differentAgentsDoNotWaitForEachOther() throws Exception {
        // 选两个落在不同 stripe 的 agent
        String a = "agent-A";
        String b = "agent-B";
        for (int i = 0; WorkSessionService.stripeIndex(a) == WorkSessionService.stripeIndex(b); i++) {
            b = "agent-B" + i;
        }
        FakeWorkSessionTable table = new FakeWorkSessionTable();
        table.seed(open(a, T0));
        table.seed(open(b, T0));
        GatedTx tx = new GatedTx();
        WorkSessionService svc = service(table.repository(), tx);
        String agentB = b;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> blocked = pool.submit(() -> svc.advance(device(a), T0, T0.plusSeconds(10), false));
            assertThat(tx.entered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> other = pool.submit(() -> svc.advance(device(agentB), T0, T0.plusSeconds(10), false));
            other.get(5, TimeUnit.SECONDS); // A 还被闩着，B 照样完成

            assertThat(blocked.isDone()).isFalse();
            tx.release.countDown();
            blocked.get(5, TimeUnit.SECONDS);
        } finally {
            tx.release.countDown();
            pool.shutdownNow();
        }
    }
}
