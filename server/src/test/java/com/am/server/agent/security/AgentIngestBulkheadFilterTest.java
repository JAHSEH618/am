package com.am.server.agent.security;

import com.am.server.common.ErrorCode;
import com.am.server.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgentIngestBulkheadFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentIngestBulkheadFilter filter(int maxConcurrency) {
        AgentProperties props = new AgentProperties();
        props.setIngestMaxConcurrency(maxConcurrency);
        props.setIngestAcquireTimeoutMs(50);
        return new AgentIngestBulkheadFilter(props, objectMapper);
    }

    private static MockHttpServletRequest report(String path, int bodyBytes) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setContent(new byte[bodyBytes]);
        return req;
    }

    @Test
    void saturatedHeavyReportGets503WithoutTouchingChain() throws Exception {
        AgentIngestBulkheadFilter filter = filter(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FilterChain blocking = (req, resp) -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024),
                        new MockHttpServletResponse(), blocking);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_COMMITS_PATH, 64 * 1024), resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(503);
        assertThat(resp.getHeader("Retry-After")).isNotBlank();
        assertThat(objectMapper.readTree(resp.getContentAsString()).get("code").asInt())
                .isEqualTo(ErrorCode.SERVER_BUSY);

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        assertThat(filter.availablePermits()).as("permit released after chain completes").isEqualTo(1);
    }

    @Test
    void heartbeatSizedReportBypassesBulkheadEvenWhenSaturated() throws Exception {
        AgentIngestBulkheadFilter filter = filter(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> heavy = CompletableFuture.runAsync(() -> {
            try {
                filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024),
                        new MockHttpServletResponse(), (req, resp) -> {
                            entered.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 512), resp, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(200);
        release.countDown();
        heavy.get(5, TimeUnit.SECONDS);
    }

    @Test
    void otherPathsAndMethodsAreNotFiltered() throws Exception {
        AgentIngestBulkheadFilter filter = filter(1);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(report("/api/v1/agent/register", 64 * 1024), new MockHttpServletResponse(), chain);
        MockHttpServletRequest get = new MockHttpServletRequest("GET", AgentIngestBulkheadFilter.REPORT_PATH);
        filter.doFilter(get, new MockHttpServletResponse(), chain);
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    void unknownLengthCountsAsHeavy() {
        assertThat(AgentIngestBulkheadFilter.isLight(-1)).isFalse();
        assertThat(AgentIngestBulkheadFilter.isLight(0)).isTrue();
        assertThat(AgentIngestBulkheadFilter.isLight(AgentIngestBulkheadFilter.LIGHT_BODY_BYTES)).isTrue();
        assertThat(AgentIngestBulkheadFilter.isLight(AgentIngestBulkheadFilter.LIGHT_BODY_BYTES + 1)).isFalse();
    }
}
