package com.am.server.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 日志节流：满载 / 被攻击时每个被拒请求都打一行 WARN 会把日志盘打爆（还会拖慢请求线程）。
 *
 * <p>用法：{@code long n = throttle.tryEmit(); if (n > 0) log.warn("... {} events in window", n);}——
 * 返回 0 表示这次该静默（已计入被抑制计数）；返回 &gt;0 表示该打一行日志，数值 = 自上一行以来发生的事件数
 * （含本次），日志里带上它，运维才知道「静默」期间实际发生了多少次。
 * gz
 */
public final class LogThrottle {

    private static final long UNSET = Long.MIN_VALUE / 2;

    private final long intervalMs;
    private final LongSupplier clockMs;
    private final AtomicLong lastEmitAt = new AtomicLong(UNSET);
    private final AtomicLong suppressed = new AtomicLong();

    public LogThrottle(long intervalMs) {
        this(intervalMs, System::currentTimeMillis);
    }

    public LogThrottle(long intervalMs, LongSupplier clockMs) {
        this.intervalMs = intervalMs;
        this.clockMs = clockMs;
    }

    /** @return 0 = 本次静默；&gt;0 = 该打日志，值为自上次输出以来的事件数（含本次）。 */
    public long tryEmit() {
        long now = clockMs.getAsLong();
        long last = lastEmitAt.get();
        if (now - last >= intervalMs && lastEmitAt.compareAndSet(last, now)) {
            return suppressed.getAndSet(0) + 1;
        }
        suppressed.incrementAndGet();
        return 0;
    }

    /**
     * 按 key 独立节流（例如「每个 agent 每 10 分钟一行」）。key 数量有上限，满了整体清空——
     * key 可能来自未认证请求的头（伪造 agent id），不能让它无限涨内存。
     */
    public static final class Keyed {

        private final long intervalMs;
        private final int maxKeys;
        private final LongSupplier clockMs;
        private final ConcurrentHashMap<String, LogThrottle> throttles = new ConcurrentHashMap<>();

        public Keyed(long intervalMs, int maxKeys) {
            this(intervalMs, maxKeys, System::currentTimeMillis);
        }

        public Keyed(long intervalMs, int maxKeys, LongSupplier clockMs) {
            this.intervalMs = intervalMs;
            this.maxKeys = maxKeys;
            this.clockMs = clockMs;
        }

        /** 语义同 {@link LogThrottle#tryEmit()}。 */
        public long tryEmit(String key) {
            String k = key == null ? "" : key;
            if (throttles.size() >= maxKeys && !throttles.containsKey(k)) {
                throttles.clear();
            }
            return throttles.computeIfAbsent(k, x -> new LogThrottle(intervalMs, clockMs)).tryEmit();
        }
    }
}
