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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * AI 会话活动事件流水实体
 * 每次状态变更 / 工具调用 / token 增量 都落一行，作为审计与回放数据源
 * gz
 */
@Entity
@Table(name = "ai_session_event")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AiSessionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ai_session_id", nullable = false)
    private Long aiSessionId;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    /** 字符串值见 AiSessionEventType.name() */
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "tool_name", length = 512)
    private String toolName;

    @Column(name = "tokens_delta")
    private Long tokensDelta = 0L;

    @Column(name = "input_tokens_delta")
    private Long inputTokensDelta = 0L;

    @Column(name = "output_tokens_delta")
    private Long outputTokensDelta = 0L;

    @Column(name = "messages_delta")
    private Integer messagesDelta = 0;

    /** 复杂额外上下文以 JSON 字符串存储，避免引入 hibernate-types 依赖 */
    @Column(name = "extra_json", columnDefinition = "JSON")
    private String extraJson;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
