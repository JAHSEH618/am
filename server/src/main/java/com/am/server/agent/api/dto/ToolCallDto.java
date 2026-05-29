package com.am.server.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Provider 上报的工具调用
 * gz
 */
@Data
public class ToolCallDto {

    private String name;
    private LocalDateTime timestamp;
}
