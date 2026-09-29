package com.am.server.agent.security;

import com.am.server.agent.security.AgentFilterTestSupport.TrackingRequest;
import com.am.server.common.ErrorCode;
import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentAlert;
import com.am.server.domain.agent.AgentAlertRepository;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.domain.agent.AlertType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.am.server.agent.security.AgentFilterTestSupport.device;
import static com.am.server.agent.security.AgentFilterTestSupport.gzip;
import static com.am.server.agent.security.AgentFilterTestSupport.json;
import static com.am.server.agent.security.AgentFilterTestSupport.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSignatureFilterTest {

    private static final String REPORT = AgentIngestBulkheadFilter.REPORT_PATH;

    private final AgentDeviceRepository deviceRepository = mock(AgentDeviceRepository.class);
    private final NonceStoreService nonceStoreService = mock(NonceStoreService.class);
    private final AlertService alertService = mock(AlertService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentProperties props = new AgentProperties();
    private final AgentIngestMetrics metrics = AgentFilterTestSupport.noMetrics();

    private AgentSignatureFilter filter() {
        return filter(props);
    }

    private AgentSignatureFilter filter(AgentProperties p) {
        return new AgentSignatureFilter(deviceRepository, nonceStoreService, alertService, objectMapper, p,
                new AgentIngestGuard(p), metrics);
    }

    private void agentExists(String agentId, String version) {
        when(deviceRepository.findByAgentId(agentId)).thenReturn(Optional.of(device(agentId, version)));
        when(nonceStoreService.tryClaim(anyString(), anyString(), anyString())).thenReturn(true);
    }

    private JsonNode body(MockHttpServletResponse resp) throws Exception {
        return objectMapper.readTree(resp.getContentAsString());
    }

    private void assertBusy(MockHttpServletResponse resp) throws Exception {
        assertThat(resp.getStatus()).isEqualTo(503);
        assertThat(resp.getHeader("Retry-After")).isNotBlank();
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.SERVER_BUSY);
    }

    // ---------------------------------------------------------------- 既有语义

    @Test
    void badSignatureMustNotClaimNonce() throws Exception {
        AgentDevice device = new AgentDevice();
        device.setAgentId("agent-1");
        device.setStatus(AgentDevice.STATUS_ACTIVE);
        device.setAgentSecret("secret-xyz");
        device.setUserCode("U001");
        device.setHostHash("hh");
        when(deviceRepository.findByAgentId("agent-1")).thenReturn(Optional.of(device));
        // 即便 nonce 本可占用,坏签名也不应走到这一步
        when(nonceStoreService.tryClaim(anyString(), anyString(), anyString())).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/agent/report");
        req.setContent("{\"hello\":1}".getBytes(StandardCharsets.UTF_8));
        req.addHeader(AgentSignatureFilter.HEADER_AGENT_ID, "agent-1");
        req.addHeader(AgentSignatureFilter.HEADER_TS, String.valueOf(System.currentTimeMillis()));
        req.addHeader(AgentSignatureFilter.HEADER_NONCE, "nonce-1");
        req.addHeader(AgentSignatureFilter.HEADER_SIGN, "deadbeef"); // 故意错签名
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        verify(nonceStoreService, never()).tryClaim(anyString(), anyString(), anyString());
        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).as("鉴权类失败仍是 HTTP 200 + 业务码").isEqualTo(200);
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.INVALID_SIGNATURE);
        verify(alertService).error(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void validSignatureReachesControllerWithDecodedGzipBodyAndContext() throws Exception {
        agentExists("agent-1", "1.3.3");
        byte[] plain = json(4096);
        TrackingRequest req = signed(REPORT, "agent-1", gzip(plain), "n-1", true);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<byte[]> seen = new AtomicReference<>();
        AtomicReference<Object> ctx = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> {
            seen.set(rq.getInputStream().readAllBytes());
            ctx.set(rq.getAttribute(SignatureContext.ATTR));
        };

        filter().doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(plain);
        assertThat(ctx.get()).isInstanceOfSatisfying(SignatureContext.class,
                c -> assertThat(c.getAgentId()).isEqualTo("agent-1"));
        verify(nonceStoreService).tryClaim("agent-1", "n-1", req.getHeader(AgentSignatureFilter.HEADER_TS));
    }

    // ---------------------------------------------------------------- 提前拒绝：读 body 之前

    @Test
    void nonPostIsRejectedBeforeBodyIsReadOrDbTouched() throws Exception {
        TrackingRequest req = new TrackingRequest("GET", REPORT, json(1024), false);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(405);
        assertThat(resp.getHeader("Allow")).isEqualTo("POST");
        assertThat(req.bodyTouched).isFalse();
        verify(deviceRepository, never()).findByAgentId(anyString());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void unknownAgentPathIsRejectedBeforeBodyIsRead() throws Exception {
        TrackingRequest req = new TrackingRequest("POST", "/api/v1/agent/does-not-exist", json(1024), false);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(404);
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(req.bodyTouched).isFalse();
        verify(deviceRepository, never()).findByAgentId(anyString());
    }

    @Test
    void registerAndForeignPathsAreNotFilteredAtAll() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        AgentSignatureFilter f = filter();
        f.doFilter(new TrackingRequest("POST", "/api/v1/agent/register", json(64), false),
                new MockHttpServletResponse(), chain);
        f.doFilter(new TrackingRequest("GET", "/api/v1/dashboard", null, false),
                new MockHttpServletResponse(), chain);
        verify(chain, org.mockito.Mockito.times(2)).doFilter(any(), any());
    }

    @Test
    void missingHeadersRejectedWithoutReadingBody() throws Exception {
        TrackingRequest req = new TrackingRequest("POST", REPORT, json(1024), false);
        req.addHeader(AgentSignatureFilter.HEADER_AGENT_ID, "agent-1");   // 缺 ts / nonce / sign
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter().doFilter(req, resp, mock(FilterChain.class));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.INVALID_SIGNATURE);
        assertThat(req.bodyTouched).as("headers are checked before the body is read").isFalse();
        verify(deviceRepository, never()).findByAgentId(anyString());
        verify(alertService).warn("agent-1", null, null, AlertType.SIGNATURE_INVALID, "missing signature headers");
    }

    @Test
    void badTimestampRejectedWithoutReadingBody() throws Exception {
        for (String ts : List.of("not-a-number", String.valueOf(System.currentTimeMillis() - 400_000L))) {
            TrackingRequest req = new TrackingRequest("POST", REPORT, json(1024), false);
            req.addHeader(AgentSignatureFilter.HEADER_AGENT_ID, "agent-1");
            req.addHeader(AgentSignatureFilter.HEADER_TS, ts);
            req.addHeader(AgentSignatureFilter.HEADER_NONCE, "n");
            req.addHeader(AgentSignatureFilter.HEADER_SIGN, "abc");
            MockHttpServletResponse resp = new MockHttpServletResponse();

            filter().doFilter(req, resp, mock(FilterChain.class));

            assertThat(resp.getStatus()).isEqualTo(200);
            assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.TIMESTAMP_OUT_OF_WINDOW);
            assertThat(req.bodyTouched).isFalse();
        }
        verify(deviceRepository, never()).findByAgentId(anyString());
    }

    @Test
    void unknownOrInactiveAgentRejectedWithoutReadingBody() throws Exception {
        when(deviceRepository.findByAgentId("ghost")).thenReturn(Optional.empty());
        AgentDevice revoked = device("revoked", "1.3.3");
        revoked.setStatus(AgentDevice.STATUS_REVOKED);
        when(deviceRepository.findByAgentId("revoked")).thenReturn(Optional.of(revoked));

        for (String id : List.of("ghost", "revoked")) {
            TrackingRequest req = signed(REPORT, id, json(1024), "n-" + id, false);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter().doFilter(req, resp, mock(FilterChain.class));

            assertThat(resp.getStatus()).isEqualTo(200);
            assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.AGENT_NOT_FOUND);
            assertThat(req.bodyTouched).as("agent existence is checked before the body is read").isFalse();
        }
    }

    // ---------------------------------------------------------------- filter 内 DB 异常 → 503

    @Test
    void dbFailureDuringAgentLookupBecomes503EvenWhenWrappedDeep() throws Exception {
        List<RuntimeException> overloads = List.of(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                        new SQLTransientConnectionException("aiwatch-cp - Connection is not available, request timed out after 30000ms")),
                new DataAccessResourceFailureException("connection lost"),
                new CannotAcquireLockException("Lock wait timeout exceeded"),
                new TransactionTimedOutException("Transaction timed out"),
                new RuntimeException("outer", new RuntimeException("mid",
                        new SQLTransientConnectionException("Connection is not available"))));
        for (RuntimeException failure : overloads) {
            doThrow(failure).when(deviceRepository).findByAgentId("agent-1");
            TrackingRequest req = signed(REPORT, "agent-1", json(1024), "n", false);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter().doFilter(req, resp, chain);

            assertBusy(resp);
            assertThat(req.bodyTouched).isFalse();
            verify(chain, never()).doFilter(any(), any());
        }
        verify(nonceStoreService, never()).tryClaim(anyString(), anyString(), anyString());
    }

    @Test
    void unrecognisedFailureDuringAgentLookupBecomes500NotA200() throws Exception {
        when(deviceRepository.findByAgentId("agent-1")).thenThrow(new IllegalStateException("bug"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter().doFilter(signed(REPORT, "agent-1", json(1024), "n", false), resp, mock(FilterChain.class));

        assertThat(resp.getStatus()).isEqualTo(500);
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void dbFailureDuringNonceClaimBecomes503AndNeverReachesController() throws Exception {
        agentExists("agent-1", "1.3.3");
        when(nonceStoreService.tryClaim(anyString(), anyString(), anyString()))
                .thenThrow(new CannotCreateTransactionException("no connection",
                        new SQLTransientConnectionException("Connection is not available")));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        AgentSignatureFilter f = filter();

        f.doFilter(signed(REPORT, "agent-1", json(1024), "n", false), resp, chain);

        assertBusy(resp);
        verify(chain, never()).doFilter(any(), any());
        // 名额随之归还：同一 agent 紧接着的下一个重请求不会被 per-agent 误挡
        doReturn(true).when(nonceStoreService).tryClaim(anyString(), anyString(), anyString());
        MockHttpServletResponse next = new MockHttpServletResponse();
        f.doFilter(signed(REPORT, "agent-1", json(4096), "n2", false), next, chain);
        assertThat(next.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void nonceReplayStillAnswers200WithReplayCode() throws Exception {
        agentExists("agent-1", "1.3.3");
        when(nonceStoreService.tryClaim(anyString(), anyString(), anyString())).thenReturn(false);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter().doFilter(signed(REPORT, "agent-1", json(1024), "n", false), resp, mock(FilterChain.class));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.NONCE_REPLAY);
    }

    // ---------------------------------------------------------------- 告警写入绝不让请求失败

    @Test
    void alertWriteFailureNeverFailsTheRequest() throws Exception {
        // 真实 AlertService + 一个必抛异常的仓库 + 同步执行：写失败被吞
        AgentAlertRepository brokenRepo = mock(AgentAlertRepository.class);
        when(brokenRepo.save(any(AgentAlert.class))).thenThrow(new DataAccessResourceFailureException("db down"));
        AlertService real = new AlertService(brokenRepo, props, java.time.Clock.systemDefaultZone(), Runnable::run);
        AgentSignatureFilter f = new AgentSignatureFilter(deviceRepository, nonceStoreService, real, objectMapper,
                props, new AgentIngestGuard(props), metrics);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", REPORT);   // 缺头 → 触发告警
        MockHttpServletResponse resp = new MockHttpServletResponse();
        f.doFilter(req, resp, mock(FilterChain.class));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.INVALID_SIGNATURE);
        verify(brokenRepo).save(any(AgentAlert.class));
    }

    @Test
    void evenAThrowingAlertServiceImplementationCannotFailTheRequest() throws Exception {
        doThrow(new IllegalStateException("alert boom")).when(alertService)
                .warn(any(), any(), any(), anyString(), anyString());
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter().doFilter(new MockHttpServletRequest("POST", REPORT), resp, mock(FilterChain.class));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.INVALID_SIGNATURE);
    }

    // ---------------------------------------------------------------- 413

    @Test
    void oversizedContentLengthGets413WithoutReadingBodyOrClaimingNonce() throws Exception {
        props.setMaxBodyBytes(1_000);
        agentExists("agent-1", "1.2.0");
        TrackingRequest req = signed(REPORT, "agent-1", json(2_000), "n", false);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(body(resp).get("message").asText()).contains("too large");
        assertThat(req.bodyTouched).isFalse();
        verify(nonceStoreService, never()).tryClaim(anyString(), anyString(), anyString());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void chunkedBodyIsReadWithACapAndRejectedWith413() throws Exception {
        props.setMaxBodyBytes(1_000);
        agentExists("agent-1", "1.2.0");
        TrackingRequest req = signed(REPORT, "agent-1", json(500_000), "n", false, true);   // Content-Length 未知
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(req.bodyTouched).isTrue();
        assertThat(req.bytesRead.get()).as("must abort at limit+1 instead of slurping 500KB").isLessThanOrEqualTo(1_001 + 8_192);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void gzipBombIsAbortedWith413() throws Exception {
        agentExists("agent-1", "1.3.3");
        byte[] bomb = gzip(AgentFilterTestSupport.zeros(20 * 1024 * 1024));   // 20MB 零 → 约 20KB，压缩比 1000:1
        assertThat(bomb.length).isLessThan(64 * 1024);
        TrackingRequest req = signed(REPORT, "agent-1", bomb, "n", true);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        verify(chain, never()).doFilter(any(), any());
        verify(nonceStoreService, never()).tryClaim(anyString(), anyString(), anyString());
    }

    @Test
    void malformedGzipStillMapsToInvalidSignatureNot413() throws Exception {
        agentExists("agent-1", "1.3.3");
        TrackingRequest req = signed(REPORT, "agent-1", "this is not gzip at all".getBytes(StandardCharsets.UTF_8),
                "n", true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter().doFilter(req, resp, mock(FilterChain.class));

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(body(resp).get("code").asInt()).isEqualTo(ErrorCode.INVALID_SIGNATURE);
    }

    @Test
    void byteBudgetExhaustedWhileDecodingBecomes503() throws Exception {
        agentExists("agent-1", "1.3.3");
        byte[] plain = json(200_000);
        byte[] wire = gzip(plain);
        IngestByteBudget budget = new IngestByteBudget(wire.length + 50_000);   // 装得下线上字节，装不下解压后的 3x
        IngestByteBudget.Lease lease = budget.tryOpen(wire.length);
        TrackingRequest req = signed(REPORT, "agent-1", wire, "n", true);
        req.setAttribute(AgentIngestBulkheadFilter.ATTR_BYTE_LEASE, lease);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(req, resp, chain);

        assertBusy(resp);
        verify(chain, never()).doFilter(any(), any());
        assertThat(metrics.rejectedCount(AgentIngestMetrics.REASON_BYTES_BUDGET)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- per-agent 单在途 / 老客户端名额

    /** 让一个请求卡在 chain 里。 */
    private static final class Held {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> future;

        static Held start(AgentSignatureFilter filter, MockHttpServletRequest req) throws Exception {
            Held h = new Held();
            FilterChain blocking = (rq, rs) -> {
                h.entered.countDown();
                try {
                    h.release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            h.future = CompletableFuture.runAsync(() -> {
                try {
                    filter.doFilter(req, new MockHttpServletResponse(), blocking);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertThat(h.entered.await(5, TimeUnit.SECONDS)).isTrue();
            return h;
        }

        void finish() throws Exception {
            release.countDown();
            future.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void secondConcurrentHeavyRequestFromSameAgentGets503() throws Exception {
        agentExists("agent-1", "1.3.3");
        agentExists("agent-2", "1.3.3");
        AgentSignatureFilter f = filter();
        Held first = Held.start(f, signed(REPORT, "agent-1", json(8_192), "n1", false));

        MockHttpServletResponse second = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        TrackingRequest secondReq =
                signed(AgentIngestBulkheadFilter.REPORT_COMMITS_PATH, "agent-1", json(8_192), "n2", false);
        f.doFilter(secondReq, second, chain);
        assertBusy(second);
        verify(chain, never()).doFilter(any(), any());
        assertThat(metrics.rejectedCount(AgentIngestMetrics.REASON_PER_AGENT)).isEqualTo(1);
        // 被拒的请求没有消耗 nonce：per-agent 判定在 nonce 占用之前
        verify(nonceStoreService, never()).tryClaim("agent-1", "n2", secondReq.getHeader(AgentSignatureFilter.HEADER_TS));

        // 别的 agent 不受影响
        MockHttpServletResponse other = new MockHttpServletResponse();
        f.doFilter(signed(REPORT, "agent-2", json(8_192), "n3", false), other, chain);
        assertThat(other.getStatus()).isEqualTo(200);

        // 同一 agent 的轻请求（心跳）不受影响
        MockHttpServletResponse heartbeat = new MockHttpServletResponse();
        f.doFilter(signed(REPORT, "agent-1", json(300), "n4", false), heartbeat, chain);
        assertThat(heartbeat.getStatus()).isEqualTo(200);

        first.finish();

        // 第一个结束后名额归还
        MockHttpServletResponse third = new MockHttpServletResponse();
        f.doFilter(signed(REPORT, "agent-1", json(8_192), "n5", false), third, chain);
        assertThat(third.getStatus()).isEqualTo(200);
    }

    @Test
    void perAgentSlotIsReleasedEvenWhenTheControllerThrows() throws Exception {
        agentExists("agent-1", "1.3.3");
        AgentSignatureFilter f = filter();
        FilterChain boom = (rq, rs) -> {
            throw new IllegalStateException("controller exploded");
        };
        try {
            f.doFilter(signed(REPORT, "agent-1", json(8_192), "n1", false), new MockHttpServletResponse(), boom);
        } catch (Exception expected) {
            // 交给容器
        }
        MockHttpServletResponse next = new MockHttpServletResponse();
        f.doFilter(signed(REPORT, "agent-1", json(8_192), "n2", false), next, mock(FilterChain.class));
        assertThat(next.getStatus()).isEqualTo(200);
    }

    @Test
    void pre133ClientsShareASmallQuotaAndDoNotStarveNewOnes() throws Exception {
        props.setIngestLegacyMaxConcurrency(2);
        agentExists("old-1", "1.3.2");
        agentExists("old-2", "1.2.9");
        agentExists("old-3", "v1.0.0");
        agentExists("new-1", "1.3.3");
        agentExists("dev-1", "dev");
        AgentSignatureFilter f = filter();
        Held a = Held.start(f, signed(REPORT, "old-1", json(8_192), "n1", false));
        Held b = Held.start(f, signed(REPORT, "old-2", json(8_192), "n2", false));

        MockHttpServletResponse third = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(signed(REPORT, "old-3", json(8_192), "n3", false), third, chain);
        assertBusy(third);
        assertThat(metrics.rejectedCount(AgentIngestMetrics.REASON_LEGACY)).isEqualTo(1);

        MockHttpServletResponse modern = new MockHttpServletResponse();
        f.doFilter(signed(REPORT, "new-1", json(8_192), "n4", false), modern, chain);
        assertThat(modern.getStatus()).isEqualTo(200);
        MockHttpServletResponse unparseable = new MockHttpServletResponse();
        f.doFilter(signed(REPORT, "dev-1", json(8_192), "n5", false), unparseable, chain);
        assertThat(unparseable.getStatus()).as("unparseable versions are treated as new clients").isEqualTo(200);

        // 老客户端的心跳（轻请求）不受名额限制
        MockHttpServletResponse hb = new MockHttpServletResponse();
        f.doFilter(signed(REPORT, "old-3", json(300), "n6", false), hb, chain);
        assertThat(hb.getStatus()).isEqualTo(200);

        a.finish();
        b.finish();
        MockHttpServletResponse afterRelease = new MockHttpServletResponse();
        f.doFilter(signed(REPORT, "old-3", json(8_192), "n7", false), afterRelease, chain);
        assertThat(afterRelease.getStatus()).isEqualTo(200);
    }
}
