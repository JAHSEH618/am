package com.am.server.insight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 窗口级分析报告（一行 = 一个时间窗的一次报告）。
 * 团队级 payload 直接以 JSON 字符串落库；细粒度的"窗口×用户"挂在 {@link AnalysisReportUser}。
 *
 * <p>状态机见 {@link ReportStatus}；窗口唯一键 (window_from, window_to)。
 * gz
 */
@Entity
@Table(name = "analysis_report")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AnalysisReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "window_from", nullable = false)
    private LocalDate windowFrom;

    @Column(name = "window_to", nullable = false)
    private LocalDate windowTo;

    @Column(name = "report_version", nullable = false, length = 16)
    private String reportVersion;

    @Column(name = "rubric_version", nullable = false, length = 16)
    private String rubricVersion;

    /** {@link ReportStatus} */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "audited_count", nullable = false)
    private Integer auditedCount = 0;

    @Column(name = "total_count", nullable = false)
    private Integer totalCount = 0;

    @Column(name = "error_text", columnDefinition = "MEDIUMTEXT")
    private String errorText;

    @Column(name = "active_user_count")
    private Integer activeUserCount;

    @Column(name = "total_session_count")
    private Integer totalSessionCount;

    @Column(name = "total_active_hours", precision = 10, scale = 2)
    private BigDecimal totalActiveHours;

    @Column(name = "total_ai_commit")
    /** 窗口内已上报 git_commit 条数（不按 {@code ai_assisted} 过滤）。 */
    private Integer totalAiCommit;

    @Column(name = "team_difficulty_dist_json", columnDefinition = "JSON")
    private String teamDifficultyDistJson;

    @Column(name = "team_mode_dist_json", columnDefinition = "JSON")
    private String teamModeDistJson;

    @Column(name = "team_completion_dist_json", columnDefinition = "JSON")
    private String teamCompletionDistJson;

    @Column(name = "team_percentiles_json", columnDefinition = "JSON")
    private String teamPercentilesJson;

    /** 团队五维能力 P10-P90 基线 */
    @Column(name = "team_capability_percentiles_json", columnDefinition = "JSON")
    private String teamCapabilityPercentilesJson;

    @Column(name = "watchlist_summary_json", columnDefinition = "JSON")
    private String watchlistSummaryJson;

    /** 团队用户主动斜杠分布：{items:[{name,count,kind}],command_total,skill_total} */
    @Column(name = "team_tool_breakdown_json", columnDefinition = "JSON")
    private String teamToolBreakdownJson;

    @Column(name = "judge_disagreement_ratio", precision = 5, scale = 4)
    private BigDecimal judgeDisagreementRatio;

    @Column(name = "started_time")
    private LocalDateTime startedTime;

    @Column(name = "completed_time")
    private LocalDateTime completedTime;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @LastModifiedDate
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
