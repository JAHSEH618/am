package com.am.server.system.auth;

import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 启动不变量：prod profile 下拒绝以「空 / 公开默认」鉴权凭据对外启动。
 *
 * <p>背景：{@code AdminTokenInterceptor} 去掉了「token 空=放行」后门，但若 admin token 为空、
 * 或仍是 schema 历史固定默认值 {@code aiwatch-default-admin-token-0000}，公网后台仍等同裸奔。
 * 本守卫在 {@link ApplicationReadyEvent} 命中即抛异常阻止服务对外提供（dev/test 不受限，便于本地调试）。
 *
 * <p>遗留环境若从未轮换默认 token/密码，升级到本版后会被拒启——这是预期止血：
 * 升级前请先在 sys_config 设置强随机 {@code auth.admin_token} 与强 {@code auth.password}。
 * gz
 */
@Component
@RequiredArgsConstructor
public class AuthConfigStartupGuard {

    private static final Logger log = LoggerFactory.getLogger(AuthConfigStartupGuard.class);

    /** 与 schema 历史种子一致的公开默认值（必须被拒）。 */
    static final String DEFAULT_TOKEN = "aiwatch-default-admin-token-0000";
    static final String DEFAULT_PASSWORD = "admin";

    private final Environment environment;
    private final AuthConfigSyncer authConfigSyncer;
    private final SystemConfigService configService;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            return;
        }
        List<String> problems = new ArrayList<>();

        String token = authConfigSyncer.getCurrentAdminToken();
        if (token == null || token.isEmpty()) {
            problems.add("auth.admin_token 为空（admin 鉴权关闭）");
        } else if (DEFAULT_TOKEN.equals(token)) {
            problems.add("auth.admin_token 仍是公开默认值，任何人可凭此进后台");
        }

        String pwd = configService.getString(SystemConfigKeys.AUTH_PASSWORD, "");
        if (pwd != null && pwd.equals(DEFAULT_PASSWORD)) {
            problems.add("auth.password 仍是默认 admin");
        }

        if (!problems.isEmpty()) {
            log.error("CRITICAL 鉴权配置不安全，拒绝以 prod 启动：{}", problems);
            throw new IllegalStateException(
                    "Insecure admin credentials in prod: " + problems
                    + "。请在 sys_config 设置强随机 auth.admin_token 与强 auth.password 后重启。");
        }
        log.info("AuthConfigStartupGuard 通过：prod 鉴权凭据非空且非默认。");
    }
}
