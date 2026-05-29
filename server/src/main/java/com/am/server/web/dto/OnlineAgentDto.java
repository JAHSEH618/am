package com.am.server.web.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 大盘 Agent 表一行：在线设备按 target_type 展开；离线设备每设备一行（targetType=null）。
 * gz
 */
@Data
public class OnlineAgentDto {

    private String agentId;
    private String userCode;
    /** "姓名|工号"展示串；前端 Dashboard 在线表合并的"员工"列直接渲染这个 */
    private String userDisplay;
    private String hostname;
    private String osType;
    /** aiwatchd 上报的本地安装版本（与分发 manifest 比对可知是否落后）。 */
    private String agentVersion;
    /** 局域网 IPv4，agent 注册或上报时刷新 */
    private String localIp;
    /** git 全局账号 user.name */
    private String gitUserName;
    /** git 全局账号 user.email */
    private String gitUserEmail;
    /** Cursor 登录邮箱 */
    private String cursorEmail;
    /** Cursor 付费档：free / pro / pro_plus / business / ultra / enterprise */
    private String cursorMembershipType;
    /** Cursor 订阅状态 */
    private String cursorSubscriptionStatus;
    /** Cursor 注册渠道 */
    private String cursorSignupType;
    private LocalDateTime lastSeenTime;
    private long durationSeconds;
    private long activeSeconds;
    private String projectName;
    private String repoUrl;
    private String branchName;
    /**
     * 当前正在跑的 AI 工具类型（cursor / claude / codex / hermes / openclaw / openharness）。
     * 同一台机器可能并发跑多个工具 → 后端把每个 (agent_id, target_type) 展开成一行，
     * 前端用 rowSpan 把员工 / Git 等"按机器不变"的列合并显示，target_type 这一列分行展开。
     * 没有任何活跃 ai_session 时为 null（前端识别为"未运行 AI"分组行）。
     */
    private String targetType;
    /** 当前最活跃 ai_session 状态（idle / waiting / writing / ...） */
    private String currentStatus;
    private String currentTool;
    private String currentModel;
    private boolean active;
    private long sinceSeen;

    /**
     * ai_session.last_activity 距今的秒数；服务端用它做"陈旧状态降级"判定。
     * <p>当 staleSinceSeconds > {@code STATUS_STALE_THRESHOLD_SECONDS}（60s）时，currentStatus
     * 已被服务端强制覆盖为 "idle"（避免 agent 网络抖动后 UI 一直显示陈旧的 "writing"），
     * 此时 {@link #currentTool} 也会被一并清空。前端可以读这个字段给状态点加灰色 stale 样式。
     * <p>无 ai_session（targetType=null）时为 -1。
     */
    private long staleSinceSeconds = -1L;

    /**
     * 心跳是否在大盘在线窗口内；false 表示本行归属离线设备段（仅 targetType=null 的台维度行）。
     */
    private boolean deviceOnline = true;

    /**
     * 离线时长（秒）：deviceOnline=false 时为 last_seen→now（无 last_seen 时用 created_time）；
     * 在线设备固定为 0。
     */
    private long offlineSeconds;
}
