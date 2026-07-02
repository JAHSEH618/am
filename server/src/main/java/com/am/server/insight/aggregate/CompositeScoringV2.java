package com.am.server.insight.aggregate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 综合分 v2（docs/design/insight-grades-narrative-pdf-v1.0.md §3）：
 * 五维加权 + 经验贝叶斯收缩 + S/A/B/C/D 等级映射。纯函数，无状态。
 * gz
 */
public final class CompositeScoringV2 {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final double W_CAP = 0.35, W_OUTPUT = 0.25, W_QUALITY = 0.20, W_CHALLENGE = 0.15, W_INDEPENDENCE = 0.05;
    /** 收缩先验强度：w = n / (n + K) */
    static final int SHRINK_K = 10;
    /** 置信度阈值：≥20 会话 normal，否则 low（<10 在聚合层已判 insufficient） */
    static final int CONFIDENCE_NORMAL_SESSIONS = 20;

    private CompositeScoringV2() {
    }

    /**
     * @param capAvg               五维能力均值（0-5，难度加权）
     * @param outputPercentileIndex 每小时 commit 团队分位 / 100（0-1；无产出 0）
     * @param completionRate       难度加权完成率（可空 → 0）
     * @param commitRevertRate     回滚率（commit<3 时不可信，置中性）
     * @param aiCommitCount        窗口 commit 数
     * @param challengeRatio       难度≥4 且 completed 的会话占比（0-1）
     * @param leverageRatio        leverage 模式占比（可空 → 0）
     * @param dependentRatio       dependent 模式占比（可空 → 0）
     */
    public record Inputs(double capAvg, double outputPercentileIndex, Double completionRate,
                         Double commitRevertRate, int aiCommitCount, double challengeRatio,
                         Double leverageRatio, Double dependentRatio) {
    }

    public static double rawScore(Inputs in) {
        double cap = clamp01(in.capAvg() / 5.0);
        double output = clamp01(in.outputPercentileIndex());
        double revertTerm = (in.aiCommitCount() >= 3 && in.commitRevertRate() != null)
                ? clamp01(1.0 - in.commitRevertRate()) : 0.5;
        double quality = 0.6 * clamp01(nz(in.completionRate())) + 0.4 * revertTerm;
        double challenge = clamp01(in.challengeRatio());
        double independence = clamp01((nz(in.leverageRatio()) - nz(in.dependentRatio()) + 1.0) / 2.0);
        return (W_CAP * cap + W_OUTPUT * output + W_QUALITY * quality
                + W_CHALLENGE * challenge + W_INDEPENDENCE * independence) * 100.0;
    }

    /** 经验贝叶斯收缩；teamMeanRaw 为 NaN（无同侪基线）时原样返回。 */
    public static double shrink(double raw, double teamMeanRaw, int auditedCount) {
        if (Double.isNaN(teamMeanRaw)) {
            return raw;
        }
        double w = auditedCount / (double) (auditedCount + SHRINK_K);
        return w * raw + (1.0 - w) * teamMeanRaw;
    }

    public static String gradeOf(double finalScore) {
        if (finalScore >= 85) return "S";
        if (finalScore >= 70) return "A";
        if (finalScore >= 55) return "B";
        if (finalScore >= 40) return "C";
        return "D";
    }

    public static String confidenceOf(int auditedCount) {
        return auditedCount >= CONFIDENCE_NORMAL_SESSIONS ? "normal" : "low";
    }

    /** 设计文档 §3.5 的透明化 JSON；team_p50 一栏由聚合层补，本层给子分与收缩参数。 */
    public static String breakdownJson(Inputs in, double raw, double shrinkWeight,
                                       double teamMeanRaw, double finalScore) {
        double revertTerm = (in.aiCommitCount() >= 3 && in.commitRevertRate() != null)
                ? clamp01(1.0 - in.commitRevertRate()) : 0.5;
        List<Map<String, Object>> dims = new ArrayList<>();
        dims.add(dim("cap", "能力", W_CAP, clamp01(in.capAvg() / 5.0)));
        dims.add(dim("output", "产出效率", W_OUTPUT, clamp01(in.outputPercentileIndex())));
        dims.add(dim("quality", "完成质量", W_QUALITY,
                0.6 * clamp01(nz(in.completionRate())) + 0.4 * revertTerm));
        dims.add(dim("challenge", "高难挑战", W_CHALLENGE, clamp01(in.challengeRatio())));
        dims.add(dim("independence", "独立成熟度", W_INDEPENDENCE,
                clamp01((nz(in.leverageRatio()) - nz(in.dependentRatio()) + 1.0) / 2.0)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formula_version", "v2");
        out.put("dimensions", dims);
        out.put("raw", round2(raw));
        out.put("shrink_weight", round2(shrinkWeight));
        out.put("team_mean", Double.isNaN(teamMeanRaw) ? null : round2(teamMeanRaw));
        out.put("final", round2(finalScore));
        try {
            return MAPPER.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static Map<String, Object> dim(String key, String label, double weight, double score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("weight", weight);
        m.put("score", round4(score));
        return m;
    }

    private static double nz(Double v) {
        return v == null || v.isNaN() ? 0.0 : v;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
