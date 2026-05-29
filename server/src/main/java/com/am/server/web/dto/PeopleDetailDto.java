package com.am.server.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 员工数据详情（v2.1 Phase 2）
 *
 * <p>结构：summary 顶层指标 + dailyTimeline 每日时间线 + topModels / topTools / topProjects 三个 Top 列表。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PeopleDetailDto {

    private PeopleSummaryDto summary;

    private List<DailyPoint> dailyTimeline;

    /** Top 模型（按 token 加权累计） */
    private List<NameValuePair> topModels;

    /** Top 工具（按调用次数累计） */
    private List<NameValuePair> topTools;

    /** Top 项目（按 token 加权累计） */
    private List<NameValuePair> topProjects;

    /**
     * 选定时间窗 vs 上一自然周的关键指标对比（顶部横条）。
     * <p>本期 = RangePicker [from, min(to,today)]；上周 = 上一 ISO 自然周（周一 ~ 周日）。
     */
    private WeekOverWeek wow;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class DailyPoint {
        private String date;
        private int aiSessionCount;
        private long aiActiveSeconds;
        private long aiActiveSecondsUnion;
        private int aiMessageCount;
        private int toolCallCount;
        private int retryCount;
        private long inputTokens;
        private long outputTokens;
        private int firstResponseAvgMs;
        private long thinkingSeconds;
        private String topModel;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class NameValuePair {
        private String name;
        private long value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class WeekOverWeek {
        /** 窗口端点（含起止），格式 YYYY-MM-DD */
        private String thisWeekFrom;
        private String thisWeekTo;
        private String lastWeekFrom;
        private String lastWeekTo;

        private Metric activeSecondsUnion;
        private Metric tokens;
        private Metric sessions;
        private Metric messages;
        private Metric retries;
        /** daily_summary.ai_commit_count 累计（保留字段，前端未展示） */
        private Metric aiCommits;
        /** git_commit 表窗口内提交数（与列表 Git 列同源） */
        private Metric gitCommits;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Metric {
        private long thisWeek;
        private long lastWeek;
        /** (this - last) / last * 100；上周为 0 时返回 null（"无可比较基线"，前端显示 — 而不是 ∞%） */
        private Integer changePct;
    }
}
