package com.am.server.web.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * API 耗时测量（性能治理 §5.3「测量先行」）：/api/v1/** 耗时 &gt; {@value #SLOW_THRESHOLD_MS}ms 的请求
 * 记 WARN 日志 + 入 {@link SlowApiRecorder} 清单。SSE 长连接（/stream）除外。
 * <p>验收口径提醒：前端 axios 全局 15s 超时，任何接口不得触发（audit 已特判 120s）；
 * 本清单是「30 天窗口 P95 ≤ 3s」实测的数据来源。
 * gz
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@RequiredArgsConstructor
public class ApiTimingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTimingFilter.class);

    private static final long SLOW_THRESHOLD_MS = 1000;

    private final SlowApiRecorder slowApiRecorder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/v1/") || uri.endsWith("/stream");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms > SLOW_THRESHOLD_MS && !request.isAsyncStarted()) {
                String path = request.getRequestURI();
                String query = request.getQueryString();
                log.warn("slow api: {} {}{} took {}ms status={}",
                        request.getMethod(), path, query == null ? "" : "?" + query,
                        ms, response.getStatus());
                slowApiRecorder.record(new SlowApiRecorder.SlowApiEntry(
                        LocalDateTime.now(), request.getMethod(), path, query, ms, response.getStatus()));
            }
        }
    }
}
