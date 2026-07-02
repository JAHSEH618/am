package com.am.server.insight.aggregate;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 单个员工在某个窗口内的画像中间产物。
 *
 * <p>由 {@link ReportAggregator} 算出后既：
 * <ul>
 *   <li>送给 {@link WatchlistEvaluator} 与团队基线对比，标记 watchlist 信号</li>
 *   <li>转成 {@link com.am.server.insight.domain.AnalysisReportUser} 实体落库</li>
 * </ul>
 * gz
 */
@Data
public class UserMetrics {

    private String userCode;

    private int sessionCount;
    private double aiActiveHours;
    private long totalTokens;
    /** 窗口内已上报 git_commit 条数（不按 ai_assisted 推断过滤）。 */
    private int aiCommitCount;
    private long aiLinesAdded;

    /** 难度 1-5 → 该难度的会话数 */
    private Map<Integer, Integer> difficultyDist;
    private Double avgDifficulty;
    private Double highDifficultyRatio;

    /** 难度加权完成率 */
    private Double completionRate;
    private Double abandonedRate;

    /** 5 维能力（难度加权均值） */
    private Double capProblemDecomposition;
    private Double capContextManagement;
    private Double capDebuggingSkill;
    private Double capToolOrchestration;
    private Double capSelfCorrection;

    /** mode 占比 */
    private Map<String, Double> modeDist;

    private Double aiCommitsPerActiveHour;
    private Double aiLinesPer1kToken;
    private Double commitRevertRate;
    private Double highDifficultyCommitRatio;

    /** 综合分（内部排序用） */
    private Double compositeScore;
    /** 综合分百分位（0-100） */
    private Double compositePercentile;
    /** v2 等级 S/A/B/C/D；insufficient 时 null */
    private String compositeGrade;
    /** normal / low */
    private String compositeConfidence;
    /** v2 得分构成 JSON */
    private String compositeBreakdownJson;
    /** 难度≥4 且 completed 的会话占比（challenge 维度输入） */
    private Double highDifficultyCompletedRatio;

    /** 数据量太小不出详细评判 */
    private boolean insufficientData;

    /** 窗口内<strong>用户主动斜杠</strong>（首行 /，非 TOOL_CALL）中判为「命令」的次数 */
    private int toolCommandCount;
    /** 窗口内用户主动斜杠中判为「技能」的次数（启发式，见 {@link UserSlashInvocationKind}） */
    private int toolSkillCount;
    /**
     * JSON 数组：[{name, count, kind}]，name 为首词如 /fix；kind ∈ command|skill。
     */
    private String toolBreakdownJson;

    /** 窗口内重试/卡壳次数（daily_summary.ai_retry_count 求和） */
    private Integer retryCount;
    private Double retryPerActiveHour;
    /** 窗口内工具调用次数（daily_summary.tool_call_count 求和） */
    private Integer toolCallCount;

    /** Top 模型 / 项目 / Agent 分布（JSON 数组 [{name,value}]） */
    private String topModelsJson;
    private String topProjectsJson;
    private String agentDistJson;

    /** 简易序列化：仅给 BigDecimal 化用 */
    public static BigDecimal bd(Double v) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
