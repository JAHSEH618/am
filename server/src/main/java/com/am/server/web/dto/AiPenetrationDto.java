package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 渗透率（北极星）查询结果。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiPenetrationDto {

    /** 渗透率百分比 0–100；-1 = 无数据（前端显示「—」）。 */
    private int percent;

    /** 回显请求口径：today / 7d / 30d。 */
    private String window;
}
