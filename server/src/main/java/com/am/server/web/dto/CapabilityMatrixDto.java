package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * /capability 人×一级维度覆盖矩阵（ECharts heatmap 友好形态）。
 * users / items 均按窗口内总调用量降序；cells = [userIndex, itemIndex, invokeCount]。
 * TopN 外折叠由前端做，后端返回全量排序结果。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityMatrixDto {
    private List<UserRow> users;
    private List<String> items;
    private List<int[]> cells;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserRow {
        private String userCode;
        private String displayName;
        private long totalCount;
    }
}
