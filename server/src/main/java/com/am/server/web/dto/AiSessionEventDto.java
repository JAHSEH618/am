package com.am.server.web.dto;

import com.am.server.domain.ai.AiSessionEvent;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话事件视图
 * gz
 */
@Data
public class AiSessionEventDto {

    private Long id;
    private Long aiSessionId;
    private String userCode;
    /** "姓名|工号"展示串；前端 Realtime 事件流直接显示这个 */
    private String userDisplay;
    private String targetType;
    private String eventType;
    private String status;
    private String toolName;
    private long tokensDelta;
    private int messagesDelta;
    private LocalDateTime eventTime;

    public static AiSessionEventDto of(AiSessionEvent e) {
        AiSessionEventDto d = new AiSessionEventDto();
        d.id = e.getId();
        d.aiSessionId = e.getAiSessionId();
        d.userCode = e.getUserCode();
        d.targetType = e.getTargetType();
        d.eventType = e.getEventType();
        d.status = e.getStatus();
        d.toolName = e.getToolName();
        d.tokensDelta = e.getTokensDelta() == null ? 0 : e.getTokensDelta();
        d.messagesDelta = e.getMessagesDelta() == null ? 0 : e.getMessagesDelta();
        d.eventTime = e.getEventTime();
        return d;
    }
}
