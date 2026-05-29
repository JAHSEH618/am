package com.am.server.insight.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史报告侧栏一行。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisReportListItemDto {

    private Long id;
    private String windowFrom;
    private String windowTo;
    private String status;
    private Integer auditedCount;
    private Integer totalCount;
    private Integer activeUserCount;
    private Integer totalSessionCount;
    private String createdTime;
    private String completedTime;
}
