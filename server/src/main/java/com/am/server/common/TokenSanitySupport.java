package com.am.server.common;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Token 数值合理性判定的纯函数集合（无 Spring / DB 依赖，可直接单测）。
 *
 * <p><b>为什么需要</b>：{@code ai_session.input_tokens/output_tokens} 由客户端上报、服务端原样落库，
 * 而控制台「Token 走势」= {@code SUM(daily_summary.total_*_tokens)}，后者又是「last_activity 落在当日的
 * 会话累计值之和」。任何一个客户端解析出的异常大值（典型：pre-1.3.3 的子 agent 归并每个 tick 原地累加缓存对象，
 * 见 {@code docs/ops/token-anomaly.md}）都会整块砸进当天的汇总，且因为会话之后不再被上报（滑出 48h 回看窗口），
 * 坏值永远留在库里。所以入口、汇总两层都要有一个"绝不可能"的硬上限。
 *
 * <p><b>默认上限的依据</b>（全部可用配置覆盖，见 {@code TokenSanityGuard} 与 {@code DailySummaryAggregator}）：
 * <ul>
 *   <li>{@link #DEFAULT_MAX_SESSION_TOKENS} = 20 亿（input / output 各自）：单请求上下文窗口现实上限 ~1–2M
 *       token；重度 agent 以 ~1 请求 / 3–5s 连续跑满 24h ≈ 2 万次请求，含缓存读的「总输入」口径下
 *       200k × 2 万 ≈ 40 亿是理论天花板，而真实的多日长会话（Codex / Kimi 的 input 含缓存）实测在 1e8–1e9。
 *       20 亿留出 ≥ 2× 余量又比"万亿级"坏值低 3 个数量级，足以拦住所有已知的放大 bug。</li>
 *   <li>{@link #DEFAULT_MAX_SESSION_CACHE_TOKENS} = 100 亿（cache_read / cache_create）：缓存读每个请求都会把
 *       整段前缀再计一遍，长会话天然比 input / output 大一个量级，故单独放宽 5×。</li>
 *   <li>{@link #DEFAULT_MAX_DELTA_TOKENS} = 5 亿（单条 TOKEN_DELTA 的 input 或 output）：一条增量对应一次模型响应
 *       或两次 token_count 快照之间的差，受单请求上下文窗口约束（≤ 数 M）；5 亿 ≈ 窗口的 250×，
 *       仍远大于任何合法值，却能拦下"把累计值当增量"这一类错误。</li>
 *   <li>{@link #DEFAULT_MAX_USER_DAILY_TOKENS} = 50 亿（单用户单日 input 或 output）：汇总层最后一道闸，
 *       即便每个会话都"看起来合理"，单人一天也不可能超过它（≈ 单会话上限的 2.5×）。</li>
 * </ul>
 *
 * <p><b>判定口径</b>：{@code null} 视为 0（沿用 {@code nz()} 语义）；负数、超过上限都算异常；
 * <b>刚好等于上限是合法的</b>（闭区间）。溢出 {@code long} 的值（只可能来自 {@link BigInteger} 之类的宽类型）
 * 同样判为超限；累加一律用 {@link #saturatingAdd(long, long)}，避免两个接近 {@code Long.MAX_VALUE} 的值绕回负数。
 */
public final class TokenSanitySupport {

    /** 单会话累计 input / output token 上限：20 亿。 */
    public static final long DEFAULT_MAX_SESSION_TOKENS = 2_000_000_000L;
    /** 单会话累计 cache_read / cache_create token 上限：100 亿。 */
    public static final long DEFAULT_MAX_SESSION_CACHE_TOKENS = 10_000_000_000L;
    /** 单条 TOKEN_DELTA 的 input / output 上限：5 亿。 */
    public static final long DEFAULT_MAX_DELTA_TOKENS = 500_000_000L;
    /** 单用户单日 input / output 汇总上限：50 亿。 */
    public static final long DEFAULT_MAX_USER_DAILY_TOKENS = 5_000_000_000L;

    private TokenSanitySupport() {}

    /** 判定结果。 */
    public enum Verdict {
        /** 合法（含 null，按 0 处理）。 */
        OK,
        /** 负数。 */
        NEGATIVE,
        /** 超过上限（含溢出 long）。 */
        OVER_LIMIT;

        public boolean isOk() {
            return this == OK;
        }
    }

    /** 汇总层使用的一组上限。 */
    public record Limits(long maxSessionTokens, long maxUserDailyTokens) {
        public static final Limits DEFAULTS =
                new Limits(DEFAULT_MAX_SESSION_TOKENS, DEFAULT_MAX_USER_DAILY_TOKENS);
    }

    /** {@code value} 相对上限 {@code max} 的判定：null → OK；&lt;0 → NEGATIVE；&gt;max → OVER_LIMIT；其余 OK。 */
    public static Verdict judge(Long value, long max) {
        if (value == null) {
            return Verdict.OK;
        }
        if (value < 0) {
            return Verdict.NEGATIVE;
        }
        return value > max ? Verdict.OVER_LIMIT : Verdict.OK;
    }

    /** 宽类型版本：超出 {@code long} 范围的正数一律 OVER_LIMIT，负数一律 NEGATIVE。 */
    public static Verdict judge(BigInteger value, long max) {
        if (value == null) {
            return Verdict.OK;
        }
        if (value.signum() < 0) {
            return Verdict.NEGATIVE;
        }
        if (value.bitLength() > 63) {
            return Verdict.OVER_LIMIT;
        }
        return value.longValue() > max ? Verdict.OVER_LIMIT : Verdict.OK;
    }

    /**
     * 会话累计值：合法则采信 {@code incoming}（null → 0）；异常则<b>不采信</b>，保留库内上一次的值
     * {@code previous}（新会话传 0）。{@code previous} 本身即使已越界也原样保留——不在此处"顺手改库"，
     * 留给汇总层跳过、运维脚本修复，以保住取证证据。
     */
    public static long acceptTotal(Long incoming, long previous, long max) {
        return judge(incoming, max).isOk() ? (incoming == null ? 0L : incoming) : previous;
    }

    /** 单条增量：合法则采信（null → 0）；异常（负数 / 超限）则丢弃（返回 0）。 */
    public static long acceptDelta(Long incoming, long max) {
        return judge(incoming, max).isOk() ? (incoming == null ? 0L : incoming) : 0L;
    }

    /** 饱和加法：溢出时停在 {@code Long.MAX_VALUE} / {@code Long.MIN_VALUE}，不会绕回符号相反的值。 */
    public static long saturatingAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------
    // 汇总层：单用户单日
    // ------------------------------------------------------------------------------------------

    /** 一个会话的累计 token（汇总层逐会话输入）。 */
    public record SessionTokens(Long sessionId, long input, long output) {}

    /**
     * 单用户单日汇总结果。
     *
     * @param input            采信的会话 input 之和（已按 {@link Limits#maxUserDailyTokens()} 封顶）
     * @param output           采信的会话 output 之和（同上）
     * @param skipped          被跳过的异常会话（任一字段负数 / 超过单会话上限）
     * @param inputClamped     input 之和超过单日上限而被截断
     * @param outputClamped    output 之和超过单日上限而被截断
     * @param rawInput         封顶前的 input 之和（饱和加）
     * @param rawOutput        封顶前的 output 之和（饱和加）
     */
    public record DayTotals(long input, long output, List<SessionTokens> skipped,
                            boolean inputClamped, boolean outputClamped, long rawInput, long rawOutput) {
        public boolean anomalous() {
            return !skipped.isEmpty() || inputClamped || outputClamped;
        }
    }

    /**
     * 汇总策略（选定并说明）：<b>跳过异常会话，再对剩余之和做单日封顶</b>。
     *
     * <ul>
     *   <li>为什么"跳过"而不是"按上限截断"：坏值会话的真实用量未知，截断成上限（20 亿）仍会在趋势图上留下一个
     *       假的 20 亿凸起；整块跳过让该日只剩其它会话的真实用量，误差方向是"略偏低"而非"虚高"。</li>
     *   <li>为什么还要单日封顶：防止"每个会话都不越界、但加起来越界"（例如大量合法会话被同时重放）；
     *       封顶后仍打 ERROR，便于发现。</li>
     * </ul>
     */
    public static DayTotals sumUserDay(Collection<SessionTokens> sessions, Limits limits) {
        long in = 0L;
        long out = 0L;
        List<SessionTokens> skipped = new ArrayList<>();
        if (sessions != null) {
            for (SessionTokens s : sessions) {
                if (s == null) {
                    continue;
                }
                if (!judge(s.input(), limits.maxSessionTokens()).isOk()
                        || !judge(s.output(), limits.maxSessionTokens()).isOk()) {
                    skipped.add(s);
                    continue;
                }
                in = saturatingAdd(in, s.input());
                out = saturatingAdd(out, s.output());
            }
        }
        boolean inClamped = in > limits.maxUserDailyTokens();
        boolean outClamped = out > limits.maxUserDailyTokens();
        return new DayTotals(
                inClamped ? limits.maxUserDailyTokens() : in,
                outClamped ? limits.maxUserDailyTokens() : out,
                List.copyOf(skipped), inClamped, outClamped, in, out);
    }

    /** 该会话是否会被 {@link #sumUserDay} 跳过（模型 Top 等旁路统计复用同一判定）。 */
    public static boolean isAnomalousSession(long input, long output, long maxSessionTokens) {
        return !judge(input, maxSessionTokens).isOk() || !judge(output, maxSessionTokens).isOk();
    }
}
