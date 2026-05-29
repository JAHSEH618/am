package com.am.server.system.web;

import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import com.am.server.system.auth.AuthConfigSyncer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统设置 - 鉴权与安全（{@code /api/v1/admin/auth-config}）。
 *
 * <ul>
 *   <li><b>GET</b>：返回当前 sys_config 中 auth.* 的明文（admin 看自己的密码 / token，方便核对）。</li>
 *   <li><b>PUT</b>：批量更新；用户名 / 密码 / admin token 任意组合，未传字段保持不变。</li>
 *   <li><b>POST /rotate-token</b>：服务端生成一个 32 字符随机串作为新 admin token，立即生效并返回明文（仅此一次）。</li>
 * </ul>
 *
 * <p>所有修改都会触发 {@link com.am.server.system.SystemConfigChangedEvent}，
 * {@link AuthConfigSyncer} 监听并刷新内存字段；
 * {@link com.am.server.web.security.AdminTokenInterceptor} 每次请求都现场读新 token，
 * {@link com.am.server.config.SecurityConfig} 的 UserDetailsService 每次登录都现场读账号密码——
 * 全链路热生效，无需重启。
 *
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/auth-config")
public class AuthConfigAdminController {

    private static final Logger log = LoggerFactory.getLogger(AuthConfigAdminController.class);

    private final SystemConfigService configService;

    @GetMapping
    public R<Map<String, Object>> get() {
        // 走 admin 鉴权后的明文回显——和 InsightConfig 一样的策略：admin 看自己配置
        return R.ok(configService.getPlain(SystemConfigKeys.CAT_AUTH));
    }

    @PutMapping
    public R<Map<String, Object>> save(@RequestBody Map<String, String> body,
                                       HttpServletRequest http,
                                       Principal principal) {
        if (body == null || body.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "body 不能为空");
        }
        // 仅接受 auth.* 命名空间，防止 PUT 端口扩散到无关分类
        Map<String, String> updates = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : body.entrySet()) {
            String key = e.getKey();
            if (key == null) continue;
            if (key.startsWith(SystemConfigKeys.CAT_AUTH + ".")) {
                updates.put(key, e.getValue() == null ? "" : e.getValue());
            }
        }
        if (updates.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "body 中没有可保存的 auth.* key");
        }

        // 简单合法性：username 不能空白（admin 自己锁死自己）
        if (updates.containsKey(SystemConfigKeys.AUTH_USERNAME)) {
            String u = updates.get(SystemConfigKeys.AUTH_USERNAME);
            if (u == null || u.isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "用户名不能为空");
            }
            updates.put(SystemConfigKeys.AUTH_USERNAME, u.trim());
        }
        if (updates.containsKey(SystemConfigKeys.AUTH_PASSWORD)) {
            String p = updates.get(SystemConfigKeys.AUTH_PASSWORD);
            if (p == null || p.length() < 6) {
                throw new BizException(ErrorCode.PARAM_INVALID, "密码不能少于 6 位");
            }
        }
        if (updates.containsKey(SystemConfigKeys.AUTH_ADMIN_TOKEN)) {
            String t = updates.get(SystemConfigKeys.AUTH_ADMIN_TOKEN);
            // 允许显式置空以"关闭 token 鉴权"——对开发期友好；非空时至少 8 字符防暴力枚举
            if (t != null && !t.isEmpty() && t.trim().length() < 8) {
                throw new BizException(ErrorCode.PARAM_INVALID, "admin token 长度至少 8 字符（生产建议 32+）");
            }
        }

        String operator = resolveOperator(principal, http);
        configService.setBatch(updates, operator);
        log.info("auth-config updated by {} keys={}", operator, updates.keySet());

        return get();
    }

    /** 服务端生成一个高熵 token 并立即写入；返回明文（admin 必须复制保存）。 */
    @PostMapping("/rotate-token")
    public R<Map<String, String>> rotateToken(HttpServletRequest http, Principal principal) {
        String newToken = AuthConfigSyncer.generateRandomToken(32);
        String operator = resolveOperator(principal, http);
        configService.setBatch(Map.of(SystemConfigKeys.AUTH_ADMIN_TOKEN, newToken), operator);
        log.info("admin token rotated by {}", operator);
        return R.ok(Map.of(
                "admin_token", newToken,
                "note", "已立即生效；旧 token 不再可用，自动化脚本请同步更新"));
    }

    private String resolveOperator(Principal principal, HttpServletRequest http) {
        if (principal != null && principal.getName() != null) return principal.getName();
        if (http != null) {
            Object u = http.getSession(false) != null ? http.getSession(false).getAttribute("username") : null;
            if (u != null) return u.toString();
            if (http.getHeader("X-Admin-Token") != null) return "admin-token";
        }
        return "anonymous-admin";
    }
}
