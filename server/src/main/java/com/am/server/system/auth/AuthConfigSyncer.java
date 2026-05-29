package com.am.server.system.auth;

import com.am.server.config.AuthProperties;
import com.am.server.system.SystemConfigChangedEvent;
import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Set;

/**
 * 鉴权三件套（用户名 / 密码 / admin token）的运行时配置同步器。
 *
 * <p>设计动机：原来 yml 里写死了 {@code aiwatch.auth.*} 和 {@code aiwatch.admin.token}，
 * 改密码 / 轮换 token 都要重打 jar 或改环境变量重启。v2.10 起这三项纳入 sys_config，
 * UI 改完热生效——下次登录请求 / 下次 admin 接口调用就用新值，无需重启服务。
 *
 * <ul>
 *   <li><b>启动 seed</b>：把 AuthProperties 的代码默认值（dev: admin/123456；prod: 空密码登录被锁死）
 *       灌进 sys_config（仅当 key 缺失）。已经升级过的环境保持 sys_config 现状。</li>
 *   <li><b>启动 load</b>：sys_config 当前值刷回 {@link AuthProperties}，并存入本组件的内存字段
 *       以便 {@link com.am.server.web.security.AdminTokenInterceptor} 无锁读 token。</li>
 *   <li><b>运行时事件</b>：监听 {@link SystemConfigChangedEvent}，相关 key 变更即时刷新；
 *       {@link com.am.server.config.SecurityConfig} 注入的 UserDetailsService 每次登录时
 *       动态读取，所以新密码立刻生效。</li>
 * </ul>
 *
 * <p><b>空密码自锁</b>：sys_config 里 auth.password 为空时，登录端点不会通过——
 * 这是和原 SecurityConfig 一致的"未配置即不可登"安全语义。
 * gz
 */
@Component
@RequiredArgsConstructor
public class AuthConfigSyncer {

    private static final Logger log = LoggerFactory.getLogger(AuthConfigSyncer.class);

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final SystemConfigService configService;
    private final AuthProperties authProperties;

    /**
     * 缓存当前生效的 admin token（AdminTokenInterceptor 直接读这里，无锁、零开销）。
     * volatile 保证更新可见性；空串视为"鉴权关闭"。
     */
    @Getter
    private volatile String currentAdminToken = "";

    @PostConstruct
    public void init() {
        seedDefaults();
        loadFromConfig();
        log.info("AuthConfig synced from sys_config: username={} passwordSet={} adminTokenSet={}",
                authProperties.getUsername(),
                authProperties.getPassword() != null && !authProperties.getPassword().isEmpty(),
                !currentAdminToken.isEmpty());
    }

    /**
     * 首次启动 seed —— 已存在的 key 不会被覆盖。
     *
     * <p>密码默认 {@code admin}（与传统后台一致），管理员上 UI 立即改密——
     * 比"空密码登录被锁死"更友好，避免 prod 首次部署进不去后台。
     * <p>admin token 缺失时生成一个 32 字符随机串作为默认值，避免空 token 鉴权关闭的窗口期；
     * 首次部署需要管理员直接到 sys_config 表里查看初始值（或登录 UI 后到鉴权 Tab 复制）。
     */
    private void seedDefaults() {
        configService.seedIfAbsent(SystemConfigKeys.AUTH_USERNAME,
                nullSafe(authProperties.getUsername(), "admin"),
                "string", SystemConfigKeys.CAT_AUTH, false,
                "后台管理员用户名");

        String pwd = authProperties.getPassword();
        if (pwd == null || pwd.isBlank()) pwd = "admin";
        configService.seedIfAbsent(SystemConfigKeys.AUTH_PASSWORD,
                pwd,
                "string", SystemConfigKeys.CAT_AUTH, true,
                "后台管理员密码（明文存储；UI 上 admin 可见）。首次部署默认 admin，请立即修改");

        configService.seedIfAbsent(SystemConfigKeys.AUTH_ADMIN_TOKEN,
                generateRandomToken(32),
                "string", SystemConfigKeys.CAT_AUTH, true,
                "/api/v1/admin/** 自动化通道的 X-Admin-Token 期望值（敏感字段）");
    }

    /** 反向：sys_config → AuthProperties + currentAdminToken 字段。 */
    private void loadFromConfig() {
        authProperties.setUsername(configService.getString(
                SystemConfigKeys.AUTH_USERNAME, authProperties.getUsername()));
        authProperties.setPassword(configService.getString(
                SystemConfigKeys.AUTH_PASSWORD, authProperties.getPassword()));
        this.currentAdminToken = nullSafe(
                configService.getString(SystemConfigKeys.AUTH_ADMIN_TOKEN, ""), "").trim();
    }

    @EventListener
    public void onConfigChanged(SystemConfigChangedEvent ev) {
        Set<String> keys = ev.getChangedKeys();
        if (keys == null || keys.isEmpty()) return;
        boolean touched = keys.stream().anyMatch(k ->
                k.startsWith(SystemConfigKeys.CAT_AUTH + "."));
        if (touched) {
            loadFromConfig();
            log.info("AuthConfig reloaded after sys_config change (keys={})", keys);
        }
    }

    /**
     * 生成一个 length 字符的小写字母数字 token。用于首次启动 seed 默认值，也用于"轮换 token"按钮。
     * 字符集 36 个，length=32 时熵 ≈ 165 bit，对于 admin token 充分。
     */
    public static String generateRandomToken(int length) {
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            buf[i] = TOKEN_ALPHABET[RNG.nextInt(TOKEN_ALPHABET.length)];
        }
        return new String(buf);
    }

    private static String nullSafe(String s, String fallback) {
        return s == null ? fallback : s;
    }
}
