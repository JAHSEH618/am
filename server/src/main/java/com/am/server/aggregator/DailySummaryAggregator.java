package com.am.server.aggregator;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEvent;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionEventType;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.ai.AiSessionStatus;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.summary.DailySummary;
import com.am.server.domain.summary.DailySummaryRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * AIWatch 员工日汇总聚合任务（v2.4 重构：按 message_time / event_time 切片，杜绝跨期会话数据污染）
 *
 * <p>调度：每天 0:05 (Asia/Shanghai) 跑前一天，每小时整点滚动跑 today + yesterday。
 * 把 ai_session_message / ai_session_event / git_commit 三类流水按 user_code × work_date 压成
 * daily_summary 一张主键 (user_code, work_date) 的汇总表。
 *
 * <p><b>v2.4 修复的核心问题</b>：旧版本先按 ai_session.last_activity 在当日窗口里命中会话，
 * 再把 session 上的累积 input_tokens / userMessages 计入该日。但 ai_session 表上挂的是
 * 整个生命周期的累积值，跨期会话会把历史消耗全算进 last_activity 那一天，造成 daily_summary
 * 的 token / 消息 / 模型 Top 严重失真，进而污染员工数据、报告、行为标签等所有下游展示。<br>
 * 重构后：当日切片 = ai_session_message.message_time ∈ [00:00, 24:00) 的消息子集 +
 * ai_session_event.event_time 落在该日的事件子集，token / messageCount / firstResponse / retry
 * 等指标全部从这两个原子切片里算出来。
 *
 * <p>v2.11：message / event 查询路径均 JOIN {@code ai_session} 并限制 {@code invalid_reason IS NULL}，
 * 无效会话不进员工数据等下游指标。
 *
 * <p>聚合输出（按 user_code 维度）：
 * <ul>
 *   <li>ai_session_count           当日有 message 或 event 的 distinct ai_session_id</li>
 *   <li>ai_message_count           当日 message_time 切片内的消息总数</li>
 *   <li>total_input_tokens / total_output_tokens  当日切片内 SUM(message.token)</li>
 *   <li>tool_call_count            当日 event_time 切片内 TOOL_CALL 事件数</li>
 *   <li>active_model_top           当日 token 消耗最多的模型（去 null）</li>
 *   <li>ai_active_seconds          v2.6 起：把 message_time + event_time 合并成"活跃信号"流，
 *                                  同 session 内相邻信号间隔 ≤ {@link #ACTIVE_GAP_MS} 视为持续活跃，
 *                                  逐 session 求和（多 session 并行可叠加，可能 > 86400）</li>
 *   <li>ai_active_seconds_union    同上活跃区间做 merge-overlapping 后的并集秒数（多 session 并行只算一次）</li>
 *   <li>ai_first_response_avg_ms   单个 session 第一条 user → 第一条 assistant 间隔的当日均值</li>
 *   <li>ai_thinking_seconds        STATUS_CHANGE 事件流中"进入 → 离开 thinking"时长求和</li>
 *   <li>ai_retry_count             同 session 当日切片内连续 user 消息无 assistant 间隔的次数</li>
 *   <li>ai_models_top3             token 消耗 Top3 模型 JSON</li>
 *   <li>ai_commit_count            当日该用户已入库的 git_commit 条数</li>
 * </ul>
 *
 * <p>v2.0 单实例部署，未启用 ShedLock；多实例部署时再加 @SchedulerLock + LockProvider Bean。
 *
 * gz
 */
@Component
@RequiredArgsConstructor
public class DailySummaryAggregator {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryAggregator.class);

    /** 自代理:让内部自调用经过 Spring 代理,@Transactional(aggregate) 才生效。见 GitCommitIngestService 同款。 */
    @Autowired
    @Lazy
    private DailySummaryAggregator self;

    /** 计算"首次响应"时认为超过这个值就是异常会话（用户开了又消息不连贯），不入平均 */
    private static final long FIRST_RESPONSE_OUTLIER_MS = 30 * 60 * 1000L;

    /**
     * 协作时长口径"硬切阈值"：同 session 内相邻信号间隔 > 此值，视为离开（吃饭 / 开会 / 切走），
     * 不计任何活跃秒数；从下一个信号起重新开段。30 min 与 firstResponse 离群阈值对齐。
     */
    private static final long ACTIVE_GAP_MS = 30 * 60 * 1000L;

    /**
     * 协作时长口径"段内 cap"：相邻信号间隔即便 ≤ {@link #ACTIVE_GAP_MS}，也最多按这个时长计入活跃秒数。
     *
     * <p>语义："一次信号代表用户当下在协作，并最多延续 5 min"——避免一次连续敲键盘后离座 25 min
     * 又回来时整段 25 min 被算成持续活跃（实测 30 min gap 下出现"卡 29 分边界"的虚高，
     * 1 个员工 14.4 h 跨度被算成 13.4 h 协作时长）。具体效果：
     * <ul>
     *   <li>间隔 30s → 计 30s（密集敲键，全部计入）</li>
     *   <li>间隔 25 min → 计 5 min（短暂思考 / 切窗口，保守估）</li>
     *   <li>间隔 35 min → 计 0（视为离开）</li>
     * </ul>
     */
    private static final long ACTIVE_FILL_CAP_MS = 5 * 60 * 1000L;

    private final AiSessionRepository sessionRepository;
    private final AiSessionMessageRepository messageRepository;
    private final AiSessionEventRepository eventRepository;
    private final DailySummaryRepository summaryRepository;
    private final GitCommitRepository gitCommitRepository;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final DynamicScheduledTaskManager scheduledTaskManager;

    /** 任务编码与 sys_config 子 key 保持一致；改名会废掉历史 cron 配置，谨慎。 */
    public static final String TASK_CODE_DAILY = "daily_summary_daily";
    public static final String TASK_CODE_HOURLY = "daily_summary_hourly";

    @PostConstruct
    public void registerDynamicTasks() {
        // v2.10：把原来的 @Scheduled 接管到 DynamicScheduledTaskManager，cron / enabled 走 sys_config。
        // cron 选 0:05 而非 0:00：避开 monitor 的 close-stale-sessions 收口，保证那一刻 lastActivity 已落定。
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE_DAILY,
                        "每日聚合（昨日 daily_summary）",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 5 0 * * *",
                        true,
                        true,
                        true,
                        "每天 00:05 (Asia/Shanghai) 跑昨日全员 daily_summary 聚合，刷新员工数据与分析报告口径。",
                        "Asia/Shanghai"),
                this::dailyJob);
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE_HOURLY,
                        "小时滚动聚合（today + yesterday）",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 0 * * * *",
                        true,
                        true,
                        true,
                        "每小时整点滚动聚合 today + yesterday，让员工数据最长滞后 1 小时；用户访问画像时仍会触发 ensureFresh 进一步收口。",
                        "Asia/Shanghai"),
                this::hourlyJob);
    }

    /**
     * 各日期上次 aggregate 完成的时间。
     * <p>用于 {@link #ensureFresh}：员工数据访问触发的 view-time 聚合做 TTL 节流，
     * 避免并发用户重复算同一天的数据。
     */
    private final ConcurrentHashMap<LocalDate, LocalDateTime> lastAggregatedAt = new ConcurrentHashMap<>();

    /**
     * {@link #ensureFresh} 的 per-date 互斥锁。
     * <p>v2.10.1：原实现把锁加在方法上（实例级 {@code synchronized}），
     * 不同日期之间也会互相串行——一次 14 天的全量重算会把所有员工详情请求都堵死。
     * 改成按日期粒度，不同日期可以并发收口；同一日期仍只允许一个线程跑 aggregate。
     */
    private final ConcurrentHashMap<LocalDate, Object> ensureFreshLocks = new ConcurrentHashMap<>();

    /**
     * v2.9 ingest 触发的"按需追新"队列：每次 ingest AFTER_COMMIT 调 {@link #enqueueRefresh}
     * 把本次涉及到的 work_date 入队；同一天 N 次 enqueue 在 {@link #REFRESH_DEBOUNCE} 内
     * 被合并成 1 次实际 aggregate，避免高频上报压垮 DB。
     */
    private static final long REFRESH_DEBOUNCE_MS = 15_000L;
    private final ConcurrentHashMap<LocalDate, ScheduledFuture<?>> pendingRefresh = new ConcurrentHashMap<>();
    private final ScheduledExecutorService refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "daily-summary-refresh");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        refreshExecutor.shutdownNow();
    }

    /**
     * 把本次 ingest 涉及的工作日入队，{@link #REFRESH_DEBOUNCE_MS} 后由后台线程跑一次
     * {@link #aggregate}。同一天在 debounce 窗口内多次入队只会执行 1 次（取消前一次延迟任务）。
     *
     * <p>调用方：{@link com.am.server.agent.ingest.AbstractAiSessionIngestService}
     * 在 AFTER_COMMIT 时机派发，保证后台线程能读到本次 ingest 写入的 event / message。
     */
    public void enqueueRefresh(Collection<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return;
        }
        for (LocalDate d : dates) {
            if (d == null) continue;
            pendingRefresh.compute(d, (date, prev) -> {
                if (prev != null) {
                    prev.cancel(false);
                }
                return refreshExecutor.schedule(() -> {
                    try {
                        int users = self.aggregate(date);
                        lastAggregatedAt.put(date, LocalDateTime.now());
                        if (users > 0) {
                            log.debug("daily summary refreshed (debounced): date={} users={}", date, users);
                        }
                    } catch (Exception ex) {
                        log.warn("daily summary refresh failed: date={} reason={}", date, ex.toString());
                    } finally {
                        pendingRefresh.remove(date);
                    }
                }, REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            });
        }
    }

    /**
     * 每日 0:05 (Asia/Shanghai) 跑前一天的聚合。
     * <p>v2.10 起由 {@link DynamicScheduledTaskManager} 接管调度，cron 见 sys_config
     * {@code scheduling.daily_summary_daily.cron}；本方法保留为可被手动触发的"业务体"。
     */
    public void dailyJob() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try {
            int touched = self.aggregate(yesterday);
            log.info("daily summary aggregated: date={} users={}", yesterday, touched);
        } catch (Exception e) {
            log.error("daily summary aggregate failed: date={}", yesterday, e);
        }
    }

    /**
     * 每小时整点跑一次 today + yesterday 的滚动聚合。
     * <p>问题背景：原来只在 0:05 跑前一天 → daily_summary 当天为空 → 员工数据"会话数 / 协作时长"
     *   等指标会比"会话列表（实时查 ai_session）"少今天产生的部分。
     *   小时级滚动让 daily_summary 最长滞后 1 小时；用户访问员工数据时还会触发 ensureFresh 进一步收口。
     * <p>v2.10 起由 {@link DynamicScheduledTaskManager} 接管调度，cron 见 sys_config
     * {@code scheduling.daily_summary_hourly.cron}。
     */
    public void hourlyJob() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        try {
            int t = self.aggregate(today);
            int y = self.aggregate(yesterday);
            lastAggregatedAt.put(today, LocalDateTime.now());
            lastAggregatedAt.put(yesterday, LocalDateTime.now());
            log.info("hourly daily summary aggregated: today={} users={} yesterday={} users={}",
                    today, t, yesterday, y);
        } catch (Exception e) {
            log.error("hourly daily summary aggregate failed", e);
        }
    }

    /**
     * 节流的"按需聚合"。员工数据 / 报告页查询前主动调用，确保看到的 daily_summary 数据接近实时。
     *
     * <p>锁粒度：per-date。不同日期可以并发跑 aggregate（典型场景是用户打开员工详情时
     * 按窗口逐天 ensureFresh，多个用户访问不同窗口不会互相阻塞）。同一日期仍串行，
     * 通过双检 TTL 让重复请求快速跳过。
     */
    public boolean ensureFresh(LocalDate date, Duration ttl) {
        // 快速路径：TTL 内直接跳过，绝大多数稳态请求走这里
        LocalDateTime last = lastAggregatedAt.get(date);
        if (last != null && Duration.between(last, LocalDateTime.now()).compareTo(ttl) < 0) {
            return false;
        }
        Object lock = ensureFreshLocks.computeIfAbsent(date, d -> new Object());
        synchronized (lock) {
            // 进锁后双检——前一个等锁者刚算完，TTL 内的就别重复算了
            last = lastAggregatedAt.get(date);
            if (last != null && Duration.between(last, LocalDateTime.now()).compareTo(ttl) < 0) {
                return false;
            }
            try {
                self.aggregate(date);
                lastAggregatedAt.put(date, LocalDateTime.now());
                return true;
            } catch (Exception e) {
                log.warn("ensureFresh aggregate failed: date={} reason={}", date, e.toString());
                return false;
            }
        }
    }

    /**
     * 全量聚合指定日期：发现当日需刷新的全部用户（事件流 distinct user + 仍有非零快照的 user），
     * 再委托给增量核。手动触发（AdminController）、cron（daily/hourly）、ensureFresh 走这里。
     */
    public int aggregate(LocalDate workDate) {
        // v2.10 起：只聚合 activeTargetTypes 内的 agent 数据。客户端继续采集全部 6 种 agent，
        // 但 monitor_target.enabled=0 的 agent 不参与日聚合 → 下游 daily_summary 自动干净。
        // 切换开关后历史 daily_summary 需要管理员触发"历史重算"才会按新口径回填。
        java.util.Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            log.info("aggregate: date={} no active target types (all disabled), skip", workDate);
            return 0;
        }
        LocalDateTime dayStart = workDate.atStartOfDay();
        LocalDateTime dayEnd = workDate.plusDays(1).atStartOfDay();
        // 拉当日活跃用户：从 ai_session_event 流取 distinct user_code（全 provider 兼容），
        // 且仅统计 ai_session.invalid_reason IS NULL 的有效会话（见 Repository JPQL）。
        // ai_session_message 只在 provider 上报 recentMessages 时才写，event 流是无条件写的，
        // 所以以 event 为活跃判定真相源更准。
        LinkedHashSet<String> userCodes = new LinkedHashSet<>(
                eventRepository.findActiveUsersInWindowAndTargetTypeIn(dayStart, dayEnd, activeTypes));
        // v2.11：并入「该日 daily_summary 仍有非零 AI 快照」的用户——避免收窄口径后无人命中 event、
        // 旧汇总行永远不刷新（典型：窗口内全是 invalid / 禁用 agent 会话，员工数据仍显示虚高协作时长）。
        userCodes.addAll(summaryRepository.findUserCodesWithNonZeroAiStatsOnDate(workDate));
        if (userCodes.isEmpty()) {
            log.info("aggregate: date={} no users to aggregate, skip", workDate);
            return 0;
        }
        return self.aggregate(workDate, userCodes);
    }

    /**
     * 增量核：只重算 {@code onlyUsers} 的当日 daily_summary。ingest 追新直接走这条；
     * 全量版发现用户后也委托到这里。commit 计数按 onlyUsers 过滤，避免全表扫 git_commit。
     */
    @Transactional
    public int aggregate(LocalDate workDate, Set<String> onlyUsers) {
        if (onlyUsers == null || onlyUsers.isEmpty()) {
            return 0;
        }
        java.util.Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        if (activeTypes.isEmpty()) {
            return 0;
        }
        LocalDateTime dayStart = workDate.atStartOfDay();
        LocalDateTime dayEnd = workDate.plusDays(1).atStartOfDay();

        Map<String, Long> commitCountByUser = new HashMap<>();
        for (Object[] row : gitCommitRepository
                .countGroupedByUserCodeInCommitWindowAndUserCodeIn(dayStart, dayEnd, onlyUsers)) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            commitCountByUser.put(row[0].toString(), toLong(row[1]));
        }

        List<DailySummary> rowsToSave = new ArrayList<>(onlyUsers.size());
        int touched = 0;
        for (String userCode : onlyUsers) {
            UserDailyStats stats = computeForUser(userCode, dayStart, dayEnd, activeTypes);
            stats.aiCommitCount = commitCountByUser.getOrDefault(userCode, 0L).intValue();
            rowsToSave.add(buildSummaryRow(userCode, workDate, stats));
            log.debug("aggregate: date={} user={} sessions={} msgs={} tokens={} active_sec={} thinking_sec={} retry={} first_resp_ms={} ai_commits={}",
                    workDate, userCode, stats.sessionCount, stats.aiMessageCount,
                    stats.totalInputTokens + stats.totalOutputTokens,
                    stats.aiActiveSeconds, stats.aiThinkingSeconds,
                    stats.aiRetryCount, stats.aiFirstResponseAvgMs, stats.aiCommitCount);
            touched++;
        }
        if (!rowsToSave.isEmpty()) {
            summaryRepository.saveAll(rowsToSave);
        }
        return touched;
    }

    /**
     * 计算单个用户 + 单日的所有指标。
     *
     * <p>核心数据源：
     * <ul>
     *   <li>当日 message 切片（按 message_time）→ messageCount / firstResponse / retry</li>
     *   <li>当日 event 切片（按 event_time）→ sessionCount / activeSeconds(_union) /
     *       tool_call_count / thinking（v2.6 起切到 event 流）</li>
     *   <li>当日 last_activity 落入的会话集合 → totalInput/Output Tokens / 模型 Top</li>
     * </ul>
     */
    private UserDailyStats computeForUser(String userCode, LocalDateTime dayStart, LocalDateTime dayEnd,
                                          java.util.Collection<String> activeTypes) {
        UserDailyStats st = new UserDailyStats();

        // 1) 当日消息切片（投影列）—— 仅用于 firstResponse / retry / messageCount / 活跃信号
        Map<Long, List<MessageSlice>> bySession = new LinkedHashMap<>();
        int messageCount = 0;
        for (Object[] row : messageRepository.findMessageSlicesByUserAndWindowAndTargetTypeIn(
                userCode, dayStart, dayEnd, activeTypes)) {
            MessageSlice slice = toMessageSlice(row);
            if (slice == null) {
                continue;
            }
            messageCount++;
            bySession.computeIfAbsent(slice.aiSessionId(), k -> new ArrayList<>()).add(slice);
        }
        st.aiMessageCount = messageCount;

        // token 走会话级切片，不走 message 级求和（v2.6）。
        // 原因：cursor / codex / hermes / openharness 的 ai_session_message.input_tokens / output_tokens
        // 在客户端就大面积为 0（cursor 数据源就是 0；其他三个 client 解析时未赋值），
        // 而 ai_session.input_tokens / output_tokens 是 ReporterPipeline 必写字段，覆盖完整。
        // 切片规则：last_activity ∈ [dayStart, dayEnd) 的会话累计 token 全部计入当日；
        // 跨期会话（昨日开始今日收口）历史 token 也合并算入今日，符合"窗口期合计"语义。
        List<Object[]> tokenRows = sessionRepository
                .sumTokensByUserInLastActivityWindowAndTargetTypeIn(userCode, dayStart, dayEnd, activeTypes);
        if (!tokenRows.isEmpty()) {
            Object[] row = tokenRows.get(0);
            st.totalInputTokens = row.length > 0 ? toLong(row[0]) : 0L;
            st.totalOutputTokens = row.length > 1 ? toLong(row[1]) : 0L;
        }

        // 2) firstResponse / retry 在每个 session 的当日子序列上算
        long firstRespSumMs = 0L;
        int firstRespCount = 0;
        for (List<MessageSlice> seg : bySession.values()) {
            long fr = firstResponseMillis(seg);
            if (fr > 0 && fr < FIRST_RESPONSE_OUTLIER_MS) {
                firstRespSumMs += fr;
                firstRespCount++;
            }
            st.aiRetryCount += retryCount(seg);
        }
        st.aiFirstResponseAvgMs = firstRespCount > 0
                ? (int) (firstRespSumMs / firstRespCount)
                : 0;

        // 3) 模型 Top：与 token 总量自洽——按"会话 last_activity 落入当日"的会话级 token 分组求和。
        //    跟 totalInputTokens / totalOutputTokens 是同一份切片，保证 SUM(modelTotals) == totalTokens。
        //    一个 session 一个 model（若某 session 跨日改了 model 取最新值，与展示侧 ai_session 一致）。
        Map<String, Long> tokensByModel = new HashMap<>();
        for (Object[] row : sessionRepository.findModelTokenSlicesByUserAndLastActivityWindowAndTargetTypeIn(
                userCode, dayStart, dayEnd, activeTypes)) {
            if (row == null || row.length < 1 || row[0] == null) {
                continue;
            }
            String model = row[0].toString();
            if (model.isBlank()) {
                continue;
            }
            long tokens = (row.length > 1 ? toLong(row[1]) : 0L) + (row.length > 2 ? toLong(row[2]) : 0L);
            if (tokens <= 0) {
                continue;
            }
            tokensByModel.merge(model, tokens, Long::sum);
        }

        List<Map.Entry<String, Long>> modelRank = new ArrayList<>(tokensByModel.entrySet());
        modelRank.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        if (!modelRank.isEmpty()) {
            st.activeModelTop = modelRank.get(0).getKey();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Math.min(3, modelRank.size()); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escapeJson(modelRank.get(i).getKey())).append('"');
            }
            sb.append(']');
            st.aiModelsTop3 = sb.toString();
        }

        // 4) 当日事件切片（已按 event_time 升序）
        //    v2.6 起 sessionCount / activeSeconds(_union) / toolCallCount / thinking 统一基于
        //    message_time + event_time 合并出来的"活跃信号"流。
        //
        //    背景（必读）：旧 v2.5 用 user→assistant 轮次配对（仅 message_time）算协作时长，
        //    在 client reporter 只上报最后 N 条 message 的场景下严重低估（1h 会话只剩 1min）。
        //    单纯换 event 流又会漏掉 hermes 这种"基本不发 event 但 message 密集"的 provider。
        //    因此 v2.6 把两路时间戳合并成统一的"活跃信号"输入活跃区间算法，覆盖全 provider。
        List<EventSlice> events = new ArrayList<>();
        for (Object[] row : eventRepository.findEventSlicesByUserAndWindowAndTargetTypeIn(
                userCode, dayStart, dayEnd, activeTypes)) {
            EventSlice slice = toEventSlice(row);
            if (slice == null) {
                continue;
            }
            events.add(slice);
        }

        Set<Long> activeSessionIds = new HashSet<>();
        for (EventSlice e : events) {
            if (e.aiSessionId() != null) {
                activeSessionIds.add(e.aiSessionId());
            }
            if (AiSessionEventType.TOOL_CALL.name().equals(e.eventType())) {
                st.toolCallCount++;
            }
        }
        for (Long sid : bySession.keySet()) {
            if (sid != null) {
                activeSessionIds.add(sid);
            }
        }
        st.sessionCount = activeSessionIds.size();

        // 5) 协作秒数 / union 秒数（v2.6）：把 message + event 时间戳合并成活跃信号流，
        //    同 session 内相邻信号间隔 ≤ ACTIVE_GAP_MS 视为持续活跃；
        //    多 session 并行时 union 走 merge-overlapping 去重。
        List<long[]> intervals = new ArrayList<>();
        st.aiActiveSeconds = activeIntervalsFromSignals(bySession, events, dayStart, dayEnd, intervals);
        st.aiActiveSecondsUnion = mergedIntervalSeconds(intervals);

        // 6) thinking：基于 STATUS_CHANGE 事件的 thinking 状态机
        st.aiThinkingSeconds = thinkingSecondsFromEventSlices(events, dayStart, dayEnd);

        return st;
    }

    /**
     * 找子序列里第一条 user 与紧随其后第一条 assistant 之间的 ms 间隔。没有匹配返回 -1。
     */
    private static long firstResponseMillis(List<MessageSlice> msgs) {
        LocalDateTime firstUser = null;
        for (MessageSlice m : msgs) {
            if (firstUser == null && "user".equalsIgnoreCase(m.role())) {
                firstUser = m.messageTime();
                continue;
            }
            if (firstUser != null && "assistant".equalsIgnoreCase(m.role())) {
                if (m.messageTime() != null) {
                    long ms = Duration.between(firstUser, m.messageTime()).toMillis();
                    return ms < 0 ? 0 : ms;
                }
                return -1;
            }
        }
        return -1;
    }

    /**
     * 同 session 内"连续 user 消息中间没出现 assistant"算 1 次。
     * 含义：用户连发问题（卡壳 / 不满意）。
     */
    private static int retryCount(List<MessageSlice> msgs) {
        int retries = 0;
        boolean lastWasUser = false;
        for (MessageSlice m : msgs) {
            String role = m.role();
            if ("user".equalsIgnoreCase(role)) {
                if (lastWasUser) retries++;
                lastWasUser = true;
            } else if ("assistant".equalsIgnoreCase(role)) {
                lastWasUser = false;
            }
        }
        return retries;
    }

    /**
     * 从 STATUS_CHANGE 事件流计算 thinking 状态累计秒数。
     * 算法：扫一遍事件，记录"进入 thinking 的时刻"，下一个 status != thinking 的事件来时收口一段。
     */
    private static long thinkingSecondsFromEventSlices(
            List<EventSlice> events, LocalDateTime dayStart, LocalDateTime dayEnd) {

        long total = 0L;
        Map<Long, LocalDateTime> enterByCession = new HashMap<>();

        events.sort(Comparator.comparing(EventSlice::eventTime,
                Comparator.nullsLast(Comparator.naturalOrder())));

        for (EventSlice e : events) {
            if (!AiSessionEventType.SESSION_OPEN.name().equals(e.eventType())
                    && !AiSessionEventType.STATUS_CHANGE.name().equals(e.eventType())
                    && !AiSessionEventType.SESSION_CLOSE.name().equals(e.eventType())) {
                continue;
            }
            String status = e.status();
            LocalDateTime t = e.eventTime();
            if (t == null) continue;
            Long sid = e.aiSessionId();
            LocalDateTime enter = enterByCession.get(sid);
            if (status != null && AiSessionStatus.THINKING.code().equalsIgnoreCase(status)) {
                if (enter == null) enterByCession.put(sid, t);
            } else if (enter != null) {
                LocalDateTime startClip = max(enter, dayStart);
                LocalDateTime endClip = min(t, dayEnd);
                if (startClip != null && endClip != null && endClip.isAfter(startClip)) {
                    total += Duration.between(startClip, endClip).toSeconds();
                }
                enterByCession.remove(sid);
            }
        }
        // 仍在 thinking 的尾段：clip 到 dayEnd（避免会话仍在进行时丢失今日已有的 thinking 时间）
        for (LocalDateTime enter : enterByCession.values()) {
            LocalDateTime startClip = max(enter, dayStart);
            if (startClip != null && dayEnd.isAfter(startClip)) {
                total += Duration.between(startClip, dayEnd).toSeconds();
            }
        }
        return total;
    }

    /** upsert by (user_code, work_date) — 构建行，由 aggregate 批量 saveAll。 */
    private DailySummary buildSummaryRow(String userCode, LocalDate workDate, UserDailyStats st) {
        DailySummary row = summaryRepository
                .findByUserCodeAndWorkDate(userCode, workDate)
                .orElseGet(() -> {
                    DailySummary fresh = new DailySummary();
                    fresh.setUserCode(userCode);
                    fresh.setWorkDate(workDate);
                    return fresh;
                });

        // 仅覆盖 v1.3 / v2.0 AI 字段；v1.2 工时字段（onlineSeconds / activeSeconds / projectCount 等）
        // 由独立的 work_session 聚合任务负责，本聚合不动它们。
        row.setAiSessionCount(st.sessionCount);
        row.setAiMessageCount(st.aiMessageCount);
        row.setTotalInputTokens(st.totalInputTokens);
        row.setTotalOutputTokens(st.totalOutputTokens);
        row.setToolCallCount(st.toolCallCount);
        row.setActiveModelTop(st.activeModelTop);

        row.setAiActiveSeconds(st.aiActiveSeconds);
        row.setAiActiveSecondsUnion(st.aiActiveSecondsUnion);
        row.setAiFirstResponseAvgMs(st.aiFirstResponseAvgMs);
        row.setAiThinkingSeconds(st.aiThinkingSeconds);
        row.setAiRetryCount(st.aiRetryCount);
        row.setAiCommitCount(st.aiCommitCount);
        row.setAiModelsTop3(st.aiModelsTop3);

        // v2.9：强制把 updated_time 设到现在——即使本轮 stats 与上一轮完全一致，
        // hibernate 也会因这条字段 dirty 而发 UPDATE。
        // 背景：updated_time 走 @LastModifiedDate auditing，受 dirty check 限制；
        // 当某天 stats 已稳定（无新 event/message 进来），UPDATE 会被跳过 → updated_time 卡死。
        // PeopleController.ensureWindowFreshIfStale 用 updated_time 判定过期，
        // 卡死会导致每次访问都误判为"过期"反复 ensureFresh。手动 setUpdatedTime 让此字段
        // 永远反映 aggregate 真实执行时刻，过期判定才准确。
        // @LastModifiedDate 仍然生效——它会在 flush 时覆盖此处手动值，但这次 dirty 标记已被打上。
        row.setUpdatedTime(LocalDateTime.now());

        return row;
    }

    private record MessageSlice(Long aiSessionId, String role, LocalDateTime messageTime) {}

    private record EventSlice(Long aiSessionId, LocalDateTime eventTime, String eventType, String status) {}

    private static MessageSlice toMessageSlice(Object[] row) {
        if (row == null || row.length < 3 || row[0] == null) {
            return null;
        }
        Long sid = row[0] instanceof Number n ? n.longValue() : null;
        String role = row[1] == null ? null : row[1].toString();
        LocalDateTime t = row[2] instanceof LocalDateTime ldt ? ldt
                : row[2] instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null;
        return new MessageSlice(sid, role, t);
    }

    private static EventSlice toEventSlice(Object[] row) {
        if (row == null || row.length < 4 || row[0] == null) {
            return null;
        }
        Long sid = row[0] instanceof Number n ? n.longValue() : null;
        LocalDateTime t = row[1] instanceof LocalDateTime ldt ? ldt
                : row[1] instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null;
        String type = row[2] == null ? null : row[2].toString();
        String status = row[3] == null ? null : row[3].toString();
        return new EventSlice(sid, t, type, status);
    }

    // ---------- helpers ----------

    private static class UserDailyStats {
        int sessionCount;
        int aiMessageCount;
        long totalInputTokens;
        long totalOutputTokens;
        int toolCallCount;
        String activeModelTop;
        long aiActiveSeconds;
        long aiActiveSecondsUnion;
        int aiFirstResponseAvgMs;
        long aiThinkingSeconds;
        int aiRetryCount;
        int aiCommitCount;
        String aiModelsTop3;
    }

    /**
     * v2.6 协作时长真相源：把 message_time（来自 ai_session_message）+ event_time（来自
     * ai_session_event）合并成统一的"活跃信号"流，按 (sessionId, time) 排序后扫一遍。
     *
     * <p>双阈值算法（v2.6.1 加段内 cap）：
     * <ul>
     *   <li>间隔 ≤ {@link #ACTIVE_FILL_CAP_MS}（5 min）：原样计入，每个信号代表用户当下在协作</li>
     *   <li>{@code ACTIVE_FILL_CAP_MS < 间隔 ≤ ACTIVE_GAP_MS}（5~30 min）：截断到 5 min 计入——
     *       用户可能短暂思考 / 切窗口 / 看文档，保守按 5 min 估</li>
     *   <li>间隔 > {@link #ACTIVE_GAP_MS}（30 min）：完全剔除，视为离开（吃饭 / 开会 / 切走）</li>
     * </ul>
     *
     * <p>双阈值的设计意图：单 gap 阈值（30 min 通通算活跃）会把"边界附近的真实离开"也填进去
     * （实测 SXF2939 5/8 前 5 大间隔都卡在 25~30 min，明显是开短会，但全被算成持续活跃，
     * 跨度 14.4 h 被算成 13.4 h 协作）；段内 cap 让每个信号"自然衰减"——一次活跃延续最多 5 min，
     * 之后必须有新信号续活才继续算。这跟"键盘前协作"语义最贴。
     *
     * <p>为什么合并 message + event 两路：
     * <ul>
     *   <li>cursor / codex / claude：reporter 只上报每会话最后 N 条 message，message_time 流稀疏；
     *       但 STATUS_CHANGE / MESSAGE_DELTA / TOKEN_DELTA event 全程写——event 流主导</li>
     *   <li>hermes：基本只在 SESSION_OPEN 写 1 个 event 后不再写；但 message 密集——message 流主导</li>
     *   <li>openclaw / openharness：两路皆有</li>
     * </ul>
     * 合并后任一信号流稠密都能贡献活跃判定，对所有 provider 鲁棒。
     *
     * @param bySession 当日已按 (sessionId, sequenceNo) 升序的 message 子序列
     * @param events    当日已按 event_time 升序的全部事件
     * @param dayStart  当日 0:00（含）
     * @param dayEnd    次日 0:00（不含）
     * @param intervals 区间收集器（[startEpochSec, endEpochSec]），副作用写入
     * @return 当日按"逐 session 累加"的活跃秒数（多 session 并行会重复累加，可能 > 86400）
     */
    private static long activeIntervalsFromSignals(
            Map<Long, List<MessageSlice>> bySession,
            List<EventSlice> events,
            LocalDateTime dayStart, LocalDateTime dayEnd,
            List<long[]> intervals) {

        Map<Long, List<LocalDateTime>> signalsBySession = new HashMap<>();
        for (Map.Entry<Long, List<MessageSlice>> e : bySession.entrySet()) {
            Long sid = e.getKey();
            if (sid == null) continue;
            List<LocalDateTime> bucket = signalsBySession
                    .computeIfAbsent(sid, k -> new ArrayList<>());
            for (MessageSlice m : e.getValue()) {
                if (m.messageTime() != null) bucket.add(m.messageTime());
            }
        }
        for (EventSlice ev : events) {
            Long sid = ev.aiSessionId();
            LocalDateTime t = ev.eventTime();
            if (sid == null || t == null) continue;
            signalsBySession.computeIfAbsent(sid, k -> new ArrayList<>()).add(t);
        }

        long total = 0L;
        for (List<LocalDateTime> ts : signalsBySession.values()) {
            if (ts.size() < 2) continue;
            ts.sort(Comparator.naturalOrder());
            for (int i = 1; i < ts.size(); i++) {
                LocalDateTime prev = ts.get(i - 1);
                LocalDateTime cur = ts.get(i);
                long ms = Duration.between(prev, cur).toMillis();
                if (ms <= 0 || ms > ACTIVE_GAP_MS) continue;
                long fillMs = Math.min(ms, ACTIVE_FILL_CAP_MS);
                LocalDateTime fillEnd = prev.plusNanos(fillMs * 1_000_000L);
                LocalDateTime startClip = max(prev, dayStart);
                LocalDateTime endClip = min(fillEnd, dayEnd);
                if (startClip == null || endClip == null || !endClip.isAfter(startClip)) continue;
                total += Duration.between(startClip, endClip).toSeconds();
                intervals.add(new long[]{
                        startClip.toEpochSecond(ZoneOffset.UTC),
                        endClip.toEpochSecond(ZoneOffset.UTC)});
            }
        }
        return total;
    }

    /**
     * 给定一组 [startEpochSec, endEpochSec] 区间，按 start 排序后做 merge-overlapping，
     * 返回合并后总秒数。算法 O(n log n) 排序 + O(n) 一遍扫描。
     * 用于 ai_active_seconds_union：同一用户多 session 重叠时只算一次。
     */
    private static long mergedIntervalSeconds(List<long[]> intervals) {
        if (intervals.isEmpty()) return 0L;
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        long total = 0L;
        long curStart = intervals.get(0)[0];
        long curEnd = intervals.get(0)[1];
        for (int i = 1; i < intervals.size(); i++) {
            long s = intervals.get(i)[0];
            long e = intervals.get(i)[1];
            if (s <= curEnd) {
                if (e > curEnd) curEnd = e;
            } else {
                total += curEnd - curStart;
                curStart = s;
                curEnd = e;
            }
        }
        total += curEnd - curStart;
        return total;
    }

    private static long nz(Long v) { return v == null ? 0 : v; }
    private static int nz(Integer v) { return v == null ? 0 : v; }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
