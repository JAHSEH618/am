package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用统计行
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolStatDto {
    private String toolName;
    private long count;
    private long userCount;
    private long sessionCount;
}
