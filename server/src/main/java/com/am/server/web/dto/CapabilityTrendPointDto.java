package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * /capability 趋势点（按日）。kind=skill 时 explicitCount / nlCount 分档，
 * invokeCount 恒为当日总调用（skill = 显式 + NL）。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityTrendPointDto {
    private LocalDate date;
    private long invokeCount;
    private Long explicitCount;
    private Long nlCount;
}
