package com.am.server.insight.aggregate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 百分位计算工具。所有 watchlist 规则、相对化基线、composite_percentile 都依赖它。
 *
 * <p>实现选择：线性插值的分位数（与 numpy 默认行为一致）。
 * 数据量小（团队规模），不需要 t-digest 之类近似算法。
 * gz
 */
public final class PercentileCalculator {

    private PercentileCalculator() {
    }

    /**
     * 计算 percentile（0-100）。空列表返回 NaN。
     *
     * <p>同 numpy.percentile 的 linear 插值：
     * <pre>
     *   rank = (n - 1) * p / 100
     *   lo   = floor(rank), hi = ceil(rank), frac = rank - lo
     *   v    = sorted[lo] + frac * (sorted[hi] - sorted[lo])
     * </pre>
     */
    public static double percentile(List<Double> values, double p) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n == 1) {
            return sorted.get(0);
        }
        double rank = (n - 1) * p / 100.0;
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        double frac = rank - lo;
        return sorted.get(lo) + frac * (sorted.get(hi) - sorted.get(lo));
    }

    /** 一次性算 P25/P50/P75（最常用三档）。 */
    public static Percentiles tri(List<Double> values) {
        return new Percentiles(
                percentile(values, 25),
                percentile(values, 50),
                percentile(values, 75));
    }

    /** 一次性算 P10/P25/P50/P75/P90（完整 5 档，用于 watchlist 触发判定）。 */
    public static FivePercentiles five(List<Double> values) {
        return new FivePercentiles(
                percentile(values, 10),
                percentile(values, 25),
                percentile(values, 50),
                percentile(values, 75),
                percentile(values, 90));
    }

    /**
     * 给定一个值 x 在 values 中的百分位（0-100）。简单实现：count(values ≤ x) / n * 100。
     * 主要用于 composite_percentile 计算。
     */
    public static double rankPercentile(List<Double> values, double x) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        int leq = 0;
        for (double v : values) {
            if (v <= x) {
                leq++;
            }
        }
        return leq * 100.0 / values.size();
    }

    public record Percentiles(double p25, double p50, double p75) {
    }

    public record FivePercentiles(double p10, double p25, double p50, double p75, double p90) {
    }
}
