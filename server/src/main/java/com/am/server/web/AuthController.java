package com.am.server.web;

import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import com.am.server.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 后台登录端点
 *
 * <p>设计取舍：
 * <ul>
 *   <li>POST /login：username + password；交给 AuthenticationManager 走标准 SpringSecurity 校验链；
 *       命中后 把 SecurityContext 写进 HttpSession（{@link HttpSessionSecurityContextRepository#SPRING_SECURITY_CONTEXT_KEY}），
 *       后续请求带 JSESSIONID Cookie 即自动恢复登录态。</li>
 *   <li>GET  /me：从 SecurityContextHolder 拿当前用户；未登录时统一抛 BizException 让全局处理器返回 401</li>
 *   <li>POST /logout：invalidate session + 清 SecurityContext。</li>
 *   <li>密码未配置：登录端点直接报 AUTH_NOT_CONFIGURED，引导运维去填 AIWATCH_PASSWORD。</li>
 * </ul>
 *
 * gz
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthProperties authProperties;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        if (authProperties.getPassword() == null || authProperties.getPassword().isBlank()) {
            log.warn("login attempted but aiwatch.auth.password is not configured");
            throw new BizException(ErrorCode.PARAM_INVALID, "AUTH_NOT_CONFIGURED: contact ops");
        }
        if (req == null || req.getUsername() == null || req.getPassword() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "username & password required");
        }
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword())
            );
            // 把认证结果显式写进 HttpSession，刷新页面 / 后续请求才能复用
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            HttpSession session = http.getSession(true);
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    ctx);
            log.info("admin login success: {} sessionId={}", auth.getName(), session.getId());
            return R.ok(Map.of("username", auth.getName()));
        } catch (BadCredentialsException e) {
            // 不区分"用户名错"还是"密码错"，统一 BAD_CREDENTIALS，避免账号枚举
            throw new BizException(ErrorCode.PARAM_INVALID, "BAD_CREDENTIALS");
        }
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest req, HttpServletResponse resp) {
        HttpSession s = req.getSession(false);
        if (s != null) {
            s.invalidate();
        }
        SecurityContextHolder.clearContext();
        return R.ok();
    }

    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getPrincipal())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "AUTH_REQUIRED");
        }
        return R.ok(Map.of("username", a.getName()));
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }
}
