package com.am.server.aggregator;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.summary.CapabilityDaily;
import com.am.server.domain.summary.CapabilityDailyRepository;
import com.am.server.insight.aggregate.SlashHitsJsonSupport;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 能力使用日聚合任务（《管理后台-产出归因与能力使用分析 v1.0》§3.3）：把
 * {@code ai_session_message.slash_hits_json}（skill / nl_skill / 插件命名空间技能）与
 * {@code ai_session_event.tool_name}（MCP 工具调用）按 work_date × user_code × kind × item ×
 * sub_item 压成 {@code capability_daily}。/capability 页的排行 / 趋势 / 矩阵不做实时扫表，只打本表。
 *
 * <p><b>整日重算</b>：与 {@link DailySummaryAggregator} 的 per-user upsert 不同，本表 item 集合
 * 会随重扫变化（消息回填 / nl_skill reconcile 可能增删行），按 (user, key) upsert 无法收敛删除，
 * 故每次 {@link #aggregate} 对整日 delete + insert——单日行数 = 活跃用户 × 少量 item，代价可忽略，
 * 且天然幂等，回溯任务直接逐日调用即可。
 *
 * <p><b>MCP 双路数据源</b>（§3.2）：主路 TOOL_CALL 事件（有 idx_tool_name，全程写入）；兜底路
 * content_parts_json 的 tool_call part——仅对「当日无 MCP TOOL_CALL 事件」的会话启用（对齐
 * DailySummaryAggregator v2.6 对 hermes 类 provider 的两路信号合并思路），不与主路重复计数。
 *
 * <p>调度：每日 00:15 (Asia/Shanghai) 跑昨日 + 每小时 :10 滚动跑 today + yesterday（错开
 * daily_summary 的 00:05 / 整点，避免同刻抢 DB）；查询侧可调 {@link #ensureFresh} view-time 收口。
 * 历史回溯由 {@code CapabilityDailyBackfillPatch} 启动期一次性补齐。
 * gz
 */
@Component
@RequiredArgsConstructor
public class CapabilityDailyAggregator {

    private static final Logger log = LoggerFactory.getLogger(CapabilityDailyAggregator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 sys_config 子 key 保持一致；改名会废掉历史 cron 配置，谨慎。 */
    public static final String TASK_CODE_DAILY = "capability_daily_daily";
    public static final String TASK_CODE_HOURLY = "capability_daily_hourly";

    /** 列宽约束（与 schema.sql 对齐），超长维度名截断入库。 */
    private static final int ITEM_MAX_LEN = 128;
    private static final int SUB_ITEM_MAX_LEN = 256;

    /** 自代理：让内部自调用经过 Spring 代理，@Transactional(replaceDay) 才生效。见 DailySummaryAggregator 同款。 */
    @Autowired
    @Lazy
    private CapabilityDailyAggregator self;

    private final AiSessionMessageRepository messageRepository;
    private final AiSessionEventRepository eventRepository;
    private final CapabilityDailyRepository capabilityRepository;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final DynamicScheduledTaskManager scheduledTaskManager;

    /** {@link #ensureFresh} 的 TTL 快速路径 + per-date 互斥，语义与 DailySummaryAggregator 一致。 */
    private final ConcurrentHashMap<LocalDate, LocalDateTime> lastAggregatedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LocalDate, Object> ensureFreshLocks = new ConcurrentHashMap<>();

    @PostConstruct
    public void registerDynamicTasks() {
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE_DAILY,
                        "能力使用每日聚合（昨日 capability_daily）",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 15 0 * * *",
                        true,
                        true,
                        true,
                        "每天 00:15 (Asia/Shanghai) 重算昨日 capability_daily（skill / MCP / 插件维度），定型能力分析页历史数据。",
                        "Asia/Shanghai"),
                this::dailyJob);
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE_HOURLY,
                        "能力使用小时滚动聚合（today + yesterday）",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 10 * * * *",
                        true,
                        true,
                        true,
                        "每小时 :10 滚动重算 today + yesterday 的 capability_daily，让能力分析页最长滞后 1 小时。",
                        "Asia/Shanghai"),
                this::hourlyJob);
    }

    public void dailyJob() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try {
            int rows = aggregate(yesterday);
            lastAggregatedAt.put(yesterday, LocalDateTime.now());
            log.info("capability daily aggregated: date={} rows={}", yesterday, rows);
        } catch (Exception e) {
            log.error("capability daily aggregate failed: date={}", yesterday, e);
        }
    }

    public void hourlyJob() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        try {
            int t = aggregate(today);
            int y = aggregate(yesterday);
            lastAggregatedAt.put(today, LocalDateTime.now());
            lastAggregatedAt.put(yesterday, LocalDateTime.now());
            log.info("hourly capability daily aggregated: today={} rows={} yesterday={} rows={}",
                    today, t, yesterday, y);
        } catch (Exception e) {
            log.error("hourly capability daily aggregate failed", e);
        }
    }

    /**
     * 节流的按需聚合：/capability 查询前调用，确保窗口尾日接近实时。
     * per-date 锁 + 双检 TTL，不同日期可并发。
     */
    public boolean ensureFresh(LocalDate date, Duration ttl) {
        LocalDateTime last = lastAggregatedAt.get(date);
        if (last != null && Duration.between(last, LocalDateTime.now()).compareTo(ttl) < 0) {
            return false;
        }
        Object lock = ensureFreshLocks.computeIfAbsent(date, d -> new Object());
        synchronized (lock) {
            last = lastAggregatedAt.get(date);
            if (last != null && Duration.between(last, LocalDateTime.now()).compareTo(ttl) < 0) {
                return false;
            }
            try {
                aggregate(date);
                lastAggregatedAt.put(date, LocalDateTime.now());
                return true;
            } catch (Exception e) {
                log.warn("capability ensureFresh failed: date={} reason={}", date, e.toString());
                return false;
            }
        }
    }

    /**
     * 重算一整天：三路取数（slash hits / MCP 事件 / MCP parts 兜底）在内存合并计数，
     * 再经 {@link #replaceDay} 一个事务内 delete + insert。返回写入行数。
     */
    public int aggregate(LocalDate workDate) {
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            log.info("capability aggregate: date={} no active target types, skip", workDate);
            return 0;
        }
        LocalDateTime dayStart = workDate.atStartOfDay();
        LocalDateTime dayEnd = workDate.plusDays(1).atStartOfDay();

        Map<Key, Counts> acc = new LinkedHashMap<>();

        // 1) skill / nl_skill / plugin_ns：slash_hits_json（仅 user 消息，ingest 时已注好）
        for (Object[] row : messageRepository.loadSlashHitsJsonOnlyInWindowGlobal(dayStart, dayEnd, activeTypes)) {
            if (row == null || row.length < 3 || row[0] == null) {
                continue;
            }
            String userCode = row[0].toString();
            Long sessionId = row[1] instanceof Number n ? n.longValue() : null;
            String hitsJson = row[2] == null ? null : row[2].toString();
            for (Map<String, String> hit : SlashHitsJsonSupport.parseHits(hitsJson)) {
                String kind = hit.getOrDefault("kind", "");
                if (!CapabilityDaily.KIND_SKILL.equals(kind) && !CapabilityDaily.KIND_NL_SKILL.equals(kind)) {
                    continue; // command / noise 不属于能力页范围
                }
                String item = CapabilityItemParser.normalizeSkillItem(hit.get("token"));
                if (item == null) {
                    continue;
                }
                add(acc, userCode, kind, item, CapabilityDaily.SUB_ITEM_ROLLUP, sessionId, 1);
                CapabilityItemParser.parseNamespaceSkill(item).ifPresent(ns -> {
                    add(acc, userCode, CapabilityDaily.KIND_PLUGIN_NS, ns.namespace(),
                            CapabilityDaily.SUB_ITEM_ROLLUP, sessionId, 1);
                    add(acc, userCode, CapabilityDaily.KIND_PLUGIN_NS, ns.namespace(),
                            ns.skill(), sessionId, 1);
                });
            }
        }

        // 2) MCP 主路：TOOL_CALL 事件。记录已覆盖的会话，兜底路按会话让位。
        Set<Long> mcpEventSessions = new HashSet<>();
        for (Object[] row : eventRepository.aggregateMcpToolCallRowsInWindowAndTargetTypeIn(
                dayStart, dayEnd, activeTypes)) {
            if (row == null || row.length < 4 || row[0] == null) {
                continue;
            }
            String userCode = row[0].toString();
            Long sessionId = row[1] instanceof Number n ? n.longValue() : null;
            String toolName = row[2] == null ? null : row[2].toString();
            int count = row[3] instanceof Number n ? n.intValue() : 0;
            CapabilityItemParser.parseMcpToolName(toolName).ifPresent(mt -> {
                if (sessionId != null) {
                    mcpEventSessions.add(sessionId);
                }
                addMcp(acc, userCode, mt, sessionId, count);
            });
        }

        // 3) MCP 兜底路：content_parts_json 的 tool_call part，仅限当日无 MCP 事件的会话。
        for (Object[] row : messageRepository.loadMcpContentPartsInWindowGlobal(dayStart, dayEnd, activeTypes)) {
            if (row == null || row.length < 3 || row[0] == null) {
                continue;
            }
            String userCode = row[0].toString();
            Long sessionId = row[1] instanceof Number n ? n.longValue() : null;
            if (sessionId != null && mcpEventSessions.contains(sessionId)) {
                continue;
            }
            for (String toolName : mcpToolNamesInParts(row[2] == null ? null : row[2].toString())) {
                CapabilityItemParser.parseMcpToolName(toolName)
                        .ifPresent(mt -> addMcp(acc, userCode, mt, sessionId, 1));
            }
        }

        List<CapabilityDaily> rows = new ArrayList<>(acc.size());
        for (Map.Entry<Key, Counts> en : acc.entrySet()) {
            Key k = en.getKey();
            Counts c = en.getValue();
            CapabilityDaily r = new CapabilityDaily();
            r.setWorkDate(workDate);
            r.setUserCode(k.userCode());
            r.setKind(k.kind());
            r.setItem(k.item());
            r.setSubItem(k.subItem());
            r.setInvokeCount(c.invokes);
            r.setSessionCount(c.sessions.size());
            rows.add(r);
        }
        return self.replaceDay(workDate, rows);
    }

    /** 整日 delete + insert，单事务；行数很小（活跃用户 × 少量 item），无需分批。 */
    @Transactional
    public int replaceDay(LocalDate workDate, List<CapabilityDaily> rows) {
        capabilityRepository.deleteByWorkDate(workDate);
        if (!rows.isEmpty()) {
            capabilityRepository.saveAll(rows);
        }
        return rows.size();
    }

    /** MCP 计数恒双粒度：sub_item='' 汇总行 + sub_item=tool 明细行（查询按粒度过滤，见实体注释）。 */
    private static void addMcp(Map<Key, Counts> acc, String userCode,
                               CapabilityItemParser.McpTool mt, Long sessionId, int count) {
        add(acc, userCode, CapabilityDaily.KIND_MCP, mt.server(), CapabilityDaily.SUB_ITEM_ROLLUP,
                sessionId, count);
        add(acc, userCode, CapabilityDaily.KIND_MCP, mt.server(), mt.tool(), sessionId, count);
    }

    private static void add(Map<Key, Counts> acc, String userCode, String kind,
                            String item, String subItem, Long sessionId, int count) {
        if (count <= 0 || userCode == null || userCode.isBlank()) {
            return;
        }
        Key key = new Key(userCode, kind, truncate(item, ITEM_MAX_LEN), truncate(subItem, SUB_ITEM_MAX_LEN));
        Counts c = acc.computeIfAbsent(key, k -> new Counts());
        c.invokes += count;
        if (sessionId != null) {
            c.sessions.add(sessionId);
        }
    }

    /** content_parts_json → 其中全部 tool_call part 的 tool_name（含非 MCP 的，由调用方过滤）。 */
    private static List<String> mcpToolNamesInParts(String contentPartsJson) {
        List<String> out = new ArrayList<>();
        if (contentPartsJson == null || contentPartsJson.isBlank()) {
            return out;
        }
        try {
            JsonNode arr = MAPPER.readTree(contentPartsJson);
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode part : arr) {
                if (!"tool_call".equals(part.path("type").asText(""))) {
                    continue;
                }
                String toolName = part.path("tool_name").asText("");
                if (!toolName.isBlank()) {
                    out.add(toolName);
                }
            }
        } catch (Exception ignored) {
            // best-effort：坏 JSON 跳过该消息
        }
        return out;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private record Key(String userCode, String kind, String item, String subItem) {}

    private static final class Counts {
        int invokes;
        final Set<Long> sessions = new HashSet<>();
    }
}
