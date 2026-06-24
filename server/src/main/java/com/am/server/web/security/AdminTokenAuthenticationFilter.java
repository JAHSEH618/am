package com.am.server.web.security;

import com.am.server.system.auth.AuthConfigSyncer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 防御纵深：把合法 {@code X-Admin-Token} 请求在 Spring Security 层认成 {@code ROLE_ADMIN}，
 * 使 {@link com.am.server.config.SecurityConfig} 能把 {@code /api/v1/admin/**} 配成
 * {@code authenticated()} 而不破坏自动化（curl / 脚本）通道。
 *
 * <p>仅在当前无认证态时才尝试（已登录 session 用户保持其原身份）。token 配置为空时不认（fail-closed）。
 *
 * <p><b>不是 @Component</b>：只由 {@link com.am.server.config.SecurityConfig} 在安全过滤链内
 * {@code addFilterBefore} 注册（须在 SecurityContextHolderFilter 之后），避免被 Spring Boot
 * 同时自动注册进 servlet 链导致认证态被安全链起点清掉。
 * gz
 */
public class AdminTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AuthConfigSyncer authConfigSyncer;

    public AdminTokenAuthenticationFilter(AuthConfigSyncer authConfigSyncer) {
        this.authConfigSyncer = authConfigSyncer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/v1/admin/")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String expected = authConfigSyncer.getCurrentAdminToken();
            String got = request.getHeader(AdminTokenInterceptor.HEADER_NAME);
            if (expected != null && !expected.isEmpty() && got != null && got.equals(expected)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        "admin-token", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
