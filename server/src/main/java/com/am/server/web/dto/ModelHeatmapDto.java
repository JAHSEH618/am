package com.am.server.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 模型 token 燃烧热力图（v2.1 Phase 2）
 *
 * <p>结构：days x models 矩阵，cells 给前端做 ECharts heatmap。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ModelHeatmapDto {

    /** Y 轴：参与的模型，按总 token 倒排 */
    private List<String> models;
    /** X 轴：日期字符串 yyyy-MM-dd */
    private List<String> days;
    /** 单元格 */
    private List<Cell> cells;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Cell {
        /** 在 days 数组中的索引 */
        private int dayIndex;
        /** 在 models 数组中的索引 */
        private int modelIndex;
        private long tokens;
    }
}
