package com.am.server.common;

import com.am.server.common.TokenSanitySupport.DayTotals;
import com.am.server.common.TokenSanitySupport.Limits;
import com.am.server.common.TokenSanitySupport.SessionTokens;
import com.am.server.common.TokenSanitySupport.Verdict;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token 合理性纯函数：边界（刚好等于上限 / 超一 / 负数 / null / 溢出 long / 正常大值 1 亿）与汇总策略。
 */
class TokenSanitySupportTest {

    private static final long SESSION_MAX = TokenSanitySupport.DEFAULT_MAX_SESSION_TOKENS; // 20 亿
    private static final long DELTA_MAX = TokenSanitySupport.DEFAULT_MAX_DELTA_TOKENS;     // 5 亿
    private static final long DAILY_MAX = TokenSanitySupport.DEFAULT_MAX_USER_DAILY_TOKENS; // 50 亿

    // ---------------------------------------------------------------- judge

    @Test
    void judge_boundaries() {
        assertThat(TokenSanitySupport.judge(0L, SESSION_MAX)).isEqualTo(Verdict.OK);
        assertThat(TokenSanitySupport.judge(100_000_000L, SESSION_MAX)).as("正常大值 1 亿").isEqualTo(Verdict.OK);
        assertThat(TokenSanitySupport.judge(SESSION_MAX, SESSION_MAX)).as("刚好等于上限合法").isEqualTo(Verdict.OK);
        assertThat(TokenSanitySupport.judge(SESSION_MAX + 1, SESSION_MAX)).as("超一").isEqualTo(Verdict.OVER_LIMIT);
        assertThat(TokenSanitySupport.judge(-1L, SESSION_MAX)).isEqualTo(Verdict.NEGATIVE);
        assertThat(TokenSanitySupport.judge(Long.MIN_VALUE, SESSION_MAX)).isEqualTo(Verdict.NEGATIVE);
        assertThat(TokenSanitySupport.judge(Long.MAX_VALUE, SESSION_MAX)).isEqualTo(Verdict.OVER_LIMIT);
        assertThat(TokenSanitySupport.judge((Long) null, SESSION_MAX)).as("null 按 0 处理").isEqualTo(Verdict.OK);
        // 事故里的形态：2,070,054 M 输入
        assertThat(TokenSanitySupport.judge(2_070_054_000_000L, SESSION_MAX)).isEqualTo(Verdict.OVER_LIMIT);
    }

    @Test
    void judge_bigIntegerBeyondLong() {
        BigInteger overflow = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        assertThat(TokenSanitySupport.judge(overflow, Long.MAX_VALUE)).as("溢出 long 即使上限设成 MAX 也判超限")
                .isEqualTo(Verdict.OVER_LIMIT);
        assertThat(TokenSanitySupport.judge(overflow.negate().subtract(BigInteger.ONE), SESSION_MAX))
                .isEqualTo(Verdict.NEGATIVE);
        assertThat(TokenSanitySupport.judge(BigInteger.valueOf(SESSION_MAX), SESSION_MAX)).isEqualTo(Verdict.OK);
        assertThat(TokenSanitySupport.judge(BigInteger.valueOf(SESSION_MAX + 1), SESSION_MAX))
                .isEqualTo(Verdict.OVER_LIMIT);
        assertThat(TokenSanitySupport.judge((BigInteger) null, SESSION_MAX)).isEqualTo(Verdict.OK);
    }

    // ---------------------------------------------------------------- accept*

    @Test
    void acceptTotal_adoptsLegitimateAndKeepsPreviousOtherwise() {
        assertThat(TokenSanitySupport.acceptTotal(100_000_000L, 7L, SESSION_MAX)).isEqualTo(100_000_000L);
        assertThat(TokenSanitySupport.acceptTotal(SESSION_MAX, 7L, SESSION_MAX)).as("等于上限采信").isEqualTo(SESSION_MAX);
        assertThat(TokenSanitySupport.acceptTotal(0L, 7L, SESSION_MAX)).as("合法的 0 也采信（会话重置）").isEqualTo(0L);
        assertThat(TokenSanitySupport.acceptTotal(null, 7L, SESSION_MAX)).as("null → 0，沿用 nz()").isEqualTo(0L);

        assertThat(TokenSanitySupport.acceptTotal(SESSION_MAX + 1, 7L, SESSION_MAX)).as("超限 → 保留上一次").isEqualTo(7L);
        assertThat(TokenSanitySupport.acceptTotal(-5L, 7L, SESSION_MAX)).as("负数 → 保留上一次").isEqualTo(7L);
        assertThat(TokenSanitySupport.acceptTotal(Long.MAX_VALUE, 0L, SESSION_MAX)).as("新会话 previous=0").isEqualTo(0L);
        // previous 自身已越界（历史坏值）也原样保留，不在这里"顺手改库"
        assertThat(TokenSanitySupport.acceptTotal(Long.MAX_VALUE, 2_070_054_000_000L, SESSION_MAX))
                .isEqualTo(2_070_054_000_000L);
    }

    @Test
    void acceptDelta_dropsNegativeAndOversized() {
        assertThat(TokenSanitySupport.acceptDelta(100_000_000L, DELTA_MAX)).isEqualTo(100_000_000L);
        assertThat(TokenSanitySupport.acceptDelta(DELTA_MAX, DELTA_MAX)).as("等于上限采信").isEqualTo(DELTA_MAX);
        assertThat(TokenSanitySupport.acceptDelta(DELTA_MAX + 1, DELTA_MAX)).isEqualTo(0L);
        assertThat(TokenSanitySupport.acceptDelta(-1L, DELTA_MAX)).isEqualTo(0L);
        assertThat(TokenSanitySupport.acceptDelta(Long.MAX_VALUE, DELTA_MAX)).isEqualTo(0L);
        assertThat(TokenSanitySupport.acceptDelta(null, DELTA_MAX)).isEqualTo(0L);
    }

    @Test
    void saturatingAdd_doesNotWrapAround() {
        assertThat(TokenSanitySupport.saturatingAdd(1L, 2L)).isEqualTo(3L);
        assertThat(TokenSanitySupport.saturatingAdd(Long.MAX_VALUE, 1L)).isEqualTo(Long.MAX_VALUE);
        assertThat(TokenSanitySupport.saturatingAdd(Long.MAX_VALUE, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(TokenSanitySupport.saturatingAdd(Long.MIN_VALUE, -1L)).isEqualTo(Long.MIN_VALUE);
        assertThat(TokenSanitySupport.saturatingAdd(Long.MAX_VALUE, -1L)).isEqualTo(Long.MAX_VALUE - 1);
    }

    // ---------------------------------------------------------------- sumUserDay

    @Test
    void sumUserDay_normalDayIsUntouched() {
        DayTotals t = TokenSanitySupport.sumUserDay(List.of(
                new SessionTokens(1L, 100_000_000L, 500_000L),
                new SessionTokens(2L, 300_000_000L, 1_500_000L)), Limits.DEFAULTS);
        assertThat(t.input()).isEqualTo(400_000_000L);
        assertThat(t.output()).isEqualTo(2_000_000L);
        assertThat(t.anomalous()).isFalse();
        assertThat(t.skipped()).isEmpty();
    }

    @Test
    void sumUserDay_skipsAnomalousSessionInsteadOfPollutingTheDay() {
        // 事故形态：一个 2.07 万亿 input / 102 亿 output 的坏值会话 + 一个正常会话
        DayTotals t = TokenSanitySupport.sumUserDay(List.of(
                new SessionTokens(1L, 2_070_054_000_000L, 10_246_000_000L),
                new SessionTokens(2L, 300_000_000L, 1_500_000L)), Limits.DEFAULTS);
        assertThat(t.input()).as("只剩正常会话").isEqualTo(300_000_000L);
        assertThat(t.output()).isEqualTo(1_500_000L);
        assertThat(t.skipped()).extracting(SessionTokens::sessionId).containsExactly(1L);
        assertThat(t.anomalous()).isTrue();
        assertThat(t.inputClamped()).isFalse();
    }

    @Test
    void sumUserDay_sessionExactlyAtLimitIsKeptButOneOverIsSkipped() {
        DayTotals atLimit = TokenSanitySupport.sumUserDay(
                List.of(new SessionTokens(1L, SESSION_MAX, 0L)), Limits.DEFAULTS);
        assertThat(atLimit.input()).isEqualTo(SESSION_MAX);
        assertThat(atLimit.skipped()).isEmpty();

        DayTotals over = TokenSanitySupport.sumUserDay(
                List.of(new SessionTokens(1L, SESSION_MAX + 1, 0L)), Limits.DEFAULTS);
        assertThat(over.input()).isZero();
        assertThat(over.skipped()).hasSize(1);
    }

    @Test
    void sumUserDay_negativeOrOneBadFieldSkipsWholeSession() {
        DayTotals t = TokenSanitySupport.sumUserDay(List.of(
                new SessionTokens(1L, -5L, 100L),
                new SessionTokens(2L, 100L, SESSION_MAX + 1),
                new SessionTokens(3L, 10L, 20L)), Limits.DEFAULTS);
        assertThat(t.input()).isEqualTo(10L);
        assertThat(t.output()).isEqualTo(20L);
        assertThat(t.skipped()).extracting(SessionTokens::sessionId).containsExactly(1L, 2L);
    }

    @Test
    void sumUserDay_clampsWhenIndividuallyPlausibleSessionsAddUpPastDailyCap() {
        // 4 个各 19 亿（都不越界）→ 76 亿 > 50 亿日上限：封顶但不跳过
        DayTotals t = TokenSanitySupport.sumUserDay(List.of(
                new SessionTokens(1L, 1_900_000_000L, 1L),
                new SessionTokens(2L, 1_900_000_000L, 1L),
                new SessionTokens(3L, 1_900_000_000L, 1L),
                new SessionTokens(4L, 1_900_000_000L, 1L)), Limits.DEFAULTS);
        assertThat(t.skipped()).isEmpty();
        assertThat(t.rawInput()).isEqualTo(7_600_000_000L);
        assertThat(t.input()).isEqualTo(DAILY_MAX);
        assertThat(t.inputClamped()).isTrue();
        assertThat(t.output()).isEqualTo(4L);
        assertThat(t.outputClamped()).isFalse();
        assertThat(t.anomalous()).isTrue();
    }

    @Test
    void sumUserDay_exactlyAtDailyCapIsNotClamped() {
        DayTotals t = TokenSanitySupport.sumUserDay(List.of(
                new SessionTokens(1L, 2_000_000_000L, 0L),
                new SessionTokens(2L, 2_000_000_000L, 0L),
                new SessionTokens(3L, 1_000_000_000L, 0L)), Limits.DEFAULTS);
        assertThat(t.input()).isEqualTo(DAILY_MAX);
        assertThat(t.inputClamped()).isFalse();
    }

    @Test
    void sumUserDay_handlesNullAndEmpty() {
        assertThat(TokenSanitySupport.sumUserDay(null, Limits.DEFAULTS).input()).isZero();
        assertThat(TokenSanitySupport.sumUserDay(List.of(), Limits.DEFAULTS).anomalous()).isFalse();
    }

    @Test
    void isAnomalousSession_matchesSumPolicy() {
        assertThat(TokenSanitySupport.isAnomalousSession(SESSION_MAX, SESSION_MAX, SESSION_MAX)).isFalse();
        assertThat(TokenSanitySupport.isAnomalousSession(SESSION_MAX + 1, 0, SESSION_MAX)).isTrue();
        assertThat(TokenSanitySupport.isAnomalousSession(0, SESSION_MAX + 1, SESSION_MAX)).isTrue();
        assertThat(TokenSanitySupport.isAnomalousSession(-1, 0, SESSION_MAX)).isTrue();
    }
}
