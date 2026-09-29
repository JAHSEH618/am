package com.am.server.web;

import com.am.server.common.R;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.ToolStatDto;
import com.am.server.web.support.SlashCommandStatSupport;
import com.am.server.web.support.TtlSingleFlightCache;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Slash Commands 排行（用户输入框主动 {@code /command}，非模型 TOOL_CALL 事件）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/stats/tools")
public class ToolStatController {

    private final SlashCommandStatSupport slashCommandStatSupport;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;

    /**
     * 排行是整窗 user 消息扫描 + 逐行解析 slash_hits_json，同一窗口会被多个标签页、切窗重试反复算。
     * 45s TTL + single-flight，与模型分布 / 项目透视同口径；key = (from, to, activeTypes, limit)。
     */
    private static final Duration WINDOW_CACHE_TTL = Duration.ofSeconds(45);
    private final TtlSingleFlightCache<String, List<ToolStatDto>> rankingCache =
            new TtlSingleFlightCache<>(WINDOW_CACHE_TTL);

    @GetMapping
    public R<List<ToolStatDto>> tools(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "20") int limit) {

        LocalDate today = LocalDate.now();
        LocalDate defFrom = today.with(DayOfWeek.MONDAY);
        LocalDate defTo = defFrom.plusDays(6);
        LocalDateTime fromTs = (from == null ? defFrom : from).atStartOfDay();
        LocalDateTime toEx = (to == null ? defTo : to).plusDays(1).atStartOfDay();

        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            return R.ok(new ArrayList<>());
        }
        int cap = Math.max(limit, 1);
        String key = fromTs + "|" + toEx + "|"
                + activeTypes.stream().sorted().collect(Collectors.joining(",")) + "|" + cap;
        return R.ok(rankingCache.get(key, () -> List.copyOf(
                slashCommandStatSupport.topCommandTokensGlobal(fromTs, toEx, activeTypes, cap))));
    }
}
