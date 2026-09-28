package com.am.server.agent.security;

import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import com.am.server.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * agent 上报舱壁：限制同时处理中的「重」上报数，超额请求在读 body、查库之前直接回 503。
 *
 * <p>为什么需要：{@code /report} 是唯一由客户端数量决定并发的入口，每个会话一个短事务，
 * 但全员同时上报（尤其服务重启后每台 agent 的 outbox 一起补发）时，50 个 Tomcat 线程会把
 * 40 条 Hikari 连接全部占满——连 {@link AgentSignatureFilter} 的 {@code findByAgentId} 都等
 * 30s 超时，客户端失败后又把 body 落 outbox、下个 tick 补发，压力只增不减。这里把重上报压到
 * {@link AgentProperties#getIngestMaxConcurrency()} 以内，剩余连接留给控制台、定时任务与轻量心跳。
 *
 * <p>顺序：在 Spring Security 与 {@link AgentSignatureFilter} 之前执行，拒绝时 body 尚未读入内存
 * （{@link CachedBodyHttpServletRequest} 会整包缓存），所以也顺带给堆设了上限。
 *
 * <p>轻量通道：Content-Length 已知且不超过 {@value #LIGHT_BODY_BYTES} 字节的请求（设备心跳、
 * 空闲 tick）不占名额——它们只刷 {@code last_seen}，被挡掉会让大盘误判离线。
 *
 * <p>客户端契约：HTTP 503 + {@link ErrorCode#SERVER_BUSY}。1.3.3+ agent 识别后不落 outbox
 * （游标未推进，下个 tick 自然重报）；老 agent 当普通失败处理，同样不丢数据。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AgentIngestBulkheadFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentIngestBulkheadFilter.class);

    static final String REPORT_PATH = "/api/v1/agent/report";
    static final String REPORT_COMMITS_PATH = "/api/v1/agent/report-commits";

    /**
     * 轻量通道阈值：设备心跳约 300~600 字节（<1KB 不压缩）；带几个会话的上报 gzip 后通常已过 2KB。
     * 阈值过宽会让老 agent 的小批量上报绕过舱壁。
     */
    static final long LIGHT_BODY_BYTES = 2 * 1024;

    /** 客户端退避提示（秒）；新 agent 以自己的节奏重报，此头主要给代理 / 运维看。 */
    private static final String RETRY_AFTER_SECONDS = "30";

    /** 拒绝日志节流：满载时每个请求都打 WARN 会把日志打爆。 */
    private static final long REJECT_LOG_INTERVAL_MS = 30_000L;

    private final Semaphore permits;
    private final int maxConcurrency;
    private final long acquireTimeoutMs;
    private final ObjectMapper objectMapper;

    private final AtomicLong rejectedSinceLastLog = new AtomicLong();
    private final AtomicLong lastRejectLogAt = new AtomicLong();

    public AgentIngestBulkheadFilter(AgentProperties agentProperties, ObjectMapper objectMapper) {
        this.maxConcurrency = Math.max(1, agentProperties.getIngestMaxConcurrency());
        this.acquireTimeoutMs = Math.max(0L, agentProperties.getIngestAcquireTimeoutMs());
        this.permits = new Semaphore(maxConcurrency, true);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (!REPORT_PATH.equals(path) && !REPORT_COMMITS_PATH.equals(path)) {
            return true;
        }
        return isLight(request.getContentLengthLong());
    }

    /** 未知长度（-1，chunked）按重请求处理。 */
    static boolean isLight(long contentLength) {
        return contentLength >= 0 && contentLength <= LIGHT_BODY_BYTES;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            reject(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        rejectedSinceLastLog.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastRejectLogAt.get();
        if (now - last >= REJECT_LOG_INTERVAL_MS && lastRejectLogAt.compareAndSet(last, now)) {
            long rejected = rejectedSinceLastLog.getAndSet(0);
            log.warn("agent ingest saturated: rejected {} report(s) in last {}s (max_concurrency={}, latest {} agent={} bytes={})",
                    rejected, REJECT_LOG_INTERVAL_MS / 1000, maxConcurrency, request.getRequestURI(),
                    request.getHeader(AgentSignatureFilter.HEADER_AGENT_ID), request.getContentLengthLong());
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", RETRY_AFTER_SECONDS);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                R.fail(ErrorCode.SERVER_BUSY, "agent ingest busy, retry next tick"));
    }

    /** 观测用：当前空闲名额。 */
    int availablePermits() {
        return permits.availablePermits();
    }
}
