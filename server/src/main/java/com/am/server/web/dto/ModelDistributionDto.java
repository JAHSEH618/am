package com.am.server.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型分布单行（v2.1 Phase 2）
 *
 * <p>用于"模型与工具"页的 token 占比堆叠柱图与列表。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ModelDistributionDto {
    private String model;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    /** 占总 token 的百分比（0-100）*/
    private double percent;
    private int sessionCount;
    private int userCount;
}
