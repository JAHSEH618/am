package com.am.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * aiwatch.auth.* —— 后台单管理员账号
 *
 * <p>设计取舍：
 * <ul>
 *   <li>只有 1 个管理员账号，明文存配置文件——不上 hash 是因为这个 yml 本身就受运维管控，
 *       hash 反而增加密码丢失后无法找回的风险，对小团队不划算。</li>
 *   <li>身份信息塞进 SpringSecurity 的 InMemoryUserDetailsManager，避免自己造 UserDetails 轮子。</li>
 *   <li>明文密码运行期 + {noop} 前缀适配 Spring Security 的 DelegatingPasswordEncoder。</li>
 *   <li>会话用 HttpSession（Cookie），重启会踢人——管理员 1-3 人，已对齐用户的接受。</li>
 * </ul>
 *
 * gz
 */
@Data
@Component
@ConfigurationProperties(prefix = "aiwatch.auth")
public class AuthProperties {

    /** 管理员用户名，默认 admin */
    private String username = "admin";

    /**
     * 管理员密码（明文）。生产 必须 通过环境变量 {@code AIWATCH_PASSWORD} 覆盖；
     * 空串视为未配置，所有 /api 业务接口直接 401，避免空密码可登的事故。
     */
    private String password = "";
}
