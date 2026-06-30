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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Agent 设备实体
 * 一行 = 一个 (user_code, host_hash) 元组对应的 Agent 实例
 * agent_id 在注册时生成，agent_secret 用作 HMAC 签名密钥
 * gz
 */
@Entity
@Table(name = "agent_device")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AgentDevice {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false, unique = true, length = 64)
    private String agentId;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "host_hash", nullable = false, length = 128)
    private String hostHash;

    @Column(name = "hostname", length = 128)
    private String hostname;

    @Column(name = "os_type", length = 32)
    private String osType;

    @Column(name = "agent_version", length = 32)
    private String agentVersion;

    @Column(name = "agent_secret", nullable = false, length = 256)
    private String agentSecret;

    @Column(name = "binary_hash", length = 128)
    private String binaryHash;

    @Column(name = "local_ip", length = 64)
    private String localIp;

    @Column(name = "git_user_name", length = 128)
    private String gitUserName;

    @Column(name = "git_user_email", length = 128)
    private String gitUserEmail;

    /** Cursor 登录邮箱（cursorAuth/cachedEmail），用于识别"这台机器实际登录的 Cursor 账号" */
    @Column(name = "cursor_email", length = 128)
    private String cursorEmail;

    /** Cursor 付费档：free / pro / pro_plus / business / ultra / enterprise */
    @Column(name = "cursor_membership_type", length = 32)
    private String cursorMembershipType;

    /** Cursor 订阅状态：active / canceled / past_due / trialing / 空(免费) */
    @Column(name = "cursor_subscription_status", length = 32)
    private String cursorSubscriptionStatus;

    /** Cursor 注册渠道：Auth_0 / Google / GitHub / Email */
    @Column(name = "cursor_signup_type", length = 32)
    private String cursorSignupType;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_ACTIVE;

    @Column(name = "last_seen_time")
    private LocalDateTime lastSeenTime;

    /** 上次"离线提醒邮件"发送时间，用于 12h 去重；恢复在线时不主动清空，靠 dedup 窗口自然过期。 */
    @Column(name = "last_offline_email_time")
    private LocalDateTime lastOfflineEmailTime;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @LastModifiedDate
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
