package com.am.server.domain.agent;

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
 * Agent 异常告警实体
 * 告警类型常量见 AlertType；告警级别见 AlertLevel
 * gz
 */
@Entity
@Table(name = "agent_alert")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AgentAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "user_code", length = 64)
    private String userCode;

    @Column(name = "host_hash", length = 128)
    private String hostHash;

    @Column(name = "alert_type", nullable = false, length = 64)
    private String alertType;

    @Column(name = "alert_level", nullable = false, length = 32)
    private String alertLevel;

    @Column(name = "message", length = 512)
    private String message;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;
}
