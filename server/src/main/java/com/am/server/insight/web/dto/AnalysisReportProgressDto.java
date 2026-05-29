package com.am.server.insight.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 报告生成进度，前端轮询用。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisReportProgressDto {

    private Long id;
    private String status;          // pending / running / completed / failed
    private Integer auditedCount;
    private Integer totalCount;
    private Integer progressPercent; // 0-100，纯展示
    private String errorText;
}
