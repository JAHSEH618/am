package com.am.server.web;

import com.am.server.aggregator.BaselineEventBackfillService;
import com.am.server.aggregator.DailySummaryAggregator;
import com.am.server.common.R;
import com.am.server.domain.summary.DailySummary;
import com.am.server.domain.summary.DailySummaryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理类后端运维接口
 * v2.0 起仅暴露日报手动触发与查询；后续 Phase 2/3 的"重算 / 回补 / 清理"任务都汇集到这里
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final DailySummaryAggregator aggregator;
    private final DailySummaryRepository summaryRepository;
    private final BaselineEventBackfillService baselineBackfill;

    /**
     * 触发指定日期的 DailySummary 聚合。
     * <p>不传 date 默认聚合"昨天"（与定时任务口径一致）；传 today 也允许，但当日数据未收口，结果会偏低。
     * <p>用法：
     * <pre>
     *   POST /api/v1/admin/aggregate-daily               # 昨天
     *   POST /api/v1/admin/aggregate-daily?date=2026-05-06
     * </pre>
     */
    @PostMapping("/aggregate-daily")
    public R<Map<String, Object>> aggregateDaily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date == null ? LocalDate.now().minusDays(1) : date;
        long t0 = System.currentTimeMillis();
        int users = aggregator.aggregate(target);
        long elapsed = System.currentTimeMillis() - t0;
        log.info("admin aggregate-daily date={} users={} elapsed={}ms", target, users, elapsed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", target.toString());
        out.put("users_touched", users);
        out.put("elapsed_ms", elapsed);
        return R.ok(out);
    }

    /** 查看指定日期所有员工的 DailySummary，便于验收聚合结果 */
    @GetMapping("/daily-summary")
    public R<List<DailySummary>> listByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(summaryRepository.findByWorkDate(date));
    }

    /**
     * 一次性给 [from, to] 区间内 event 流缺失 baseline 的历史会话补
     * SESSION_OPEN / TOKEN_DELTA / MESSAGE_DELTA。
     * <p>使用场景：v2.7 / v2.8 期间客户端 backfill 上来的历史会话，event 流里只有 TOOL_CALL，
     * 导致按 event 切片的"项目透视 detail / 大盘 Top / 员工详情 Top 模型 / Top 项目"在历史
     * 窗口下全 0。<br>
     * 算法幂等：检查 ai_session_event 已有的 eventType，已有则跳过；多次跑同一区间无副作用。
     * 完成后会把涉及到的 work_date 喂给 daily_summary 异步重聚（debounce 15s）。
     * <p>用法：
     * <pre>
     *   POST /api/v1/admin/backfill-baseline-events?from=2026-04-01&amp;to=2026-04-30
     * </pre>
     */
    @PostMapping("/backfill-baseline-events")
    public R<Map<String, Object>> backfillBaselineEvents(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        long t0 = System.currentTimeMillis();
        BaselineEventBackfillService.BackfillResult r = baselineBackfill.backfill(from, to);
        long elapsed = System.currentTimeMillis() - t0;
        log.info("admin backfill-baseline-events from={} to={} sessions={} session_opens={} token_deltas={} message_deltas={} elapsed={}ms",
                r.from, r.to, r.sessionsScanned, r.sessionOpensCreated, r.tokenDeltasCreated,
                r.messageDeltasCreated, elapsed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", r.from.toString());
        out.put("to", r.to.toString());
        out.put("sessions_scanned", r.sessionsScanned);
        out.put("session_opens_created", r.sessionOpensCreated);
        out.put("token_deltas_created", r.tokenDeltasCreated);
        out.put("message_deltas_created", r.messageDeltasCreated);
        out.put("dates_touched", r.affectedDates.size());
        out.put("elapsed_ms", elapsed);
        return R.ok(out);
    }

    /**
     * 批量回填 [from, to] 区间内每一天的 daily_summary。
     * <p>使用场景：客户端 backfill 历史会话上来后，hourlyJob 只跑 today + yesterday，
     * 历史日期的 daily_summary 永远生不出来 → 员工数据选 4 月窗口全 0。这个端点用一次性
     * 把这段窗口扫一遍：每天调一次 {@link DailySummaryAggregator#aggregate}，幂等。
     * <p>用法：
     * <pre>
     *   POST /api/v1/admin/aggregate-range?from=2026-04-01&amp;to=2026-04-30
     * </pre>
     */
    @PostMapping("/aggregate-range")
    public R<Map<String, Object>> aggregateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from.isAfter(to)) {
            LocalDate tmp = from; from = to; to = tmp;
        }
        long t0 = System.currentTimeMillis();
        int days = 0, totalUsers = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            try {
                totalUsers += aggregator.aggregate(d);
                days++;
            } catch (Exception e) {
                log.warn("aggregate-range: date={} failed reason={}", d, e.toString());
            }
        }
        long elapsed = System.currentTimeMillis() - t0;
        log.info("admin aggregate-range from={} to={} days={} users_total={} elapsed={}ms",
                from, to, days, totalUsers, elapsed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("days_processed", days);
        out.put("users_touched_total", totalUsers);
        out.put("elapsed_ms", elapsed);
        return R.ok(out);
    }
}
