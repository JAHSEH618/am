package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * /attribution 交叉透视响应：cells 按 (row_key, col_key, tier) 展平，指标（commit 数/净行数/占比）
 * 由前端从 tier 分档行自行汇算——B/A 分列展示是口径要求，served 端不预混。
 * 维度值为空（未归因行的 project/tool/model）时 key 为空串，前端渲染「未归因/未知」。
 * userNames 仅在行或列含"人"维度时携带（user_code → 显示名）。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttributionPivotDto {
    private List<Cell> cells;
    private Map<String, String> userNames;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cell {
        private String rowKey;
        private String colKey;
        private String tier;
        private long commitCount;
        private long linesAdded;
        private long linesDeleted;
    }
}
