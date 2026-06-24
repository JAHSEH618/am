package com.am.server.config;

import com.am.server.common.R;
import com.am.server.system.auth.AuthConfigSyncer;
import com.am.server.web.security.AdminTokenAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SpringSecurity 配置 —— 单管理员账号 + Session
 *
 * <ul>
 *   <li>非 API 路径（SPA 路由 /、/dashboard 等）：放行，让前端 React Router 接管</li>
 *   <li>白名单 API：登录端点 / agent 通道 / 安装包 / X-Admin-Token / 健康检查</li>
 *   <li>其它 /api/** ：必须 SecurityContextHolder 里有已认证身份 —— 由 {@link com.am.server.web.AuthController#login} 写进 session</li>
 *   <li>未登录访问受保护接口：401 + 业务包装格式 {code:40100,message:AUTH_REQUIRED}</li>
 * </ul>
 *
 * <p>会话机制：HttpSession（默认 Cookie {@code JSESSIONID}），过期由 {@code server.servlet.session.timeout} 控制。
 * 重启会踢人；这是与用户对齐的取舍（管理员 1-3 人，重启不频繁）。
 *
 * gz
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;
    private final AuthConfigSyncer authConfigSyncer;

    /**
     * 用 DelegatingPasswordEncoder：yml 里的明文密码自动加 {noop} 前缀走明文比较；
     * 未来要切 bcrypt，把前缀改成 {bcrypt} + hash，无需改代码。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 动态 UserDetailsService：每次登录请求都现场从 {@link AuthProperties} 读取
     * 当前生效的账号 / 密码（值由 {@link com.am.server.system.auth.AuthConfigSyncer} 维护，
     * UI 改完热生效）。这样改密码后 <em>下一次</em> 登录立刻用新密码——
     * 已登录的 Session 不强制踢出（避免误操作把自己锁在外面）。
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return (String username) -> {
            String expectUser = authProperties.getUsername();
            if (expectUser == null || expectUser.isBlank()) expectUser = "admin";
            if (!expectUser.equals(username)) {
                throw new UsernameNotFoundException("user not found: " + username);
            }
            String pwd = authProperties.getPassword();
            if (pwd == null || pwd.isBlank()) {
                // 未配置 / 被管理员清空：构造永远不会匹配的 placeholder，且每次都换，杜绝撞库
                log.warn("auth.password 未配置，后台不可登录。请在系统设置 → 鉴权与安全里设置密码");
                pwd = java.util.UUID.randomUUID().toString();
            }
            return User.withUsername(expectUser)
                    .password("{noop}" + pwd)
                    .roles("ADMIN")
                    .build();
        };
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // 登录端点必须公开
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        // agent 通道：HMAC 签名 + 设备凭证自鉴权（注意单数 /agent/**）
                        .requestMatchers("/api/v1/agent/**").permitAll()
                        // 监控字典：MainLayout 加载时调，未登录态切登录页也会触发
                        .requestMatchers("/api/v1/monitor-targets").permitAll()
                        // 登录页"客户端自助安装"面板要拉这个，未登录态也要 200
                        .requestMatchers("/api/v1/install/**").permitAll()
                        // 安装包 / 脚本：员工初装时还没登录账号
                        .requestMatchers("/install/**").permitAll()
                        // admin 接口：必须已认证。X-Admin-Token 自动化通道由
                        // AdminTokenAuthenticationFilter 在 Spring 层认成 ROLE_ADMIN；
                        // 登录 session 走 UI 通道；二者皆无 → 401。AdminTokenInterceptor 二层兜底。
                        .requestMatchers("/api/v1/admin/**").authenticated()
                        // actuator
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // SPA 静态资源 + 入口；其它前端路由（/dashboard /people 等）不在 /api 下，由 anyRequest 放行
                        .requestMatchers("/", "/index.html", "/favicon.ico",
                                "/assets/**", "/static/**").permitAll()
                        // 受保护：除上述白名单以外的 /api/** 全部要登录
                        .requestMatchers("/api/**").authenticated()
                        // 其它（前端 React Router 路径）：放行让 SPA fallback 到 index.html
                        .anyRequest().permitAll())
                // 防御纵深：合法 X-Admin-Token 在 Spring 层认成 ROLE_ADMIN，使 admin 路径可 authenticated()
                .addFilterBefore(new AdminTokenAuthenticationFilter(authConfigSyncer),
                        UsernamePasswordAuthenticationFilter.class)
                // HSTS：强制浏览器后续走 HTTPS（TLS 仍建议反代终止）
                .headers(h -> h.httpStrictTransportSecurity(hsts ->
                        hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, resp, ex) -> {
                            // 复用业务包装格式 R{code,message,data}；前端 axios 401 拦截器统一处理跳登录
                            resp.setStatus(HttpStatus.UNAUTHORIZED.value());
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(resp.getOutputStream(),
                                    R.fail(40100, "AUTH_REQUIRED: please login"));
                        })
                        .accessDeniedHandler((req, resp, ex) -> resp.setStatus(HttpServletResponse.SC_FORBIDDEN)));
        return http.build();
    }
}
