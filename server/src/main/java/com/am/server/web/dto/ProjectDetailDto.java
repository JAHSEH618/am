package com.am.server.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 项目透视详情（v2.1 Phase 2）
 *
 * <p>结构：summary + contributorMatrix + dailyTimeline + topSlashCommands + topModels。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ProjectDetailDto {

    private ProjectSummaryDto summary;

    private List<Contributor> contributorMatrix;

    private List<DailyPoint> dailyTimeline;

    private List<NameValuePair> topSlashCommands;

    private List<NameValuePair> topModels;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Contributor {
        private String userCode;
        /** "姓名|工号"展示串；前端贡献者矩阵列直接显示这个 */
        private String userDisplay;
        private int sessionCount;
        private long totalTokens;
        private int messageCount;
        private int userMessageCount;
        private int assistantMessageCount;
        private long inputTokens;
        private long outputTokens;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class DailyPoint {
        private String date;
        private int sessionCount;
        private long totalTokens;
        private int userCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class NameValuePair {
        private String name;
        private long value;
    }
}
