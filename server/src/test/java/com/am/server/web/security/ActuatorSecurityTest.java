package com.am.server.web.security;

import com.am.server.Application;
import com.am.server.system.auth.AuthConfigSyncer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * actuator 暴露面的安全契约（走完整 Spring Security 过滤链）：
 * <ul>
 *   <li>{@code /actuator/health}、{@code /health/liveness}、{@code /health/readiness}、{@code /info}：匿名可访问
 *       （compose healthcheck / 部署脚本 / 探活用）；</li>
 *   <li>{@code /actuator/prometheus} 等其余端点：匿名 401，带合法 {@code X-Admin-Token} 或管理员会话 200；</li>
 *   <li>readiness 随 {@link ReadinessState} 变化（503），liveness 不受影响且不含 db。</li>
 * </ul>
 * 需要本地 MySQL（与其它 @SpringBootTest 一致，见 application-test.yml）。
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
// @SpringBootTest 默认关闭指标导出；prometheus 端点需要真实的 PrometheusMeterRegistry
@AutoConfigureObservability
@ActiveProfiles("test")
class ActuatorSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthConfigSyncer authConfigSyncer;
    @Autowired
    private ApplicationContext context;

    @AfterEach
    void restoreReadiness() {
        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        SecurityContextHolder.clearContext();
    }

    // ---- 匿名可访问：健康探针 ----

    @Test
    void readinessAndLiveness_areAnonymouslyReachable() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void fullHealthAndInfo_stayAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void fullHealth_stillIncludesDbAndDisk_butNotSmtp() throws Exception {
        // /actuator/health 保持"完整健康"：含 db / 磁盘；SMTP 不算（未配 / 不可达都不该让服务显示 DOWN）
        String token = authConfigSyncer.getCurrentAdminToken();
        mockMvc.perform(get("/actuator/health").header(AdminTokenInterceptor.HEADER_NAME, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.diskSpace.status").value("UP"))
                .andExpect(jsonPath("$.components.mail").doesNotExist());
    }

    @Test
    void anonymousHealth_hidesDetails() throws Exception {
        // show-details=when_authorized：匿名只见 status，不泄露 db / 磁盘细节
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    // ---- 探针语义 ----

    @Test
    void readinessGoesOutOfService_whileLivenessStaysUp() throws Exception {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void livenessGroup_doesNotDependOnDatabase() throws Exception {
        // 带 token 才能看到 components：liveness 组里只能有 livenessState，不能有 db / diskSpace，
        // 否则 DB 抖动会被 healthcheck / autoheal 放大成容器重启。
        String token = authConfigSyncer.getCurrentAdminToken();
        mockMvc.perform(get("/actuator/health/liveness").header(AdminTokenInterceptor.HEADER_NAME, token))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("\"db\""))))
                .andExpect(content().string(not(containsString("diskSpace"))));
    }

    // ---- 需要认证：prometheus 及其它 actuator 端点 ----

    @Test
    void prometheus_anonymous_isRejected() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheus_wrongToken_isRejected() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").header(AdminTokenInterceptor.HEADER_NAME, "not-the-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheus_withValidAdminToken_isServed() throws Exception {
        // 先打几个请求，确保 http.server.requests 计时器已存在
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());

        String token = authConfigSyncer.getCurrentAdminToken();
        mockMvc.perform(get("/actuator/prometheus").header(AdminTokenInterceptor.HEADER_NAME, token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("hikaricp_connections")))
                // 公共标签
                .andExpect(content().string(containsString("application=\"aiwatch\"")))
                // http.server.requests 的 SLO 直方图桶（P99 用）
                .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
                .andExpect(content().string(containsString("le=\"2.5\"")));
    }

    @Test
    void prometheus_withAdminSession_isServed() throws Exception {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);

        mockMvc.perform(get("/actuator/prometheus").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void otherActuatorPaths_requireAuth_evenWhenNotExposed() throws Exception {
        // 未暴露 / 未白名单的路径：匿名一律先撞 401，而不是 permitAll 后 404 泄露"这个端点存在与否"
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health/db")).andExpect(status().isUnauthorized());
    }
}
