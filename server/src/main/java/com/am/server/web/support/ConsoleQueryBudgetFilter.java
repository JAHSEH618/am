package com.am.server.web.support;

import com.am.server.common.QueryBudget;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 控制台读请求（{@code GET /api/v1/**}）给本线程设 SELECT 执行上限 {@value #MAX_EXECUTION_MS}ms，
 * 见 {@link com.am.server.config.QueryBudgetConfig}。
 *
 * <p>取值略大于前端 axios 15s 超时：用户还等得到的查询不受影响，用户已放弃的查询由 MySQL 及时中止，
 * 不再在后台占着连接把整窗扫描跑完。agent 上报（POST）、SSE、审计详情（LLM 调用，前端特判 120s）除外。
 * gz
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class ConsoleQueryBudgetFilter extends OncePerRequestFilter {

    static final long MAX_EXECUTION_MS = 20_000L;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/v1/")
                || uri.startsWith("/api/v1/agent/")
                || uri.endsWith("/stream")
                || uri.endsWith("/audit");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        QueryBudget.set(MAX_EXECUTION_MS);
        try {
            chain.doFilter(request, response);
        } finally {
            QueryBudget.clear();
        }
    }
}
