package com.am.server.system.web;

import com.am.server.aggregator.DailySummaryAggregator;
import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.monitor.MonitorTarget;
import com.am.server.domain.monitor.MonitorTargetRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.MonitorTargetDto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 活跃 Agent 开关管理端接口（系统设置页"活跃 Agent" Tab 用）。
 *
 * <p>路径前缀 {@code /api/v1/admin/monitor-targets}，全部走 {@code AdminTokenInterceptor} 鉴权——
 * 登录态 session 或 X-Admin-Token 头任一通过即可。
 * gz
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/monitor-targets")
public class MonitorTargetAdminController {

    private static final Logger log = LoggerFactory.getLogger(MonitorTargetAdminController.class);

    /**
     * agent 开关变更后异步重算近 N 天的 daily_summary，让员工数据 / 报告里"按 daily_summary 累加"的
     * 指标（totalTokens / sessions / msgs）反映新的白名单口径。
     * <p>取 14 天既覆盖 People 默认的自然周 / 上周环比窗口，又对 dev / 中等规模生产可控（O(14 × users)）。
     * 更远的历史可由 P1.e 历史重算入口手动批量回填。
     *
     * <p>v2.10.1：原实现在 HTTP 同步路径上串行重算 14 天，叠加 ensureFresh 的实例级锁，
     * 生产数据量下耗时远超前端 axios 的 15s timeout，表现为"agent 关不掉 + 员工详情同时被挂死"。
     * 现改为 {@link DailySummaryAggregator#enqueueRefresh}：丢进单线程聚合 executor，
     * HTTP 立刻返回；15s 防抖窗口内同一天多次切换只算 1 次。
     */
    private static final int REFRESH_DAYS_AFTER_TOGGLE = 14;

    private final MonitorTargetRepository monitorTargetRepository;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final AiSessionRepository aiSessionRepository;
    private final DailySummaryAggregator dailySummaryAggregator;

    /**
     * 全量（含 enabled=0）。响应里附带每个 agent 最近 7 天的会话数，便于管理员决策。
     */
    @GetMapping
    public R<List<MonitorTargetAdminDto>> listAll() {
        LocalDateTime since = LocalDate.now().minusDays(7).atStartOfDay();
        java.util.Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        Map<String, Long> recentCounts = activeTypes.isEmpty()
                ? java.util.Map.of()
                : aiSessionRepository
                        .countSessionsByTargetTypeSinceAndActiveTypesIn(since, activeTypes).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                row -> (String) row[0],
                                row -> row[1] == null ? 0L : ((Number) row[1]).longValue()));

        List<MonitorTargetAdminDto> out = new ArrayList<>();
        for (MonitorTarget t : monitorTargetRepository.findAll().stream()
                .sorted(Comparator
                        .comparingInt((MonitorTarget m) -> m.getSortNo() == null ? 100 : m.getSortNo())
                        .thenComparingLong(MonitorTarget::getId))
                .toList()) {
            MonitorTargetDto base = MonitorTargetDto.of(t);
            out.add(new MonitorTargetAdminDto(
                    base.typeCode(),
                    base.typeName(),
                    base.enabled(),
                    base.displayColor(),
                    base.sortNo(),
                    base.description(),
                    recentCounts.getOrDefault(t.getTypeCode(), 0L)));
        }
        return R.ok(out);
    }

    /**
     * 切换某 agent 的激活开关。
     * <ul>
     *   <li>{@code value=1} → enabled=1，进入展示 + 聚合白名单</li>
     *   <li>{@code value=0} → enabled=0，客户端继续采集但所有展示 / 聚合都排除</li>
     * </ul>
     * 改动后立即刷新 {@link ActiveTargetTypesProvider} 缓存，不重启。
     */
    @PatchMapping("/{typeCode}/enabled")
    @Transactional
    public R<MonitorTargetDto> setEnabled(
            @PathVariable String typeCode,
            @RequestParam int value) {
        if (value != 0 && value != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "value 必须是 0 或 1");
        }
        MonitorTarget t = monitorTargetRepository.findByTypeCode(typeCode)
                .orElseThrow(() -> new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                        "unknown target: " + typeCode));
        t.setEnabled(value);
        t.setUpdatedTime(LocalDateTime.now());
        monitorTargetRepository.save(t);
        activeTargetTypesProvider.invalidate();

        // 异步把最近 N 天 daily_summary 按新白名单回填——绝对不能在 HTTP 线程里同步跑：
        // ensureFresh 是实例级 synchronized，14 天串行 + 生产数据量下单天就接近秒级，
        // 之前会把整个接口卡到 >15s 超时，并把员工详情接口也一起堵死。
        // enqueueRefresh 走 single-thread executor + 15s 防抖，本身瞬间返回，
        // 真正的 aggregate 在 15s 后跑，届时本事务早已提交，monitor_target 行也已可见。
        List<LocalDate> dates = new ArrayList<>(REFRESH_DAYS_AFTER_TOGGLE);
        LocalDate today = LocalDate.now();
        for (int i = 0; i < REFRESH_DAYS_AFTER_TOGGLE; i++) {
            dates.add(today.minusDays(i));
        }
        dailySummaryAggregator.enqueueRefresh(dates);
        log.info("monitor_target {} enabled set to {}, scheduled async refresh for last {} days",
                typeCode, value, REFRESH_DAYS_AFTER_TOGGLE);
        return R.ok(MonitorTargetDto.of(t));
    }

    /** 含"最近 7 天会话数"的管理员视图 DTO。 */
    public record MonitorTargetAdminDto(
            String typeCode,
            String typeName,
            Integer enabled,
            String displayColor,
            Integer sortNo,
            String description,
            long recent7dSessionCount) {}
}
