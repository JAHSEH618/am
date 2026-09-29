package com.am.server.agent.security;

import com.am.server.Application;
import com.am.server.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 就绪门在真实 Spring Boot 上下文里的行为：
 * <ul>
 *   <li>{@code @SpringBootTest} 走完整的 {@code SpringApplication.run}（含所有 ApplicationRunner），
 *       所以启动完成后 readiness 一定是 ACCEPTING_TRAFFIC——既有测试不会被就绪门打成 503；</li>
 *   <li>启动期（readiness ≠ ACCEPTING_TRAFFIC）agent 上报入口 503 + 50301，控制台接口不受影响；</li>
 *   <li>readiness 恢复后立即放行（不需要重启 / 刷新缓存）。</li>
 * </ul>
 * 需要真实 MySQL（与其它 @SpringBootTest 一致）。改动 readiness 的用例必须在 finally 里恢复，
 * 因为测试上下文会被后续测试类复用。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AgentIngestReadinessGateSpringTest {

    @Autowired
    private ApplicationAvailability availability;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void readinessIsAcceptingTrafficAfterTheContextHasStarted() {
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void whenReadyAnUnsignedReportReachesTheSignatureFilterNotTheGate() throws Exception {
        // 就绪时：缺签名头 → 验签 filter 的 HTTP 200 + 10001（说明请求穿过了舱壁与就绪门）
        mockMvc.perform(post("/api/v1/agent/report").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_SIGNATURE));
    }

    @Test
    void whileNotReadyAgentIngestIs503WithRetryAfterButConsoleEndpointsAreUntouched() throws Exception {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try {
            assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);

            for (String path : new String[]{"/api/v1/agent/report", "/api/v1/agent/report-commits",
                    "/api/v1/agent/register"}) {
                mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))   // 轻请求也拦
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().exists("Retry-After"))
                        .andExpect(jsonPath("$.code").value(ErrorCode.SERVER_BUSY));
            }

            // 控制台 / 健康检查不受影响（未登录 → 401 是 Security 的事，但绝不是就绪门的 503）
            int consoleStatus = mockMvc.perform(get("/api/v1/dashboard/overview")).andReturn().getResponse().getStatus();
            assertThat(consoleStatus).isNotEqualTo(503);
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }

        mockMvc.perform(post("/api/v1/agent/report").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_SIGNATURE));
    }

    @Test
    void nonPostAndUnknownAgentPathsAreRejectedByTheSignatureFilterBeforeAnyBodyRead() throws Exception {
        mockMvc.perform(get("/api/v1/agent/report"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
        mockMvc.perform(post("/api/v1/agent/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
