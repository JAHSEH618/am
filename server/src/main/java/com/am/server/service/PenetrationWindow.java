package com.am.server.service;

import java.time.LocalDateTime;

/**
 * AI 渗透率北极星卡的可选时间窗：今日 / 近7天 / 近30天。
 * gz
 */
public enum PenetrationWindow {

    TODAY,
    D7,
    D30;

    /** 窗口起点：今日零点 / now-7d / now-30d。 */
    public LocalDateTime from(LocalDateTime now) {
        return switch (this) {
            case TODAY -> now.toLocalDate().atStartOfDay();
            case D7 -> now.minusDays(7);
            case D30 -> now.minusDays(30);
        };
    }

    /** 解析前端入参；缺省/未知一律回退 30 天。 */
    public static PenetrationWindow parse(String s) {
        if (s == null) {
            return D30;
        }
        return switch (s.trim().toLowerCase()) {
            case "today" -> TODAY;
            case "7d" -> D7;
            default -> D30;
        };
    }
}
