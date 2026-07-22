package com.am.server.web;

import com.am.server.aggregator.CapabilityDailyAggregator;
import com.am.server.common.R;
import com.am.server.web.dto.CapabilityMatrixDto;
import com.am.server.web.dto.CapabilityRankingRowDto;
import com.am.server.web.dto.CapabilityTrendPointDto;
import com.am.server.web.dto.CapabilityUserItemDto;
import com.am.server.web.support.CapabilityStatSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * /capability 能力使用分析（Skill / 插件 MCP 两 tab 共用）：排行、趋势、人×维度矩阵、按人下钻。
 * 全部查询只打 capability_daily 预聚合表；窗口含今日时先经 {@link CapabilityDailyAggregator#ensureFresh}
 * 收口（60s TTL 节流），保证今日数据最长滞后约 1 分钟而非 1 小时。
 * 挂 /api/v1/admin/** 前缀，admin session / X-Admin-Token 与 ConsoleAccessFilter 自动覆盖。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/capability")
public class CapabilityController {

    private static final Duration ENSURE_FRESH_TTL = Duration.ofSeconds(60);

    private final CapabilityStatSupport capabilityStatSupport;
    private final CapabilityDailyAggregator capabilityDailyAggregator;

    @GetMapping("/ranking")
    public R<List<CapabilityRankingRowDto>> ranking(
            @RequestParam(defaultValue = "skill") String kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int limit) {
        LocalDate[] window = window(from, to);
        return R.ok(capabilityStatSupport.ranking(kind, window[0], window[1], limit));
    }

    @GetMapping("/trend")
    public R<List<CapabilityTrendPointDto>> trend(
            @RequestParam(defaultValue = "skill") String kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] window = window(from, to);
        return R.ok(capabilityStatSupport.trend(kind, window[0], window[1]));
    }

    @GetMapping("/matrix")
    public R<CapabilityMatrixDto> matrix(
            @RequestParam(defaultValue = "skill") String kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] window = window(from, to);
        return R.ok(capabilityStatSupport.matrix(kind, window[0], window[1]));
    }

    @GetMapping("/user")
    public R<List<CapabilityUserItemDto>> user(
            @RequestParam("user_code") String userCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] window = window(from, to);
        return R.ok(capabilityStatSupport.userDrilldown(userCode, window[0], window[1]));
    }

    /** 缺省窗口 = 近 30 天；窗口尾包含今日时触发 view-time 收口。 */
    private LocalDate[] window(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (!end.isBefore(today)) {
            capabilityDailyAggregator.ensureFresh(today, ENSURE_FRESH_TTL);
        }
        return new LocalDate[]{start, end};
    }
}
