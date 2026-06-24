package com.am.server.web.security;

import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 管理控制台访问 IP 白名单（默认关）。
 *
 * <p>{@code console.ip_allowlist}（sys_config，逗号分隔）非空时，仅白名单来源可访问
 * {@code /console/**}、{@code /api/v1/admin/**}、{@code /api/v1/dashboard/**}；其余 403。
 * 留空=放行所有（向后兼容，不锁死任何人）。公开面（落地页 /、/install、/api/v1/agent、
 * /api/v1/install/status、登录接口）始终不受限。
 *
 * <p>支持精确 IP 与前缀匹配（如 {@code 10.0.} 匹配 10.0.x.x）。反代部署时取
 * {@code X-Forwarded-For} 首跳 / {@code X-Real-IP}。
 * gz
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class ConsoleAccessFilter extends OncePerRequestFilter {

    private final SystemConfigService configService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isConsolePath(request.getRequestURI())) {
            String allow = configService.getString(SystemConfigKeys.CONSOLE_IP_ALLOWLIST, "");
            if (allow != null && !allow.isBlank() && !isAllowed(clientIp(request), allow)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "console access restricted by IP allowlist");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isConsolePath(String uri) {
        return uri.startsWith("/console")
                || uri.startsWith("/api/v1/admin/")
                || uri.startsWith("/api/v1/dashboard/");
    }

    private static boolean isAllowed(String ip, String allowCsv) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        for (String raw : allowCsv.split(",")) {
            String e = raw.trim();
            if (e.isEmpty()) {
                continue;
            }
            if (ip.equals(e) || ip.startsWith(e)) {
                return true;
            }
        }
        return false;
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return req.getRemoteAddr();
    }
}
