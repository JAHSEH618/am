package com.am.server.web.support;

import com.am.server.web.dto.TokenTrendDto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Token 走势端点的纯函数支撑：days 钳制与按日补零装配（无 DB 依赖，可单测）。
 * gz
 */
public final class TokenTrendSupport {

    /** days 下限/上限：[1, 90]。daily_summary 行数 = 人数×天数，本就很小，上限只是卫生习惯。 */
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 90;

    private TokenTrendSupport() {}

    public static int clampDays(int days) {
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
    }

    /**
     * 把 {@code [workDate, SUM(input), SUM(output)]} 投影行装配成 [from, toInclusive]
     * 逐日序列，缺失日补零；date 为 ISO yyyy-MM-dd，升序。
     */
    public static List<TokenTrendDto.DailyPoint> fillDaily(
            LocalDate from, LocalDate toInclusive, List<Object[]> rows) {
        Map<LocalDate, long[]> byDate = new HashMap<>();
        if (rows != null) {
            for (Object[] row : rows) {
                LocalDate day = toLocalDate(row[0]);
                if (day == null) {
                    continue;
                }
                byDate.put(day, new long[]{toLong(row[1]), toLong(row[2])});
            }
        }
        List<TokenTrendDto.DailyPoint> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(toInclusive); d = d.plusDays(1)) {
            long[] v = byDate.get(d);
            out.add(new TokenTrendDto.DailyPoint(
                    d.toString(), v == null ? 0L : v[0], v == null ? 0L : v[1]));
        }
        return out;
    }

    private static LocalDate toLocalDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        return null;
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
