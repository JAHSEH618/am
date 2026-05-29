package com.am.server.domain.session;

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
 * 工作会话实体（v1.3 语义升级）
 * online_seconds = 收到 device_state 的累计时长
 * active_seconds = 存在非 idle 且 last_activity 30s 内的 ai_session 累计时长
 * gz
 */
@Entity
@Table(name = "work_session")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class WorkSession {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "host_hash", nullable = false, length = 128)
    private String hostHash;

    @Column(name = "project_name", length = 128)
    private String projectName;

    @Column(name = "repo_url", length = 512)
    private String repoUrl;

    @Column(name = "branch_name", length = 128)
    private String branchName;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "duration_seconds")
    private Long durationSeconds = 0L;

    @Column(name = "active_seconds")
    private Long activeSeconds = 0L;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_OPEN;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @LastModifiedDate
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
