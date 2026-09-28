package com.am.server.web.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtlSingleFlightCacheTest {

    @Test
    void servesCachedValueWithinTtl() {
        TtlSingleFlightCache<String, Integer> cache = new TtlSingleFlightCache<>(Duration.ofMinutes(1));
        AtomicInteger loads = new AtomicInteger();
        assertThat(cache.get("k", loads::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("k", loads::incrementAndGet)).isEqualTo(1);
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void reloadsAfterExpiry() {
        TtlSingleFlightCache<String, Integer> cache = new TtlSingleFlightCache<>(Duration.ZERO);
        AtomicInteger loads = new AtomicInteger();
        cache.get("k", loads::incrementAndGet);
        cache.get("k", loads::incrementAndGet);
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void concurrentMissesShareOneLoad() throws Exception {
        TtlSingleFlightCache<String, Integer> cache = new TtlSingleFlightCache<>(Duration.ofMinutes(1));
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            results.add(pool.submit(() -> cache.get("k", () -> {
                inLoader.countDown();
                await(release);
                return loads.incrementAndGet();
            })));
            assertThat(inLoader.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 3; i++) {
                results.add(pool.submit(() -> cache.get("k", loads::incrementAndGet)));
            }
            Thread.sleep(100);
            release.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get(2, TimeUnit.SECONDS)).isEqualTo(1);
            }
            assertThat(loads.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void loaderFailureIsNotCachedAndPropagates() {
        TtlSingleFlightCache<String, Integer> cache = new TtlSingleFlightCache<>(Duration.ofMinutes(1));
        assertThatThrownBy(() -> cache.get("k", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(cache.get("k", () -> 7)).isEqualTo(7);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
