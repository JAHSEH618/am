package com.am.server.domain.ai;

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

import java.time.LocalDateTime;

/**
 * AI 编码会话主表实体
 * 一行 = 被监控软件中的一个会话，例如 Cursor 一个 composer 会话
 * 通过 (target_type, external_session_id) 在上报时做 upsert
 * gz
 */
@Entity
@Table(name = "ai_session")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AiSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "external_session_id", nullable = false, length = 128)
    private String externalSessionId;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "host_hash", nullable = false, length = 128)
    private String hostHash;

    @Column(name = "cwd", length = 512)
    private String cwd;

    @Column(name = "cwd_hash", length = 128)
    private String cwdHash;

    @Column(name = "git_branch", length = 128)
    private String gitBranch;

    @Column(name = "repo_url", length = 512)
    private String repoUrl;

    @Column(name = "project_name", length = 128)
    private String projectName;

    @Column(name = "is_worktree", nullable = false, columnDefinition = "TINYINT")
    private Integer isWorktree = 0;

    @Column(name = "main_repo", length = 512)
    private String mainRepo;

    @Column(name = "model", length = 64)
    private String model;

    /** 字符串值见 AiSessionStatus.code() */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "current_tool", length = 64)
    private String currentTool;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "last_activity", nullable = false)
    private LocalDateTime lastActivity;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "user_messages", nullable = false)
    private Integer userMessages = 0;

    @Column(name = "assistant_messages", nullable = false)
    private Integer assistantMessages = 0;

    @Column(name = "total_messages", nullable = false)
    private Integer totalMessages = 0;

    /** Agent 最近一次快照中的 recent_messages 条数，用于判断消息回填是否完成。 */
    @Column(name = "reported_snapshot_messages", nullable = false)
    private Integer reportedSnapshotMessages = 0;

    @Column(name = "input_tokens", nullable = false)
    private Long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    private Long outputTokens = 0L;

    @Column(name = "cache_create_tokens", nullable = false)
    private Long cacheCreateTokens = 0L;

    @Column(name = "cache_read_tokens", nullable = false)
    private Long cacheReadTokens = 0L;

    /**
     * 无效会话标记（v2.11）。NULL = 有效；非空表示模型从未真正回应过这个会话。
     * <ul>
     *   <li>"local_command_only"  Claude Code 等 CLI 客户端把 /usage、/exit 等本地斜杠命令
     *                              当作用户消息上报上来，但模型从未被调用</li>
     *   <li>"no_assistant_reply"  其他来源的同类无效会话兜底原因</li>
     * </ul>
     * <p>由 {@code AbstractAiSessionIngestService#evaluateInvalidReason} 在每次 ingest 末尾
     * 自动评估：满足 user_messages&gt;0 且 assistant_messages=0 且 input/output_tokens=0 时打标，
     * 一旦模型有回应（assistant_messages 或 token 变正）就自动清空，状态自收敛。
     */
    @Column(name = "invalid_reason", length = 64)
    private String invalidReason;

    /**
     * 后台洞察审计扫描状态（见 {@code BackgroundInsightAuditScanner}）。
     */
    @Column(name = "insight_audit_status", nullable = false, length = 16)
    private String insightAuditStatus = "NONE";

    @Column(name = "insight_audit_rubric_version", length = 16)
    private String insightAuditRubricVersion;

    @Column(name = "insight_reaudit_required", nullable = false, columnDefinition = "TINYINT")
    private Integer insightReauditRequired = 0;

    @Column(name = "insight_audit_lease_until")
    private LocalDateTime insightAuditLeaseUntil;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @LastModifiedDate
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
