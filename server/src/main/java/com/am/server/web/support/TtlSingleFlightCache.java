package com.am.server.web.support;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 读路径小缓存：TTL + single-flight。同一 key 过期后的并发请求只有一个去算，其余等同一份结果；
 * 条目数超过阈值时顺手清掉已过期的，不随选过的时间窗只增不减。
 *
 * <p>用在"同一窗口会被多个标签页 / 并发请求反复算"的整窗聚合上（模型分布、热力图、仪表盘审计计数）。
 * gz
 */
public final class TtlSingleFlightCache<K, V> {

    private static final int EVICT_THRESHOLD = 32;

    private final Duration ttl;
    private final ConcurrentHashMap<K, Entry<V>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, CompletableFuture<V>> inflight = new ConcurrentHashMap<>();

    private record Entry<V>(V value, Instant expiresAt) {}

    public TtlSingleFlightCache(Duration ttl) {
        this.ttl = ttl;
    }

    public V get(K key, Supplier<V> loader) {
        Entry<V> hit = cache.get(key);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.value();
        }
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = inflight.putIfAbsent(key, mine);
        if (running != null) {
            return await(running);
        }
        try {
            V value = loader.get();
            Instant now = Instant.now();
            cache.put(key, new Entry<>(value, now.plus(ttl)));
            if (cache.size() > EVICT_THRESHOLD) {
                cache.entrySet().removeIf(en -> !en.getValue().expiresAt().isAfter(now));
            }
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key, mine);
        }
    }

    int size() {
        return cache.size();
    }

    private static <V> V await(CompletableFuture<V> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }
}
