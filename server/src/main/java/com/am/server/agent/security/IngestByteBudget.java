package com.am.server.agent.security;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 在途「重」上报的堆占用预算（{@link AgentIngestBulkheadFilter} 持有，{@link CachedBodyHttpServletRequest} 追加）。
 *
 * <p>为什么要按字节而不只按并发数限：HMAC 要求整包进内存，gzip 请求还要再持一份解压副本，再加上 Jackson
 * 反序列化出的对象图——单个请求的堆占用与 body 大小成正比，而 16 个并发 × 「最大单请求」会超过 {@code -Xmx2g}。
 * 并发数只能限住「个数」，限不住「大小」，所以另设一个总字节预算。
 *
 * <p><b>计价（估算堆占用，不是网络字节）</b>，{@code R} = 线上字节，{@code D} = gzip 解压后字节：
 * <ul>
 *   <li>明文请求：{@code R}（cachedBody，与解码体同一引用）+ {@code 2R}（Jackson 对象图，估 2 倍）= <b>3R</b></li>
 *   <li>gzip 请求：{@code R}（cachedBody）+ {@code D}（解压副本）+ {@code 2D}（对象图）= <b>R + 3D</b></li>
 * </ul>
 * 进入时只知道 Content-Length，明文按 3×CL 预占，gzip 只预占 CL（{@code D} 未知）；读完 body 用实际字节数校正；
 * gzip 解压时随输出增长逐块追加 {@code 3 × 已解压字节}——预占不到即中止解压并 503。这样预算里记的永远是
 * 「当前已经/即将占住的堆」，而不是一个事后才知道的数。
 *
 * <p>{@link Lease} 归一个请求所有，{@link Lease#close()} 幂等，必须在 finally 里调用。
 * gz
 */
public final class IngestByteBudget {

    /** Jackson 对象图相对 JSON 字节的估算倍数（含 ingest 过程中的拷贝 / flatten），1 份 byte[] + 2 份对象图 = 3。 */
    public static final int HEAP_FACTOR = 3;

    private final long capacity;
    private final AtomicLong inflight = new AtomicLong();

    public IngestByteBudget(long capacity) {
        this.capacity = Math.max(1L, capacity);
    }

    public long capacity() {
        return capacity;
    }

    /** 当前已预占的估算堆字节（gauge {@code aiwatch.agent.ingest.inflight.bytes}）。 */
    public long inflightBytes() {
        return inflight.get();
    }

    /** @return 预占成功的租约；预算不足返回 {@code null}。 */
    public Lease tryOpen(long bytes) {
        long n = Math.max(0L, bytes);
        if (!reserve(n)) {
            return null;
        }
        return new Lease(n);
    }

    /** 请求进入时的预占额：明文 3×，gzip 仅线上字节（解压量未知，之后逐块追加）。 */
    public static long entryCharge(long rawBytes, boolean gzip) {
        return gzip ? rawBytes : rawBytes * HEAP_FACTOR;
    }

    private boolean reserve(long n) {
        for (;;) {
            long cur = inflight.get();
            if (cur + n > capacity) {
                return false;
            }
            if (inflight.compareAndSet(cur, cur + n)) {
                return true;
            }
        }
    }

    private void release(long n) {
        if (n > 0) {
            inflight.addAndGet(-n);
        }
    }

    /** 一个请求持有的预占额。 */
    public final class Lease {

        private long held;
        private boolean closed;

        private Lease(long held) {
            this.held = held;
        }

        /**
         * 把本请求的预占总额调整为 {@code newTotal}：变小则归还差额（必成功），变大则追加差额（预算不足返回 false，
         * 此时额度不变，调用方应中止并 503）。
         */
        public synchronized boolean tryResize(long newTotal) {
            if (closed) {
                return false;
            }
            if (newTotal <= held) {
                release(held - newTotal);
                held = newTotal;
                return true;
            }
            if (!reserve(newTotal - held)) {
                return false;
            }
            held = newTotal;
            return true;
        }

        public synchronized long held() {
            return held;
        }

        /** 归还全部额度；幂等。 */
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            release(held);
            held = 0;
        }
    }
}
