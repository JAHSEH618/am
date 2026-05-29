package com.am.server.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Provider 上报的会话消息（团队内部公开使用，不截断 text）
 * gz
 */
@Data
public class ConversationMessageDto {

    private String externalMessageId;
    private String role;
    private String text;
    private List<ContentPartDto> contentParts;
    private String toolName;
    private LocalDateTime timestamp;
    private Integer inputTokens;
    private Integer outputTokens;
    /** Cursor 等 provider 的 header 对话序号（1 起），用于展示排序。 */
    private Integer conversationOrder;
}
