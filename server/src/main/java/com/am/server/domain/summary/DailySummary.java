package com.am.server.domain.summary;

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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工日汇总实体
 * 一行 = (user_code, work_date)，由 DailySummaryAggregator 按日聚合写入
 * v2.0 字段服务于 AIWatch "员工 AI 协作健康度 / 产出"指标体系
 * gz
 */
@Entity
@Table(name = "daily_summary")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class DailySummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    // ---- v1.2 工时类（保留兼容；当前 Aggregator v2.0 暂不写这里）----

    @Column(name = "online_seconds")
    private Long onlineSeconds = 0L;

    @Column(name = "active_seconds")
    private Long activeSeconds = 0L;

    @Column(name = "first_online_time")
    private LocalDateTime firstOnlineTime;

    @Column(name = "last_offline_time")
    private LocalDateTime lastOfflineTime;

    @Column(name = "project_count")
    private Integer projectCount = 0;

    @Column(name = "project_summary", columnDefinition = "TEXT")
    private String projectSummary;

    // ---- v1.3 AI 汇总类 ----

    @Column(name = "ai_session_count", nullable = false)
    private Integer aiSessionCount = 0;

    @Column(name = "ai_message_count", nullable = false)
    private Integer aiMessageCount = 0;

    @Column(name = "total_input_tokens", nullable = false)
    private Long totalInputTokens = 0L;

    @Column(name = "total_output_tokens", nullable = false)
    private Long totalOutputTokens = 0L;

    @Column(name = "tool_call_count", nullable = false)
    private Integer toolCallCount = 0;

    @Column(name = "active_model_top", length = 64)
    private String activeModelTop;

    // ---- v2.0 AI 健康度 / 产出（DailySummaryAggregator 写入）----

    /**
     * 当日 AI 协作时长（秒）：v2.5 起按"user 提问 → 紧随 assistant 回完"轮次时长累加；
     * 单轮 ≥ 30min 视为用户走开剔除；轮次之间的闲置不计。多 session 并行时可能 > 86400。
     */
    @Column(name = "ai_active_seconds", nullable = false)
    private Long aiActiveSeconds = 0L;

    /**
     * 当日 AI 协作时长（秒），区间并集口径：同上轮次区间做 merge-overlapping 后的并集，
     * 同一用户多 session 时间重叠只算一次，自然 ≤ 86400。
     */
    @Column(name = "ai_active_seconds_union", nullable = false)
    private Long aiActiveSecondsUnion = 0L;

    /** 当日首次响应平均时长（ms）：所有 user→第一条 assistant 的平均 */
    @Column(name = "ai_first_response_avg_ms", nullable = false)
    private Integer aiFirstResponseAvgMs = 0;

    /** 当日 thinking 状态累计（秒）：连续 STATUS_CHANGE thinking 段时长求和 */
    @Column(name = "ai_thinking_seconds", nullable = false)
    private Long aiThinkingSeconds = 0L;

    /** 当日重试 / 卡壳次数：相邻 user 间无 assistant 出现的次数 */
    @Column(name = "ai_retry_count", nullable = false)
    private Integer aiRetryCount = 0;

    /** 当日 AI 协助提交数（依赖 git_commit 表，v2.2 接入；当前固定 0） */
    @Column(name = "ai_commit_count", nullable = false)
    private Integer aiCommitCount = 0;

    /** 当日 token 消耗 Top3 模型，JSON 字符串 ["model_a", "model_b", "model_c"] */
    @Column(name = "ai_models_top3", length = 256)
    private String aiModelsTop3;

    // ---- 审计 ----

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @LastModifiedDate
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
