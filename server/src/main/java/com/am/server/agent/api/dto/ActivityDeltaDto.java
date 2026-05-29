package com.am.server.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户端上报的带时间戳 token/消息增量（与 agent monitor.ActivityDelta 对齐）。
 * gz
 */
@Data
public class ActivityDeltaDto {

    private LocalDateTime eventTime;
    private Long inputTokensDelta;
    private Long outputTokensDelta;
    private Integer messagesDelta;
    private String source;
    /** 去重锚点：assistant uuid / bubble id / token_count 行等 */
    private String sourceRef;
}
