package com.am.server.agent.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.am.server.agent.security.AlertService;
import com.am.server.agent.security.SignatureContext;
import com.am.server.domain.agent.AlertType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** ingest 入口 token 护栏：不采信 / 丢弃、限流 WARN、限流 agent_alert、不含内容。 */
class TokenSanityGuardTest {

    private static final long SESSION_MAX = 2_000_000_000L;
    private static final long CACHE_MAX = 10_000_000_000L;
    private static final long DELTA_MAX = 500_000_000L;

    private final AlertService alertService = mock(AlertService.class);
    private final TokenSanityGuard guard = TokenSanityGuard.of(SESSION_MAX, CACHE_MAX, DELTA_MAX, alertService);
    private final SignatureContext ctx = new SignatureContext("agent-9", "U9", "host-9");

    private ListAppender<ILoggingEvent> appender;
    private Logger guardLogger;

    @BeforeEach
    void attachLogAppender() {
        guardLogger = (Logger) LoggerFactory.getLogger(TokenSanityGuard.class);
        appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        guardLogger.detachAppender(appender);
    }

    private long warnCount() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }

    @Test
    void legitimateValuesPassThrough_includingExactLimitAndOneHundredMillion() {
        assertThat(guard.acceptSessionTotal(ctx, "claude", "s1", "input_tokens", 100_000_000L, 5L, false))
                .isEqualTo(100_000_000L);
        assertThat(guard.acceptSessionTotal(ctx, "claude", "s1", "input_tokens", SESSION_MAX, 5L, false))
                .as("刚好等于上限").isEqualTo(SESSION_MAX);
        assertThat(guard.acceptSessionTotal(ctx, "claude", "s1", "cache_read_tokens", CACHE_MAX, 5L, true))
                .as("缓存上限单独放宽").isEqualTo(CACHE_MAX);
        assertThat(guard.acceptSessionTotal(ctx, "claude", "s1", "input_tokens", null, 5L, false))
                .as("null → 0").isZero();
        assertThat(guard.acceptDelta(ctx, "claude", "s1", "d", DELTA_MAX)).isEqualTo(DELTA_MAX);
        assertThat(guard.acceptDelta(ctx, "claude", "s1", "d", 100_000_000L)).isEqualTo(100_000_000L);

        assertThat(guard.rejectedTotalCount()).isZero();
        assertThat(guard.rejectedDeltaCount()).isZero();
        assertThat(warnCount()).isZero();
        verify(alertService, never()).warn(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void oversizedSessionTotal_isNotAdopted_keepsPrevious_warnsAndAlerts() {
        long got = guard.acceptSessionTotal(ctx, "codex", "sess-42", "input_tokens", 2_070_054_000_000L, 123_456L, false);

        assertThat(got).as("保留库内上一次的值").isEqualTo(123_456L);
        assertThat(guard.rejectedTotalCount()).isEqualTo(1);
        assertThat(warnCount()).isEqualTo(1);
        String msg = appender.list.get(0).getFormattedMessage();
        assertThat(msg).contains("agent-9", "U9", "codex", "sess-42", "input_tokens", "2070054000000", "123456");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(alertService).warn(eq("agent-9"), eq("U9"), eq("host-9"), eq(AlertType.TOKEN_TAMPER), text.capture());
        assertThat(text.getValue()).contains("sess-42", "input_tokens", "2070054000000");
    }

    @Test
    void oneOverLimitAndNegativeTotalsAreRejectedToo() {
        assertThat(guard.acceptSessionTotal(ctx, "claude", "s1", "output_tokens", SESSION_MAX + 1, 9L, false)).isEqualTo(9L);
        assertThat(guard.acceptSessionTotal(ctx, "claude", "s2", "output_tokens", -1L, 8L, false)).isEqualTo(8L);
        assertThat(guard.acceptSessionTotal(ctx, "claude", "s3", "cache_read_tokens", CACHE_MAX + 1, 7L, true))
                .isEqualTo(7L);
        assertThat(guard.acceptSessionTotal(ctx, "claude", "s4", "input_tokens", Long.MAX_VALUE, 0L, false)).isZero();
        assertThat(guard.rejectedTotalCount()).isEqualTo(4);
    }

    @Test
    void oversizedDelta_isDropped_negativeDeltaIsDroppedQuietly() {
        assertThat(guard.acceptDelta(ctx, "codex", "s1", "input_tokens_delta", DELTA_MAX + 1)).isZero();
        assertThat(guard.rejectedDeltaCount()).isEqualTo(1);
        assertThat(warnCount()).isEqualTo(1);

        // 老版本 Codex agent 压缩后合法地下发负增量：丢弃、计数，但不 WARN、不告警
        assertThat(guard.acceptDelta(ctx, "codex", "s1", "input_tokens_delta", -123L)).isZero();
        assertThat(guard.negativeDeltaCount()).isEqualTo(1);
        assertThat(guard.rejectedDeltaCount()).isEqualTo(1);
        assertThat(warnCount()).as("负 delta 不额外 WARN").isEqualTo(1);
    }

    @Test
    void repeatedRejectionsOfSameSessionFieldAreRateLimited() {
        for (int i = 0; i < 50; i++) {
            guard.acceptSessionTotal(ctx, "claude", "s1", "input_tokens", SESSION_MAX + 1, 1L, false);
        }
        assertThat(guard.rejectedTotalCount()).as("每次都拒绝").isEqualTo(50);
        assertThat(warnCount()).as("但同一会话同一字段的 WARN 限流为一条").isEqualTo(1);
        verify(alertService, times(1)).warn(anyString(), any(), any(), eq(AlertType.TOKEN_TAMPER), anyString());

        // 换一个会话：WARN 再放行一条，告警仍按 agent+provider 限流
        guard.acceptSessionTotal(ctx, "claude", "s2", "input_tokens", SESSION_MAX + 1, 1L, false);
        assertThat(warnCount()).isEqualTo(2);
        verify(alertService, times(1)).warn(anyString(), any(), any(), eq(AlertType.TOKEN_TAMPER), anyString());
    }

    @Test
    void alertFailureNeverBreaksIngest() {
        doThrow(new IllegalStateException("db down"))
                .when(alertService).warn(anyString(), any(), any(), anyString(), anyString());
        assertThat(guard.acceptSessionTotal(ctx, "claude", "s1", "input_tokens", SESSION_MAX + 1, 3L, false))
                .isEqualTo(3L);
    }

    @Test
    void withoutAlertServiceOrContext_onlyLogs() {
        TokenSanityGuard bare = TokenSanityGuard.withDefaults();
        assertThat(bare.maxSessionTokens()).isEqualTo(2_000_000_000L);
        assertThat(bare.maxSessionCacheTokens()).isEqualTo(10_000_000_000L);
        assertThat(bare.maxDeltaTokens()).isEqualTo(500_000_000L);
        assertThat(bare.acceptSessionTotal(null, "claude", "s1", "input_tokens", Long.MAX_VALUE, 4L, false)).isEqualTo(4L);
        assertThat(bare.acceptDelta(null, "claude", "s1", "d", Long.MAX_VALUE)).isZero();
    }
}
