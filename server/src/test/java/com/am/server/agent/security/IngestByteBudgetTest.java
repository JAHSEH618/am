package com.am.server.agent.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IngestByteBudgetTest {

    @Test
    void reservesUpToCapacityAndRefusesBeyondIt() {
        IngestByteBudget budget = new IngestByteBudget(1_000);
        IngestByteBudget.Lease a = budget.tryOpen(600);
        assertThat(a).isNotNull();
        assertThat(budget.tryOpen(401)).as("600 + 401 > 1000").isNull();
        IngestByteBudget.Lease b = budget.tryOpen(400);
        assertThat(b).isNotNull();
        assertThat(budget.inflightBytes()).isEqualTo(1_000);

        a.close();
        assertThat(budget.inflightBytes()).isEqualTo(400);
        assertThat(budget.tryOpen(600)).isNotNull();
    }

    @Test
    void closeIsIdempotentAndDoesNotDoubleRelease() {
        IngestByteBudget budget = new IngestByteBudget(1_000);
        IngestByteBudget.Lease a = budget.tryOpen(300);
        IngestByteBudget.Lease b = budget.tryOpen(300);
        a.close();
        a.close();
        assertThat(budget.inflightBytes()).isEqualTo(300);
        b.close();
        assertThat(budget.inflightBytes()).isZero();
    }

    @Test
    void resizeGrowsShrinksAndFailsWithoutChangingTheHeldAmount() {
        IngestByteBudget budget = new IngestByteBudget(1_000);
        IngestByteBudget.Lease a = budget.tryOpen(100);
        assertThat(a.tryResize(700)).isTrue();
        assertThat(budget.inflightBytes()).isEqualTo(700);
        assertThat(a.tryResize(1_001)).as("over capacity").isFalse();
        assertThat(a.held()).isEqualTo(700);
        assertThat(a.tryResize(200)).isTrue();
        assertThat(budget.inflightBytes()).isEqualTo(200);
        a.close();
        assertThat(a.tryResize(50)).as("a closed lease cannot be revived").isFalse();
        assertThat(budget.inflightBytes()).isZero();
    }

    @Test
    void entryChargeIsTripleForPlainAndWireOnlyForGzip() {
        assertThat(IngestByteBudget.entryCharge(1_000, false)).isEqualTo(3_000);
        assertThat(IngestByteBudget.entryCharge(1_000, true)).isEqualTo(1_000);
    }

    @Test
    void concurrentReserveNeverExceedsCapacity() throws Exception {
        IngestByteBudget budget = new IngestByteBudget(10_000);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger maxSeen = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            fs.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 2_000; i++) {
                    IngestByteBudget.Lease l = budget.tryOpen(1_000);
                    if (l != null) {
                        maxSeen.accumulateAndGet((int) budget.inflightBytes(), Math::max);
                        l.close();
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();
        assertThat(maxSeen.get()).isLessThanOrEqualTo(10_000);
        assertThat(budget.inflightBytes()).isZero();
    }
}
