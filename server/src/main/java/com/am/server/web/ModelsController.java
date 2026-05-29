package com.am.server.web;

import com.am.server.common.R;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.ModelDistributionDto;
import com.am.server.web.dto.ModelHeatmapDto;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型分布与 token 燃烧分析（v2.4 重构：窗内统计基于 message_time 切片）
 *
 * <p>接口：
 * <ul>
 *   <li>GET /api/v1/models/distribution?from&to       模型 token 占比 + session/user count</li>
 *   <li>GET /api/v1/models/heatmap?from&to            窗口期内 × 模型 token 燃烧热力图</li>
 * </ul>
 *
 * <p><b>窗内语义</b>：所有数字都基于 ai_session_event.event_time 切片 ai_session_event 流水。
 * 历史版本直接对 ai_session 累积字段求和并按 last_activity 入格，会让长会话在最后活跃日
 * 看起来"突然烧了几十万 token"——本次重构按事件真实发生时间归到对应的日 / 模型。
 *
 * <p><b>input / output</b>：窗内 in/out 来自 {@code ai_session_event.input_tokens_delta} /
 * {@code output_tokens_delta}（ingest 按会话累积值差额拆分写入）。存量库在启动时会按
 * {@link com.am.server.system.AiSessionEventTokenDeltaSchemaPatches} 回填历史行。
 *
 * <p><b>无效会话</b>：窗内聚合均排除 {@code invalid_reason} 非空的会话，且仅统计当前启用的
 * {@code target_type}（与员工数据 / 项目透视同口径）。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/models")
public class ModelsController {

    private static final int DEFAULT_HEATMAP_DAYS = 14;
    private static final int HEATMAP_TOP_MODELS = 8;

    private final AiSessionEventRepository eventRepository;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;

    @GetMapping("/distribution")
    public R<List<ModelDistributionDto>> distribution(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDateTime[] window = resolveWindow(from, to);
        // v2.10：按 active target_type 白名单过滤；空白名单 → 整张表都没数据。
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            return R.ok(new ArrayList<>());
        }
        // event SQL 输出：[model, inputTokens, outputTokens, messageCount, sessionCount, userCount]
        List<Object[]> rows = eventRepository.aggregateByModelInWindowAndTargetTypeIn(window[0], window[1], activeTypes);

        long denom = 0L;
        for (Object[] r : rows) {
            denom += toLong(r[1]) + toLong(r[2]);
        }
        if (denom == 0L) denom = 1L;

        List<ModelDistributionDto> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String model = asString(r[0]);
            if (model == null) continue;
            long input = toLong(r[1]);
            long output = toLong(r[2]);
            long total = input + output;
            ModelDistributionDto d = new ModelDistributionDto();
            d.setModel(model);
            d.setInputTokens(input);
            d.setOutputTokens(output);
            d.setTotalTokens(total);
            d.setSessionCount((int) toLong(r[4]));
            d.setUserCount((int) toLong(r[5]));
            d.setPercent(round1(total * 100.0 / denom));
            out.add(d);
        }
        out.sort(Comparator.comparingLong(ModelDistributionDto::getTotalTokens).reversed());
        return R.ok(out);
    }

    @GetMapping("/heatmap")
    public R<ModelHeatmapDto> heatmap(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer days) {
        LocalDate startDate;
        LocalDate endDate;
        if (from != null || to != null) {
            LocalDateTime[] window = resolveWindow(from, to);
            startDate = window[0].toLocalDate();
            endDate = window[1].toLocalDate().minusDays(1);
        } else {
            int n = (days == null || days <= 0) ? DEFAULT_HEATMAP_DAYS : Math.min(days, 60);
            endDate = LocalDate.now();
            startDate = endDate.minusDays(n - 1);
        }
        int n = (int) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        LocalDateTime t0 = startDate.atStartOfDay();
        LocalDateTime t1 = endDate.plusDays(1).atStartOfDay();

        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            // 空白名单：仍然返回完整 X 轴 N 天，topModels / cells 为空，前端展示一片"无数据"。
            List<String> daysX = new ArrayList<>(n);
            for (int i = 0; i < n; i++) daysX.add(startDate.plusDays(i).toString());
            return R.ok(new ModelHeatmapDto(new ArrayList<>(), daysX, new ArrayList<>()));
        }

        // [date, model, totalTokens]
        List<Object[]> raw = eventRepository.aggregateModelHeatmapAndTargetTypeIn(t0, t1, activeTypes);

        // 第一遍：算窗内 Top N 模型（按 token）
        Map<String, Long> modelTokens = new HashMap<>();
        for (Object[] r : raw) {
            String model = asString(r[1]);
            if (model == null) continue;
            modelTokens.merge(model, toLong(r[2]), Long::sum);
        }
        List<String> topModels = modelTokens.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(HEATMAP_TOP_MODELS)
                .map(Map.Entry::getKey)
                .toList();
        Map<String, Integer> modelIndex = new HashMap<>();
        for (int i = 0; i < topModels.size(); i++) modelIndex.put(topModels.get(i), i);

        // X 轴：固定 N 天
        List<String> daysX = new ArrayList<>(n);
        Map<String, Integer> dayIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String d = startDate.plusDays(i).toString();
            daysX.add(d);
            dayIndex.put(d, i);
        }

        // 第二遍：填 cells
        Map<Long, Long> cellTokens = new HashMap<>();
        for (Object[] r : raw) {
            LocalDate d = asLocalDate(r[0]);
            String model = asString(r[1]);
            if (d == null || model == null) continue;
            Integer mi = modelIndex.get(model);
            if (mi == null) continue;
            Integer di = dayIndex.get(d.toString());
            if (di == null) continue;
            long key = (long) di * 100 + mi;
            cellTokens.merge(key, toLong(r[2]), Long::sum);
        }
        List<ModelHeatmapDto.Cell> cells = new ArrayList<>(cellTokens.size());
        for (Map.Entry<Long, Long> e : cellTokens.entrySet()) {
            int di = (int) (e.getKey() / 100);
            int mi = (int) (e.getKey() % 100);
            cells.add(new ModelHeatmapDto.Cell(di, mi, e.getValue()));
        }

        return R.ok(new ModelHeatmapDto(topModels, daysX, cells));
    }

    /**
     * 默认窗口口径：自然周（ISO 周一 ~ 周日），与员工数据 / 项目透视 / 分析报告页一致。
     * <p>调用方未传 from/to 时按今天所在的自然周返回。
     */
    private static LocalDateTime[] resolveWindow(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            LocalDate today = LocalDate.now();
            LocalDate monday = today.with(DayOfWeek.MONDAY);
            LocalDate sunday = monday.plusDays(6);
            return new LocalDateTime[]{monday.atStartOfDay(), sunday.plusDays(1).atStartOfDay()};
        }
        LocalDate today = LocalDate.now();
        LocalDate t = (to == null) ? today : to;
        LocalDate f = (from == null) ? t.with(DayOfWeek.MONDAY) : from;
        if (f.isAfter(t)) {
            LocalDate tmp = f; f = t; t = tmp;
        }
        return new LocalDateTime[]{f.atStartOfDay(), t.plusDays(1).atStartOfDay()};
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static LocalDate asLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date sd) return sd.toLocalDate();
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        try {
            return LocalDate.parse(v.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
