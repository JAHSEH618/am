package com.am.server.insight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 会话级 LLM 审计永久缓存（分析报告 v3.0 核心新表）。
 *
 * <p>一行 = 一次会话的双 judge LLM 评判结果，跨窗口复用，避免重复跑 LLM。
 * 重审：当 ai_session.total_messages 比快照 message_count_at_audit 多出
 * insight.reaudit-message-threshold（默认 20）条以上时，下一轮报告生成会重新评判并
 * UPDATE 该行（不增行）。
 *
 * <p>见 {@code docs/design/employee-insight-from-ai-sessions-v1.0.md} §6.1。
 * gz
 */
@Entity
@Table(name = "ai_session_audit")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AiSessionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "aiSessionAuditIdGen")
    @TableGenerator(name = "aiSessionAuditIdGen", table = "id_sequences",
            pkColumnName = "seq_name", valueColumnName = "next_val",
            pkColumnValue = "ai_session_audit", allocationSize = 50)
    private Long id;

    @Column(name = "ai_session_id", nullable = false)
    private Long aiSessionId;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "audit_version", nullable = false, length = 16)
    private String auditVersion;

    @Column(name = "judge_a_model", nullable = false, length = 64)
    private String judgeAModel;

    @Column(name = "judge_b_model", nullable = false, length = 64)
    private String judgeBModel;

    @Column(name = "difficulty", nullable = false)
    private Integer difficulty;

    @Column(name = "difficulty_a", nullable = false)
    private Integer difficultyA;

    @Column(name = "difficulty_b", nullable = false)
    private Integer difficultyB;

    /** completed / partial / abandoned */
    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    /** leverage / learning / dependent / exploratory / debugging */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "cap_problem_decomposition", nullable = false)
    private Integer capProblemDecomposition;

    @Column(name = "cap_context_management", nullable = false)
    private Integer capContextManagement;

    @Column(name = "cap_debugging_skill", nullable = false)
    private Integer capDebuggingSkill;

    @Column(name = "cap_tool_orchestration", nullable = false)
    private Integer capToolOrchestration;

    @Column(name = "cap_self_correction", nullable = false)
    private Integer capSelfCorrection;

    @Column(name = "judge_disagreement", nullable = false)
    private Integer judgeDisagreement = 0;

    @Column(name = "judge_reason_text", columnDefinition = "MEDIUMTEXT")
    private String judgeReasonText;

    @Column(name = "message_count_at_audit", nullable = false)
    private Integer messageCountAtAudit;

    @Column(name = "audited_time", nullable = false)
    private LocalDateTime auditedTime;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
