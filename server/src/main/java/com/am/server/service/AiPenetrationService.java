package com.am.server.service;

import com.am.server.domain.git.GitCommitAttributionRepository;
import com.am.server.domain.git.GitCommitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 渗透率（北极星指标）计算。
 *
 * <p>口径（v2.12 迁移，《管理后台-产出归因与能力使用分析 v1.0》§2.3）：
 * 渗透率 = AI 产出/辅助提交的 lines_added / 全部非 merge 提交的 lines_added × 100，
 * 主路读 {@code git_commit_attribution} 预计算（tier ∈ A∪B），单表聚合替代原全窗口 JOIN；
 * 数值与旧口径近似（B ⊆ A，A 档公式未变）。
 *
 * <p>兜底：归因表窗口内无行（历史回溯未完成 / 新库）时退回旧的查询时 JOIN 推断
 * {@link GitCommitRepository#aiPenetrationLines}，避免 Dashboard 在回溯期显示「—」。
 * 迁移对照走 {@link #compare}（验收：30d 偏差 ≤ 2pp）。
 *
 * <p>分母为 0 时返回 -1，前端据此显示「—」而非 0%。
 * gz
 */
@Service
@RequiredArgsConstructor
public class AiPenetrationService {

    private final GitCommitRepository gitCommitRepository;
    private final GitCommitAttributionRepository attributionRepository;

    /** 计算指定窗口的渗透率百分比（0–100），无数据返回 -1。 */
    public int compute(PenetrationWindow window) {
        LocalDateTime from = window.from(LocalDateTime.now());
        int fromAttribution = percentOf(attributionRepository.penetrationLines(from));
        if (fromAttribution >= 0) {
            return fromAttribution;
        }
        return percentOf(gitCommitRepository.aiPenetrationLines(from));
    }

    /** 迁移对照：legacy = 查询时 JOIN 旧口径；attribution = 预计算新口径。 */
    public Comparison compare(PenetrationWindow window) {
        LocalDateTime from = window.from(LocalDateTime.now());
        int legacy = percentOf(gitCommitRepository.aiPenetrationLines(from));
        int attribution = percentOf(attributionRepository.penetrationLines(from));
        int deviation = (legacy < 0 || attribution < 0) ? -1 : Math.abs(legacy - attribution);
        return new Comparison(legacy, attribution, deviation);
    }

    /** 迁移对照结果；deviationPp = |legacy - attribution|，任一侧无数据为 -1。 */
    public record Comparison(int legacyPercent, int attributionPercent, int deviationPp) {}

    /** [assisted, total] 单行聚合 → 百分比；无行 / 分母 0 返回 -1。 */
    private static int percentOf(List<Object[]> rows) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return -1;
        }
        Object[] r = rows.get(0);
        long assisted = toLong(r[0]);
        long total = r.length > 1 ? toLong(r[1]) : 0L;
        if (total <= 0) {
            return -1;
        }
        return (int) Math.round(assisted * 100.0 / total);
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
