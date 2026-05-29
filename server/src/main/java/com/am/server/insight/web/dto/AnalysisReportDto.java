package com.am.server.insight.web.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 报告详情 API 主体：团队级摘要 + 员工列表。
 * 单员工详情走另一个 API（{@code /{reportId}/users/{userCode}}），避免一次性回包过大。
 * gz
 */
@Data
public class AnalysisReportDto {

    private Long id;
    private String windowFrom;
    private String windowTo;
    private String status;
    private Integer auditedCount;
    private Integer totalCount;
    private Integer progressPercent;
    private String errorText;
    private BigDecimal judgeDisagreementRatio;
    private String createdTime;
    private String completedTime;

    // 团队级摘要
    private Integer activeUserCount;
    private Integer totalSessionCount;
    private BigDecimal totalActiveHours;
    private Integer totalAiCommit;

    @JsonRawValue
    private String teamDifficultyDist;
    @JsonRawValue
    private String teamModeDist;
    @JsonRawValue
    private String teamPercentiles;
    @JsonRawValue
    private String teamCapabilityPercentiles;
    @JsonRawValue
    private String watchlistSummary;

    @JsonRawValue
    private String teamToolBreakdown;

    /** 员工列表（已按 composite_percentile 倒序） */
    private List<AnalysisReportUserDto> users;
}
