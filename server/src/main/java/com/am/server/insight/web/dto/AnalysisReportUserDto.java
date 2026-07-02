package com.am.server.insight.web.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 单员工数据 DTO（列表行 + 详情主体共用，详情多带的字段在前端按需读取）。
 *
 * <p>JSON 字段（如 difficulty_dist_json）直接用 {@link JsonRawValue} 透传到前端，避免
 * 在服务端做一次反序列化又序列化的浪费；前端会作为对象解析。
 * gz
 */
@Data
public class AnalysisReportUserDto {

    private String userCode;
    private String userDisplay;          // "姓名|工号"

    private Integer sessionCount;
    private BigDecimal aiActiveHours;
    private Long totalTokens;
    private Integer aiCommitCount;
    private Long aiLinesAdded;

    @JsonRawValue
    private String difficultyDist;       // JSON：{"1":3,"2":12,"3":24,"4":8,"5":2}
    private BigDecimal avgDifficulty;
    private BigDecimal highDifficultyRatio;

    private BigDecimal completionRate;
    private BigDecimal abandonedRate;

    private BigDecimal capProblemDecomposition;
    private BigDecimal capContextManagement;
    private BigDecimal capDebuggingSkill;
    private BigDecimal capToolOrchestration;
    private BigDecimal capSelfCorrection;

    @JsonRawValue
    private String modeDist;             // JSON：{"leverage":0.62, ...}

    private BigDecimal aiCommitsPerActiveHour;
    private BigDecimal aiLinesPer1kToken;
    private BigDecimal commitRevertRate;
    private BigDecimal highDifficultyCommitRatio;

    private BigDecimal compositeScore;
    private BigDecimal compositePercentile;
    /** "top25" / "mid50" / "bottom25" —— 由 percentile 推导 */
    private String compositeBucket;

    /** S/A/B/C/D（v2）；旧报告为 null，前端回退 bucket */
    private String compositeGrade;
    /** normal / low */
    private String compositeConfidence;
    @JsonRawValue
    private String compositeBreakdown;

    @JsonRawValue
    private String watchlistFlags;       // JSON 数组
    @JsonRawValue
    private String highlightSessionIds;  // JSON 数组（兼容）
    @JsonRawValue
    private String highlightSessions;      // JSON 卡片数组

    @JsonRawValue
    private String topModels;
    @JsonRawValue
    private String topProjects;
    @JsonRawValue
    private String agentDist;

    private Boolean insufficientData;

    private Integer toolCommandCount;
    private Integer toolSkillCount;
    /** JSON 数组：[{name,count,kind}] */
    @JsonRawValue
    private String toolBreakdown;

    /** LLM 个人评语；未生成为 null */
    @JsonRawValue
    private String narrative;

    private Integer retryCount;
    private BigDecimal retryPerActiveHour;
    private Integer toolCallCount;
}
