package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 全公司 Token 走势（daily_summary 口径，与员工数据页同源可对账）。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenTrendDto {

    /** 逐日一点，date 升序，缺失日已补零，恰好 days 个点。 */
    private List<DailyPoint> points;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyPoint {
        /** ISO yyyy-MM-dd */
        private String date;
        private long inputTokens;
        private long outputTokens;
    }
}
