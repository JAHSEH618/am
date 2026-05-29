package com.am.server.agent.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Provider 维度的会话快照
 * v2.0 起删除 costUsd 字段；老客户端（v1.x）继续上报 cost_usd 不会报错——Spring Boot 默认
 * Jackson 配置 fail-on-unknown-properties=false，多余字段静默忽略。
 * gz
 */
@Data
public class MonitorSessionDto {

    @NotBlank
    private String sessionId;

    private String cwd;
    private String cwdHash;
    private String gitBranch;
    private String repoUrl;
    private String projectName;
    private Boolean isWorktree;
    private String mainRepo;

    private String model;
    private String status;
    private String currentTool;

    private LocalDateTime startedAt;
    private LocalDateTime lastActivity;

    private Integer userMessages;
    private Integer assistantMessages;
    /** Agent 当前快照 recent_messages 条数（含 tool/thinking）。 */
    private Integer snapshotMessageCount;

    private Long inputTokens;
    private Long outputTokens;
    private Long cacheCreateTokens;
    private Long cacheReadTokens;

    @Valid
    private List<ToolCallDto> recentTools;

    @Valid
    private List<ConversationMessageDto> recentMessages;

    @Valid
    private List<ActivityDeltaDto> activityDeltas;
}
