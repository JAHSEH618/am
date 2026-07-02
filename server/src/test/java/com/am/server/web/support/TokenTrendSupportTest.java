package com.am.server.web.support;

import com.am.server.web.dto.TokenTrendDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenTrendSupportTest {

    @Test
    void clampDays() {
        assertEquals(1, TokenTrendSupport.clampDays(0));
        assertEquals(1, TokenTrendSupport.clampDays(-5));
        assertEquals(30, TokenTrendSupport.clampDays(30));
        assertEquals(90, TokenTrendSupport.clampDays(91));
    }

    @Test
    void fillDaily_emptyRowsProducesAllZeroPointsAscending() {
        LocalDate from = LocalDate.of(2026, 6, 3);
        LocalDate to = LocalDate.of(2026, 7, 2);
        List<TokenTrendDto.DailyPoint> pts = TokenTrendSupport.fillDaily(from, to, List.of());
        assertEquals(30, pts.size());
        assertEquals("2026-06-03", pts.get(0).getDate());
        assertEquals("2026-07-02", pts.get(29).getDate());
        assertEquals(0L, pts.get(0).getInputTokens());
        assertEquals(0L, pts.get(29).getOutputTokens());
    }

    @Test
    void fillDaily_sparseRowsLandOnCorrectDates() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 3);
        // 显式类型见证:List.of(new Object[]{...}) 会被 varargs 展开成 List<Object>
        List<Object[]> rows = List.<Object[]>of(
                new Object[]{LocalDate.of(2026, 7, 2), 100L, 200L});
        List<TokenTrendDto.DailyPoint> pts = TokenTrendSupport.fillDaily(from, to, rows);
        assertEquals(3, pts.size());
        assertEquals(0L, pts.get(0).getInputTokens());
        assertEquals(100L, pts.get(1).getInputTokens());
        assertEquals(200L, pts.get(1).getOutputTokens());
        assertEquals("2026-07-02", pts.get(1).getDate());
        assertEquals(0L, pts.get(2).getInputTokens());
    }

    @Test
    void fillDaily_toleratesSqlDateAndNumberVariants() {
        // JPA 投影里 workDate 可能给 java.sql.Date、SUM 可能给 BigDecimal/Integer(见
        // DailySummaryRepository.findMaxUpdatedTimePerDay javadoc 的 [java.sql.Date, ...] 先例)
        LocalDate from = LocalDate.of(2026, 7, 1);
        List<Object[]> rows = List.<Object[]>of(
                new Object[]{java.sql.Date.valueOf("2026-07-01"), new BigDecimal("7"), 8});
        List<TokenTrendDto.DailyPoint> pts = TokenTrendSupport.fillDaily(from, from, rows);
        assertEquals(1, pts.size());
        assertEquals(7L, pts.get(0).getInputTokens());
        assertEquals(8L, pts.get(0).getOutputTokens());
    }

    @Test
    void fillDaily_singleDayWindow() {
        LocalDate d = LocalDate.of(2026, 7, 2);
        List<TokenTrendDto.DailyPoint> pts = TokenTrendSupport.fillDaily(d, d, List.of());
        assertEquals(1, pts.size());
        assertEquals("2026-07-02", pts.get(0).getDate());
    }
}
