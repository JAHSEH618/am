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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 窗口×用户级画像（管理员列表 / 详情数据源）。
 *
 * <p>一行 = (report_id, user_code)；通过 FK 与 {@link AnalysisReport} cascade 关联，
 * 删除报告自动清理对应用户画像。
 * gz
 */
@Entity
@Table(name = "analysis_report_user")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AnalysisReportUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "session_count", nullable = false)
    private Integer sessionCount;

    @Column(name = "ai_active_hours", nullable = false, precision = 8, scale = 2)
    private BigDecimal aiActiveHours;

    @Column(name = "total_tokens", nullable = false)
    private Long totalTokens;

    @Column(name = "ai_commit_count", nullable = false)
    /** 窗口内已上报 git_commit 条数（不按 {@code ai_assisted} 过滤）。 */
    private Integer aiCommitCount;

    @Column(name = "ai_lines_added", nullable = false)
    private Long aiLinesAdded;

    @Column(name = "difficulty_dist_json", columnDefinition = "JSON")
    private String difficultyDistJson;

    @Column(name = "avg_difficulty", precision = 3, scale = 2)
    private BigDecimal avgDifficulty;

    @Column(name = "high_difficulty_ratio", precision = 5, scale = 4)
    private BigDecimal highDifficultyRatio;

    @Column(name = "completion_rate", precision = 5, scale = 4)
    private BigDecimal completionRate;

    @Column(name = "abandoned_rate", precision = 5, scale = 4)
    private BigDecimal abandonedRate;

    @Column(name = "cap_problem_decomposition", precision = 3, scale = 2)
    private BigDecimal capProblemDecomposition;

    @Column(name = "cap_context_management", precision = 3, scale = 2)
    private BigDecimal capContextManagement;

    @Column(name = "cap_debugging_skill", precision = 3, scale = 2)
    private BigDecimal capDebuggingSkill;

    @Column(name = "cap_tool_orchestration", precision = 3, scale = 2)
    private BigDecimal capToolOrchestration;

    @Column(name = "cap_self_correction", precision = 3, scale = 2)
    private BigDecimal capSelfCorrection;

    @Column(name = "mode_dist_json", columnDefinition = "JSON")
    private String modeDistJson;

    @Column(name = "ai_commits_per_active_hour", precision = 8, scale = 4)
    private BigDecimal aiCommitsPerActiveHour;

    @Column(name = "ai_lines_per_1k_token", precision = 8, scale = 4)
    private BigDecimal aiLinesPer1kToken;

    @Column(name = "commit_revert_rate", precision = 5, scale = 4)
    private BigDecimal commitRevertRate;

    @Column(name = "high_difficulty_commit_ratio", precision = 5, scale = 4)
    private BigDecimal highDifficultyCommitRatio;

    @Column(name = "composite_score", precision = 6, scale = 2)
    private BigDecimal compositeScore;

    @Column(name = "composite_percentile", precision = 5, scale = 2)
    private BigDecimal compositePercentile;

    /** S/A/B/C/D（v2 公式 + 收缩后映射）；insufficient_data 时为 null */
    @Column(name = "composite_grade", length = 2)
    private String compositeGrade;

    /** normal（≥20 会话）/ low（10-19 会话） */
    @Column(name = "composite_confidence", length = 8)
    private String compositeConfidence;

    /** v2 五维子分 + 权重 + 收缩参数（见 CompositeScoringV2#breakdownJson） */
    @Column(name = "composite_breakdown_json", columnDefinition = "JSON")
    private String compositeBreakdownJson;

    @Column(name = "watchlist_flags_json", columnDefinition = "JSON")
    private String watchlistFlagsJson;

    @Column(name = "highlight_session_ids_json", columnDefinition = "JSON")
    private String highlightSessionIdsJson;

    /** 典型会话卡片 [{session_id,difficulty,mode,reason,nearby_commit}] */
    @Column(name = "highlight_sessions_json", columnDefinition = "JSON")
    private String highlightSessionsJson;

    @Column(name = "top_models_json", columnDefinition = "JSON")
    private String topModelsJson;

    @Column(name = "top_projects_json", columnDefinition = "JSON")
    private String topProjectsJson;

    @Column(name = "agent_dist_json", columnDefinition = "JSON")
    private String agentDistJson;

    @Column(name = "insufficient_data", nullable = false)
    private Integer insufficientData = 0;

    /** 用户消息首行 / 斜杠中判为「命令」的条数（非 TOOL_CALL） */
    @Column(name = "tool_command_count", nullable = false)
    private Integer toolCommandCount = 0;

    /** 用户首行 / 斜杠中判为「技能」的条数 */
    @Column(name = "tool_skill_count", nullable = false)
    private Integer toolSkillCount = 0;

    @Column(name = "tool_breakdown_json", columnDefinition = "JSON")
    private String toolBreakdownJson;

    /** LLM 个人评语 {level_summary,evidence,strengths,weaknesses,suggestions}；失败为 null */
    @Column(name = "narrative_json", columnDefinition = "JSON")
    private String narrativeJson;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "retry_per_active_hour", precision = 8, scale = 4)
    private BigDecimal retryPerActiveHour;

    @Column(name = "tool_call_count")
    private Integer toolCallCount;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
