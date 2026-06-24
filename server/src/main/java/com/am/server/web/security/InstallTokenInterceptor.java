package com.am.server.web.security;

import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * {@code /install/**} 预共享令牌拦截器。
 *
 * <p>{@code install.token}（sys_config）非空时，安装端点（脚本 / 二进制 / manifest）需带
 * {@code ?t=<token>} 查询参数或 {@code X-Install-Token} 头，否则 403——挡住无令牌的随机外人下载整包。
 * 令牌随安装命令由管理员从 UI 一并下发，不破坏「员工未登录也能装」。
 *
 * <p>令牌为空（默认）时不启用，向后兼容既有部署。常量时间比较防 timing 攻击。
 * gz
 */
@Component
@RequiredArgsConstructor
public class InstallTokenInterceptor implements HandlerInterceptor {

    public static final String HEADER_NAME = "X-Install-Token";
    public static final String PARAM_NAME = "t";

    private final SystemConfigService configService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String expected = configService.getString(SystemConfigKeys.INSTALL_TOKEN, "");
        if (expected == null || expected.isEmpty()) {
            return true; // 未启用
        }
        String got = request.getParameter(PARAM_NAME);
        if (got == null || got.isEmpty()) {
            got = request.getHeader(HEADER_NAME);
        }
        if (got != null && !got.isEmpty() && constantTimeEquals(got, expected)) {
            return true;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "install token required");
        return false;
    }

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
