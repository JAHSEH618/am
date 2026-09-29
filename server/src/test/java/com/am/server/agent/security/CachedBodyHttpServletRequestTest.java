package com.am.server.agent.security;

import com.am.server.agent.security.AgentFilterTestSupport.TrackingRequest;
import com.am.server.agent.security.CachedBodyHttpServletRequest.BodyBudgetExceededException;
import com.am.server.agent.security.CachedBodyHttpServletRequest.BodyLimits;
import com.am.server.agent.security.CachedBodyHttpServletRequest.BodyTooLargeException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.am.server.agent.security.AgentFilterTestSupport.gzip;
import static com.am.server.agent.security.AgentFilterTestSupport.json;
import static com.am.server.agent.security.AgentFilterTestSupport.zeros;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachedBodyHttpServletRequestTest {

    private static final String PATH = "/api/v1/agent/report";
    private static final BodyLimits LIMITS = new BodyLimits(10_000, 100_000, 100);

    private static TrackingRequest req(byte[] body, boolean gzip, boolean chunked) {
        TrackingRequest r = new TrackingRequest("POST", PATH, body, chunked);
        if (gzip) {
            r.addHeader("Content-Encoding", "gzip");
        }
        return r;
    }

    @Test
    void plainBodyIsCachedAndReplayable() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        CachedBodyHttpServletRequest w = new CachedBodyHttpServletRequest(req(body, false, false), LIMITS, null);

        assertThat(w.getCachedBody()).isEqualTo(body);
        assertThat(w.getInputStream().readAllBytes()).isEqualTo(body);
        assertThat(w.getInputStream().readAllBytes()).as("re-readable").isEqualTo(body);
    }

    @Test
    void gzipBodyKeepsWireBytesForHmacAndExposesDecodedJsonDownstream() throws Exception {
        byte[] plain = json(5_000);
        byte[] wire = gzip(plain);
        CachedBodyHttpServletRequest w = new CachedBodyHttpServletRequest(req(wire, true, false), LIMITS, null);

        assertThat(w.getCachedBody()).isEqualTo(wire);
        assertThat(w.getInputStream().readAllBytes()).isEqualTo(plain);
    }

    @Test
    void defaultConstructorStillWorks() throws Exception {
        byte[] plain = json(2_000);
        CachedBodyHttpServletRequest w = new CachedBodyHttpServletRequest(req(gzip(plain), true, false));
        assertThat(w.getInputStream().readAllBytes()).isEqualTo(plain);
    }

    @Test
    void emptyBodyIsFine() throws Exception {
        CachedBodyHttpServletRequest w = new CachedBodyHttpServletRequest(req(new byte[0], true, false), LIMITS, null);
        assertThat(w.getCachedBody()).isEmpty();
        assertThat(w.getInputStream().readAllBytes()).isEmpty();
    }

    // ---------------------------------------------------------------- 线上字节上限

    @Test
    void knownContentLengthOverLimitFailsWithoutReadingAByte() {
        TrackingRequest r = req(new byte[10_001], false, false);
        assertThatThrownBy(() -> new CachedBodyHttpServletRequest(r, LIMITS, null))
                .isInstanceOfSatisfying(BodyTooLargeException.class, e -> {
                    assertThat(e.isDecoded()).isFalse();
                    assertThat(e.getLimit()).isEqualTo(10_000);
                });
        assertThat(r.bodyTouched).isFalse();
    }

    @Test
    void bodyExactlyAtLimitIsAccepted() throws Exception {
        CachedBodyHttpServletRequest w =
                new CachedBodyHttpServletRequest(req(new byte[10_000], false, false), LIMITS, null);
        assertThat(w.getCachedBody()).hasSize(10_000);
    }

    @Test
    void chunkedBodyOverLimitIsAbortedAtLimitPlusOne() {
        TrackingRequest r = req(new byte[5_000_000], false, true);
        assertThatThrownBy(() -> new CachedBodyHttpServletRequest(r, LIMITS, null))
                .isInstanceOfSatisfying(BodyTooLargeException.class, e -> assertThat(e.isDecoded()).isFalse());
        assertThat(r.bytesRead.get()).as("never slurps the whole 5MB").isLessThanOrEqualTo(LIMITS.maxBodyBytes() + 1);
    }

    @Test
    void chunkedBodyWithinLimitIsReadCompletely() throws Exception {
        byte[] body = json(9_000);
        CachedBodyHttpServletRequest w = new CachedBodyHttpServletRequest(req(body, false, true), LIMITS, null);
        assertThat(w.getCachedBody()).isEqualTo(body);
    }

    @Test
    void chunkedBodyExactlyAtLimitIsAccepted() throws Exception {
        CachedBodyHttpServletRequest w =
                new CachedBodyHttpServletRequest(req(new byte[10_000], false, true), LIMITS, null);
        assertThat(w.getCachedBody()).hasSize(10_000);
    }

    // ---------------------------------------------------------------- gzip 炸弹

    @Test
    void decodedSizeOverLimitAbortsDecompression() {
        // 解压后 200KB > maxDecoded 100KB；压缩后仅几百字节，比例保底额度 1MB 不起作用，是 maxDecoded 在拦
        byte[] wire = gzip(json(200_000));
        assertThatThrownBy(() -> new CachedBodyHttpServletRequest(req(wire, true, false), LIMITS, null))
                .isInstanceOfSatisfying(BodyTooLargeException.class, e -> {
                    assertThat(e.isDecoded()).isTrue();
                    assertThat(e.getLimit()).isEqualTo(100_000);
                    assertThat(e.getMessage()).contains("decoded body exceeds");
                });
    }

    @Test
    void compressionRatioLimitStopsABombLongBeforeTheAbsoluteLimit() {
        BodyLimits big = new BodyLimits(10_000_000, 1_000_000_000L, 100);   // 绝对上限 1GB，只靠比例拦
        byte[] wire = gzip(zeros(30 * 1024 * 1024));                       // 30MB 零 → ~30KB（1000:1）
        TrackingRequest r = req(wire, true, false);
        assertThatThrownBy(() -> new CachedBodyHttpServletRequest(r, big, null))
                .isInstanceOfSatisfying(BodyTooLargeException.class, e -> {
                    assertThat(e.isDecoded()).isTrue();
                    assertThat(e.getMessage()).contains("100:1");
                    // 允许额度 = max(1MB, wire × 100)，远小于 30MB
                    assertThat(e.getLimit()).isLessThan(6L * 1024 * 1024);
                });
    }

    @Test
    void tinyBodiesGetAOneMegabyteAllowanceSoTheRatioLimitCannotMisfire() throws Exception {
        // 800KB 零 → ~800 字节（1000:1），但绝对量很小，是合法的
        byte[] plain = zeros(800 * 1024);
        byte[] wire = gzip(plain);
        assertThat(wire.length * 100L).isLessThan(plain.length);
        CachedBodyHttpServletRequest w =
                new CachedBodyHttpServletRequest(req(wire, true, false), new BodyLimits(10_000, 10_000_000, 100), null);
        assertThat(w.getInputStream().readAllBytes()).hasSize(plain.length);
    }

    @Test
    void malformedGzipIsAPlainIoExceptionNotATooLarge() {
        assertThatThrownBy(() -> new CachedBodyHttpServletRequest(
                req("definitely not gzip".getBytes(StandardCharsets.UTF_8), true, false), LIMITS, null))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(BodyTooLargeException.class)
                .isNotInstanceOf(BodyBudgetExceededException.class)
                .hasMessageContaining("decode gzip body failed");
    }

    // ---------------------------------------------------------------- 字节预算记账

    @Test
    void plainRequestIsChargedTripleAndShrinksToActualWhenLengthWasUnknown() throws Exception {
        IngestByteBudget budget = new IngestByteBudget(1_000_000);
        // 模拟舱壁对 chunked 请求按 maxBody 预占：3 × 10_000
        IngestByteBudget.Lease lease = budget.tryOpen(IngestByteBudget.entryCharge(10_000, false));
        new CachedBodyHttpServletRequest(req(json(2_000), false, true), LIMITS, lease);

        assertThat(lease.held()).isEqualTo(3L * json(2_000).length);
        lease.close();
        assertThat(budget.inflightBytes()).isZero();
    }

    @Test
    void gzipRequestIsChargedRawPlusTripleDecoded() throws Exception {
        byte[] plain = json(50_000);
        byte[] wire = gzip(plain);
        IngestByteBudget budget = new IngestByteBudget(10_000_000);
        IngestByteBudget.Lease lease = budget.tryOpen(IngestByteBudget.entryCharge(wire.length, true));

        new CachedBodyHttpServletRequest(req(wire, true, false), new BodyLimits(10_000, 1_000_000, 100), lease);

        assertThat(lease.held()).isEqualTo(wire.length + 3L * plain.length);
        lease.close();
        assertThat(budget.inflightBytes()).isZero();
    }

    @Test
    void budgetExhaustionWhileDecodingAbortsWith503StyleException() {
        byte[] plain = json(50_000);
        byte[] wire = gzip(plain);
        IngestByteBudget budget = new IngestByteBudget(wire.length + 40_000L);   // 装不下 3 × 50_000
        IngestByteBudget.Lease lease = budget.tryOpen(wire.length);

        assertThatThrownBy(() -> new CachedBodyHttpServletRequest(req(wire, true, false),
                new BodyLimits(10_000, 1_000_000, 100), lease))
                .isInstanceOf(BodyBudgetExceededException.class);
        assertThat(budget.inflightBytes()).as("the lease still owns what it grabbed until the filter closes it")
                .isLessThanOrEqualTo(budget.capacity());
        lease.close();
        assertThat(budget.inflightBytes()).isZero();
    }
}
