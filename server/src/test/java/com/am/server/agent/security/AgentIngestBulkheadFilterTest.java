package com.am.server.agent.security;

import com.am.server.agent.security.AgentFilterTestSupport.TrackingRequest;
import com.am.server.common.ErrorCode;
import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentIngestBulkheadFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentProperties props(int maxConcurrency) {
        AgentProperties props = new AgentProperties();
        props.setIngestMaxConcurrency(maxConcurrency);
        return props;
    }

    private AgentIngestBulkheadFilter filter(AgentProperties props) {
        return filter(props, null, noMetrics());
    }

    private AgentIngestBulkheadFilter filter(AgentProperties props, ApplicationAvailability availability,
                                             AgentIngestMetrics metrics) {
        return new AgentIngestBulkheadFilter(props, objectMapper, availability, metrics, null);
    }

    private static AgentIngestMetrics noMetrics() {
        return AgentFilterTestSupport.noMetrics();
    }

    private static MockHttpServletRequest report(String path, int bodyBytes) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setContent(new byte[bodyBytes]);
        return req;
    }

    /** 让一个请求卡在 chain 里，直到 release。返回 (entered, release, future)。 */
    private static final class Blocked {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Object> leaseSeen = new AtomicReference<>();
        CompletableFuture<Void> future;

        static Blocked start(AgentIngestBulkheadFilter filter, MockHttpServletRequest req) throws Exception {
            Blocked b = new Blocked();
            FilterChain blocking = (rq, rs) -> {
                b.leaseSeen.set(rq.getAttribute(AgentIngestBulkheadFilter.ATTR_BYTE_LEASE));
                b.entered.countDown();
                try {
                    b.release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            b.future = CompletableFuture.runAsync(() -> {
                try {
                    filter.doFilter(req, new MockHttpServletResponse(), blocking);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertThat(b.entered.await(5, TimeUnit.SECONDS)).isTrue();
            return b;
        }

        void finish() throws Exception {
            release.countDown();
            future.get(5, TimeUnit.SECONDS);
        }
    }

    private void assertBusy(MockHttpServletResponse resp) throws Exception {
        assertThat(resp.getStatus()).isEqualTo(503);
        assertThat(resp.getHeader("Retry-After")).isNotBlank();
        assertThat(objectMapper.readTree(resp.getContentAsString()).get("code").asInt())
                .isEqualTo(ErrorCode.SERVER_BUSY);
    }

    // ---------------------------------------------------------------- 重名额

    @Test
    void saturatedHeavyReportGets503WithoutTouchingChain() throws Exception {
        AgentIngestBulkheadFilter filter = filter(props(1));
        Blocked first = Blocked.start(filter, report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_COMMITS_PATH, 64 * 1024), resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertBusy(resp);

        first.finish();
        assertThat(filter.availablePermits()).as("permit released after chain completes").isEqualTo(1);
    }

    @Test
    void heavyAcquireDefaultsToNoWaitSoRejectedRequestsDoNotHoldTomcatThreads() {
        assertThat(new AgentProperties().getIngestAcquireTimeoutMs()).isZero();
    }

    @Test
    void rejectedHeavyRequestReturnsImmediatelyWithDefaultZeroWait() throws Exception {
        AgentIngestBulkheadFilter filter = filter(props(1));
        Blocked first = Blocked.start(filter, report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024));

        long start = System.nanoTime();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024), resp, mock(FilterChain.class));
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertBusy(resp);
        assertThat(ms).as("no queueing: a rejected request must not linger").isLessThan(100);
        first.finish();
    }

    @Test
    void permitIsReleasedEvenWhenChainThrows() throws Exception {
        AgentIngestBulkheadFilter filter = filter(props(1));
        FilterChain boom = (rq, rs) -> {
            throw new IllegalStateException("boom");
        };
        try {
            filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024), new MockHttpServletResponse(), boom);
        } catch (Exception expected) {
            // ignore
        }
        assertThat(filter.availablePermits()).isEqualTo(1);
        assertThat(filter.byteBudget().inflightBytes()).as("byte lease returned too").isZero();
    }

    // ---------------------------------------------------------------- 轻请求

    @Test
    void heartbeatSizedReportBypassesHeavyBulkheadEvenWhenSaturated() throws Exception {
        AgentIngestBulkheadFilter filter = filter(props(1));
        Blocked heavy = Blocked.start(filter, report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024));

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 512), resp, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(200);
        heavy.finish();
    }

    @Test
    void lightRequestsHaveTheirOwnSmallSemaphore() throws Exception {
        AgentProperties p = props(4);
        p.setIngestLightMaxConcurrency(1);
        p.setIngestLightAcquireTimeoutMs(50);
        AgentIngestMetrics metrics = noMetrics();
        AgentIngestBulkheadFilter filter = filter(p, null, metrics);

        Blocked light = Blocked.start(filter, report(AgentIngestBulkheadFilter.REPORT_PATH, 300));
        assertThat(light.leaseSeen.get()).as("light requests are not byte-budgeted").isNull();

        long start = System.nanoTime();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 300), resp, chain);
        long ms = (System.nanoTime() - start) / 1_000_000;

        verify(chain, never()).doFilter(any(), any());
        assertBusy(resp);
        assertThat(ms).as("short wait, but not forever").isBetween(30L, 1_000L);
        assertThat(metrics.rejectedCount(AgentIngestMetrics.REASON_LIGHT)).isEqualTo(1);

        // 轻名额满不影响重请求
        FilterChain heavyChain = mock(FilterChain.class);
        MockHttpServletResponse heavyResp = new MockHttpServletResponse();
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024), heavyResp, heavyChain);
        verify(heavyChain).doFilter(any(), any());

        light.finish();
        assertThat(filter.availableLightPermits()).isEqualTo(1);
    }

    @Test
    void lightRequestWaitsBrieflyForAPermit() throws Exception {
        AgentProperties p = props(4);
        p.setIngestLightMaxConcurrency(1);
        p.setIngestLightAcquireTimeoutMs(2_000);
        AgentIngestBulkheadFilter filter = filter(p);

        Blocked light = Blocked.start(filter, report(AgentIngestBulkheadFilter.REPORT_PATH, 300));
        CompletableFuture<MockHttpServletResponse> second = CompletableFuture.supplyAsync(() -> {
            try {
                MockHttpServletResponse resp = new MockHttpServletResponse();
                filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 300), resp, (rq, rs) -> { });
                return resp;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(100);
        light.finish();   // 释放后，等待中的轻请求应拿到名额
        assertThat(second.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- 413

    @Test
    void oversizedContentLengthGets413BeforeAnyPermitOrRead() throws Exception {
        AgentProperties p = props(1);
        p.setMaxBodyBytes(1024);
        AgentIngestMetrics metrics = noMetrics();
        AgentIngestBulkheadFilter filter = filter(p, null, metrics);

        TrackingRequest req = new TrackingRequest("POST", AgentIngestBulkheadFilter.REPORT_PATH, new byte[2048], false);
        req.addHeader(AgentSignatureFilter.HEADER_AGENT_ID, "agent-old");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(resp.getHeader("Connection")).isEqualToIgnoringCase("close");
        assertThat(objectMapper.readTree(resp.getContentAsString()).get("code").asInt())
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(objectMapper.readTree(resp.getContentAsString()).get("message").asText()).contains("1024");
        assertThat(req.bodyTouched).as("body must not be read").isFalse();
        assertThat(filter.availablePermits()).isEqualTo(1);
        assertThat(metrics.rejectedCount(AgentIngestMetrics.REASON_TOO_LARGE)).isEqualTo(1);
    }

    @Test
    void tooLargeLogLooksUpAgentVersionBestEffort() throws Exception {
        AgentProperties p = props(1);
        p.setMaxBodyBytes(10);
        AgentDeviceRepository repo = mock(AgentDeviceRepository.class);
        when(repo.findByAgentId("agent-old")).thenReturn(Optional.of(AgentFilterTestSupport.device("agent-old", "1.2.0")));
        AgentIngestBulkheadFilter filter = new AgentIngestBulkheadFilter(p, objectMapper, (ApplicationAvailability) null,
                noMetrics(), repo);

        MockHttpServletRequest req = report(AgentIngestBulkheadFilter.REPORT_PATH, 100);
        req.addHeader(AgentSignatureFilter.HEADER_AGENT_ID, "agent-old");
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(repo).findByAgentId("agent-old");   // 首条日志才查，且只在被拒时查

        // 库不可用时查版本失败也不能影响 413 本身
        AgentDeviceRepository broken = mock(AgentDeviceRepository.class);
        when(broken.findByAgentId(any())).thenThrow(new IllegalStateException("db down"));
        AgentIngestBulkheadFilter filter2 = new AgentIngestBulkheadFilter(p, objectMapper, (ApplicationAvailability) null,
                noMetrics(), broken);
        MockHttpServletRequest req2 = report(AgentIngestBulkheadFilter.REPORT_PATH, 100);
        req2.addHeader(AgentSignatureFilter.HEADER_AGENT_ID, "agent-x");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        filter2.doFilter(req2, resp2, mock(FilterChain.class));
        assertThat(resp2.getStatus()).isEqualTo(413);
    }

    @Test
    void registerBodyIsCappedTooBecauseTheEndpointIsUnauthenticated() throws Exception {
        AgentProperties p = props(1);
        p.setRegisterMaxBodyBytes(1024);
        AgentIngestBulkheadFilter filter = filter(p);

        MockHttpServletResponse big = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(report("/api/v1/agent/register", 4096), big, chain);
        assertThat(big.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(any(), any());

        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(report("/api/v1/agent/register", 300), ok, chain);
        verify(chain, times(1)).doFilter(any(), any());
        assertThat(ok.getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- 字节预算

    @Test
    void byteBudgetRejectsWhenExhaustedAndIsReturnedAfterwards() throws Exception {
        AgentProperties p = props(8);
        p.setMaxBodyBytes(10_000);
        // 明文 3x 计价：一个 5000 字节的重请求（>2KB，不是轻请求）占 15000；预算 20000 只够一个
        p.setIngestInflightBytesBudget(20_000);
        AgentIngestMetrics metrics = noMetrics();
        AgentIngestBulkheadFilter filter = filter(p, null, metrics);

        Blocked first = Blocked.start(filter, report(AgentIngestBulkheadFilter.REPORT_PATH, 5_000));
        assertThat(first.leaseSeen.get()).isInstanceOf(IngestByteBudget.Lease.class);
        assertThat(filter.byteBudget().inflightBytes()).isEqualTo(15_000);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_COMMITS_PATH, 5_000), resp, chain);
        verify(chain, never()).doFilter(any(), any());
        assertBusy(resp);
        assertThat(metrics.rejectedCount(AgentIngestMetrics.REASON_BYTES_BUDGET)).isEqualTo(1);
        assertThat(filter.availablePermits()).as("a budget rejection must give the heavy permit back").isEqualTo(7);

        first.finish();
        assertThat(filter.byteBudget().inflightBytes()).isZero();

        MockHttpServletResponse again = new MockHttpServletResponse();
        FilterChain chain2 = mock(FilterChain.class);
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_COMMITS_PATH, 5_000), again, chain2);
        verify(chain2).doFilter(any(), any());
    }

    @Test
    void gzipRequestsOnlyReserveWireBytesAtEntry() throws Exception {
        AgentProperties p = props(8);
        p.setIngestInflightBytesBudget(10_000);
        AgentIngestBulkheadFilter filter = filter(p);

        MockHttpServletRequest gz = report(AgentIngestBulkheadFilter.REPORT_PATH, 5_000);
        gz.addHeader("Content-Encoding", "gzip");
        Blocked b = Blocked.start(filter, gz);
        assertThat(filter.byteBudget().inflightBytes()).as("decoded size unknown yet: wire bytes only").isEqualTo(5_000);
        b.finish();
    }

    @Test
    void unknownContentLengthReservesMaxBodyBytes() throws Exception {
        AgentProperties p = props(8);
        p.setMaxBodyBytes(1_000);
        p.setIngestInflightBytesBudget(10_000);
        AgentIngestBulkheadFilter filter = filter(p);

        TrackingRequest chunked = new TrackingRequest("POST", AgentIngestBulkheadFilter.REPORT_PATH, new byte[10], true);
        Blocked b = Blocked.start(filter, chunked);
        assertThat(filter.byteBudget().inflightBytes()).isEqualTo(3_000);
        b.finish();
    }

    // ---------------------------------------------------------------- 就绪门

    @Test
    void notReadyRejectsAllAgentIngestPathsIncludingHeartbeatsAndRegister() throws Exception {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        AgentIngestMetrics metrics = noMetrics();
        AgentIngestBulkheadFilter filter = filter(props(4), availability, metrics);
        FilterChain chain = mock(FilterChain.class);

        for (MockHttpServletRequest req : new MockHttpServletRequest[]{
                report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024),
                report(AgentIngestBulkheadFilter.REPORT_COMMITS_PATH, 64 * 1024),
                report(AgentIngestBulkheadFilter.REPORT_PATH, 300),          // 心跳
                report(AgentIngestBulkheadFilter.REGISTER_PATH, 300)}) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, chain);
            assertBusy(resp);
        }
        verify(chain, never()).doFilter(any(), any());
        assertThat(metrics.rejectedCount(AgentIngestMetrics.REASON_NOT_READY)).isEqualTo(4);

        // 名额没有被吞
        assertThat(filter.availablePermits()).isEqualTo(4);
    }

    @Test
    void acceptingTrafficPassesThroughAndFlipIsPickedUpLive() throws Exception {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        AgentIngestBulkheadFilter filter = filter(props(4), availability,
                noMetrics());
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse starting = new MockHttpServletResponse();
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024), starting, chain);
        assertBusy(starting);

        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        MockHttpServletResponse ready = new MockHttpServletResponse();
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024), ready, chain);
        assertThat(ready.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void missingAvailabilityBeanFailsOpen() throws Exception {
        AgentIngestBulkheadFilter filter = filter(props(4), null,
                noMetrics());
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 64 * 1024), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    // ---------------------------------------------------------------- 指标

    @Test
    void metricsExposeRejectedCountersAndInflightGauges() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentProperties p = props(1);
        p.setMaxBodyBytes(10_000);
        AgentIngestBulkheadFilter filter = filter(p, null, new AgentIngestMetrics(registry));

        Blocked first = Blocked.start(filter, report(AgentIngestBulkheadFilter.REPORT_PATH, 5_000));
        assertThat(registry.get("aiwatch.agent.ingest.inflight").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("aiwatch.agent.ingest.inflight.bytes").gauge().value()).isEqualTo(15_000.0);

        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 5_000), new MockHttpServletResponse(),
                mock(FilterChain.class));                                            // concurrency
        filter.doFilter(report(AgentIngestBulkheadFilter.REPORT_PATH, 20_000), new MockHttpServletResponse(),
                mock(FilterChain.class));                                            // too_large
        assertThat(registry.get("aiwatch.agent.ingest.rejected").tag("reason", "concurrency").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("aiwatch.agent.ingest.rejected").tag("reason", "too_large").counter().count())
                .isEqualTo(1.0);

        first.finish();
        assertThat(registry.get("aiwatch.agent.ingest.inflight").gauge().value()).isZero();
        assertThat(registry.get("aiwatch.agent.ingest.inflight.bytes").gauge().value()).isZero();
    }

    // ---------------------------------------------------------------- 路由

    @Test
    void otherPathsAndMethodsAreNotFiltered() throws Exception {
        AgentProperties p = props(1);
        p.setMaxBodyBytes(10);
        AgentIngestBulkheadFilter filter = filter(p);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(report("/api/v1/agent/other", 64 * 1024), new MockHttpServletResponse(), chain);
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
