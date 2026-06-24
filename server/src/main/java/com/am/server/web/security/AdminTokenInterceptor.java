package com.am.server.web.security;

import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.system.auth.AuthConfigSyncer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * /api/v1/admin/** 端点的鉴权拦截器，支持两条放行通道：
 * <ol>
 *   <li><b>X-Admin-Token 头</b>（自动化脚本 / curl 用）：期望值由 {@link AuthConfigSyncer}
 *       从 sys_config 动态加载，UI 上轮换后下一次请求即生效，无需重启服务。
 *       首次启动时 AuthConfigSyncer 会 seed 一个 32 字符随机 token。</li>
 *   <li><b>已登录后台 session</b>（v2.9 新增，前端 UI 按钮用）：浏览器以 admin 账号登录后台后,
 *       SecurityContextHolder 里有非 Anonymous 的 Authentication → 直接放行，前端就不用塞
 *       admin token 也不用泄漏给浏览器端。</li>
 * </ol>
 *
 * <p>两条通道任一命中即放行；都没命中抛 {@link BizException}(10101)。token 配置为空串时
 * 整体降级为"鉴权关闭"，仅打 WARN，便于本地调试。常量时间比较防止 timing 攻击。
 *
 * gz
 */
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenInterceptor.class);

    public static final String HEADER_NAME = "X-Admin-Token";

    private final AuthConfigSyncer authConfigSyncer;

    public AdminTokenInterceptor(AuthConfigSyncer authConfigSyncer) {
        this.authConfigSyncer = authConfigSyncer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 每次请求都从 AuthConfigSyncer 读最新值（volatile field，O(1) 读取，无锁开销）
        String expectedToken = authConfigSyncer.getCurrentAdminToken();
        String got = request.getHeader(HEADER_NAME);

        // 通道 1：X-Admin-Token 头（自动化脚本 / curl）。
        // fail-closed：token 未配置（空）时本通道直接不可用，绝不再「空=放行」。
        if (expectedToken != null && !expectedToken.isEmpty()
                && got != null && !got.isEmpty() && constantTimeEquals(got, expectedToken)) {
            return true;
        }

        // 通道 2：已登录后台 session（前端 UI 按钮，不需要塞 token）。
        // 即便 token 配置为空，登录态用户仍可访问；其余一律拒绝。
        if (isAuthenticatedAdminSession()) {
            return true;
        }

        log.warn("admin auth failed: uri={} token_present={} token_configured={} session_admin=false",
                request.getRequestURI(), got != null,
                expectedToken != null && !expectedToken.isEmpty());
        throw new BizException(ErrorCode.ADMIN_UNAUTHORIZED, "admin token missing or invalid");
    }

    /**
     * 判定当前请求是否带"已登录的后台管理员"身份。
     *
     * <p>SecurityConfig 把 /api/v1/admin/** 配为 permitAll —— 但 SecurityContextPersistenceFilter
     * 仍然会把 session 里的 SecurityContext 灌进 ThreadLocal，所以这里能正常拿到登录态。
     * permitAll 只表示"不强制要求认证"，不影响 SecurityContext 的填充。
     */
    private boolean isAuthenticatedAdminSession() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }

    /** 常量时间字节比较，避免基于早退优化的 timing leak */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes();
        byte[] y = b.getBytes();
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }
}
