package com.am.server.system.scheduling;

import com.am.server.system.SystemConfigChangedEvent;
import com.am.server.system.SystemConfigKeys;
import com.am.server.system.SystemConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 动态调度管理器 —— 接管所有"业务可见 @Scheduled 任务"的注册、触发、状态收集。
 *
 * <p>用法：业务组件在 {@code @PostConstruct} 里调 {@link #register} 把自身的 Runnable
 * 注册进来，连带一份 {@link ScheduledTaskDefinition}。Manager 会：
 * <ol>
 *   <li>把 default cron / default enabled 通过 {@link SystemConfigService#seedIfAbsent} 落库（首次启动时）</li>
 *   <li>读 sys_config 当前值，按 {@link CronTrigger} 调度 Runnable</li>
 *   <li>监听 {@link SystemConfigChangedEvent}，对应 key 变更时重新调度</li>
 *   <li>每次执行记录耗时 / 成败 / 错误信息 / 累计计数，供 UI 拉</li>
 * </ol>
 *
 * <p>线程池：内部维护一个独立 {@link ThreadPoolTaskScheduler}（4 线程），与
 * Spring 默认 @Scheduled 池隔离，避免基础设施任务把业务调度池挤住。
 *
 * <p>容错：注册阶段一旦失败（cron 非法等），任务不会被调度，但 manager 不会启动失败；
 * 错误信息记在 {@link ManagedTask#lastErrorText} 里供 UI 展示，管理员改 cron 后会重试调度。
 *
 * gz
 */
@Component
@RequiredArgsConstructor
public class DynamicScheduledTaskManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicScheduledTaskManager.class);

    private final SystemConfigService configService;

    /** taskCode -> 调度运行时状态 */
    private final ConcurrentMap<String, ManagedTask> tasks = new ConcurrentHashMap<>();

    private ThreadPoolTaskScheduler scheduler;

    @PostConstruct
    public void init() {
        this.scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("dyn-sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        log.info("DynamicScheduledTaskManager initialized: poolSize={}", 4);
    }

    @PreDestroy
    public void destroy() {
        for (ManagedTask t : tasks.values()) {
            t.cancelFuture();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * 注册一个可管理任务。幂等：同名 taskCode 重复注册时打 warn 并直接覆盖（dev 热重载场景）。
     *
     * <p>注册顺序：
     * <ol>
     *   <li>seed default cron / enabled 到 sys_config（仅当 key 未存在）</li>
     *   <li>读 sys_config 当前值（cron + enabled）</li>
     *   <li>根据 enabled 决定立即调度还是只挂着</li>
     * </ol>
     */
    public synchronized void register(ScheduledTaskDefinition def, Runnable runnable) {
        if (def == null || def.taskCode() == null || runnable == null) {
            throw new IllegalArgumentException("invalid task registration");
        }
        if (tasks.containsKey(def.taskCode())) {
            log.warn("task {} already registered, overriding", def.taskCode());
            tasks.get(def.taskCode()).cancelFuture();
        }

        seedConfigIfAbsent(def);

        ManagedTask mt = new ManagedTask(def, runnable);
        tasks.put(def.taskCode(), mt);

        applyConfig(mt);
        log.info("dynamic task registered: code={} category={} cron={} enabled={}",
                def.taskCode(), def.category(), mt.currentCron, mt.currentEnabled);
    }

    private void seedConfigIfAbsent(ScheduledTaskDefinition def) {
        String cronKey = SystemConfigKeys.CAT_SCHEDULING + "." + def.taskCode() + SystemConfigKeys.SUFFIX_CRON;
        String enabledKey = SystemConfigKeys.CAT_SCHEDULING + "." + def.taskCode() + SystemConfigKeys.SUFFIX_ENABLED;
        configService.seedIfAbsent(cronKey, def.defaultCron(), "string",
                SystemConfigKeys.CAT_SCHEDULING, false,
                def.displayName() + " - cron 表达式");
        configService.seedIfAbsent(enabledKey, Boolean.toString(def.defaultEnabled()), "boolean",
                SystemConfigKeys.CAT_SCHEDULING, false,
                def.displayName() + " - 是否启用");
    }

    private void applyConfig(ManagedTask mt) {
        String cronKey = SystemConfigKeys.CAT_SCHEDULING + "." + mt.definition.taskCode() + SystemConfigKeys.SUFFIX_CRON;
        String enabledKey = SystemConfigKeys.CAT_SCHEDULING + "." + mt.definition.taskCode() + SystemConfigKeys.SUFFIX_ENABLED;

        String newCron = configService.getString(cronKey, mt.definition.defaultCron());
        boolean newEnabled = configService.getBool(enabledKey, mt.definition.defaultEnabled());

        boolean cronChanged = !newCron.equals(mt.currentCron);
        boolean enabledChanged = newEnabled != mt.currentEnabled;
        if (mt.future != null && !cronChanged && !enabledChanged) {
            return;
        }

        mt.cancelFuture();
        mt.currentCron = newCron;
        mt.currentEnabled = newEnabled;
        mt.lastErrorText = null;

        if (!newEnabled) {
            log.info("task {} disabled by config, not scheduled", mt.definition.taskCode());
            return;
        }

        try {
            TimeZone tz = mt.definition.zoneId() != null
                    ? TimeZone.getTimeZone(mt.definition.zoneId())
                    : TimeZone.getDefault();
            CronTrigger trigger = new CronTrigger(newCron, tz);
            mt.future = scheduler.schedule(() -> safeRun(mt), trigger);
            log.info("task {} scheduled: cron={} zone={}", mt.definition.taskCode(), newCron, tz.getID());
        } catch (Exception ex) {
            mt.lastErrorText = "invalid cron: " + ex.getMessage();
            log.error("failed to schedule task {} cron={}: {}",
                    mt.definition.taskCode(), newCron, ex.toString());
        }
    }

    private void safeRun(ManagedTask mt) {
        if (!mt.running.compareAndSet(false, true)) {
            log.warn("task {} previous run still in progress, skip this tick", mt.definition.taskCode());
            return;
        }
        long t0 = System.currentTimeMillis();
        mt.lastStartTime.set(LocalDateTime.now());
        try {
            mt.runnable.run();
            mt.lastDurationMs.set(System.currentTimeMillis() - t0);
            mt.lastEndTime.set(LocalDateTime.now());
            mt.lastStatus.set("SUCCESS");
            mt.lastErrorText = null;
            mt.successCount.incrementAndGet();
        } catch (Throwable ex) {
            mt.lastDurationMs.set(System.currentTimeMillis() - t0);
            mt.lastEndTime.set(LocalDateTime.now());
            mt.lastStatus.set("FAILED");
            mt.lastErrorText = truncate(ex.toString(), 2000);
            mt.failureCount.incrementAndGet();
            log.error("dynamic task {} failed", mt.definition.taskCode(), ex);
        } finally {
            mt.running.set(false);
        }
    }

    /** sys_config 变更事件：对 scheduling.* 的 key 触发对应任务的 reapply。 */
    @EventListener
    public void onConfigChanged(SystemConfigChangedEvent ev) {
        Set<String> keys = ev.getChangedKeys();
        if (keys == null || keys.isEmpty()) return;
        for (ManagedTask mt : tasks.values()) {
            String cronKey = SystemConfigKeys.CAT_SCHEDULING + "." + mt.definition.taskCode() + SystemConfigKeys.SUFFIX_CRON;
            String enabledKey = SystemConfigKeys.CAT_SCHEDULING + "." + mt.definition.taskCode() + SystemConfigKeys.SUFFIX_ENABLED;
            if (keys.contains(cronKey) || keys.contains(enabledKey)) {
                applyConfig(mt);
            }
        }
    }

    /** 立即触发某任务（异步，不阻塞调用方）。任务不存在或定义禁用手动触发时抛异常。 */
    public void triggerNow(String taskCode) {
        ManagedTask mt = tasks.get(taskCode);
        if (mt == null) {
            throw new IllegalArgumentException("unknown task: " + taskCode);
        }
        if (!mt.definition.manualTriggerable()) {
            throw new IllegalStateException("task " + taskCode + " is not manually triggerable");
        }
        scheduler.execute(() -> safeRun(mt));
        log.info("task {} manually triggered", taskCode);
    }

    /** 列出全部注册任务的状态快照，按 category（business 优先）+ taskCode 排序。 */
    public List<ScheduledTaskStatus> listAll() {
        List<ScheduledTaskStatus> out = new ArrayList<>(tasks.size());
        for (ManagedTask mt : tasks.values()) {
            out.add(toStatus(mt));
        }
        out.sort((a, b) -> {
            int cat = categoryRank(a.category()) - categoryRank(b.category());
            if (cat != 0) return cat;
            return a.taskCode().compareTo(b.taskCode());
        });
        return out;
    }

    private static int categoryRank(String category) {
        return ScheduledTaskDefinition.CATEGORY_BUSINESS.equals(category) ? 0 : 1;
    }

    private ScheduledTaskStatus toStatus(ManagedTask mt) {
        LocalDateTime nextRun = null;
        if (mt.currentEnabled && mt.lastErrorText == null) {
            try {
                TimeZone tz = mt.definition.zoneId() != null
                        ? TimeZone.getTimeZone(mt.definition.zoneId())
                        : TimeZone.getDefault();
                CronTrigger ct = new CronTrigger(mt.currentCron, tz);
                ZonedDateTime base = mt.lastEndTime.get() != null
                        ? mt.lastEndTime.get().atZone(ZoneId.systemDefault())
                        : ZonedDateTime.now();
                java.time.Instant nextInstant = ct.nextExecution(
                        new SimpleTriggerContext(base.toInstant(), base.toInstant(), base.toInstant()));
                if (nextInstant != null) {
                    nextRun = LocalDateTime.ofInstant(nextInstant, ZoneId.systemDefault());
                }
            } catch (Exception ignore) {
                // 估算失败不阻断列表展示
            }
        }
        return new ScheduledTaskStatus(
                mt.definition.taskCode(),
                mt.definition.displayName(),
                mt.definition.category(),
                mt.definition.description(),
                mt.currentCron,
                mt.definition.defaultCron(),
                mt.currentEnabled,
                mt.definition.cronEditable(),
                mt.definition.manualTriggerable(),
                mt.definition.zoneId(),
                mt.running.get(),
                mt.lastStartTime.get(),
                mt.lastEndTime.get(),
                mt.lastDurationMs.get(),
                mt.lastStatus.get(),
                mt.lastErrorText,
                mt.successCount.get(),
                mt.failureCount.get(),
                nextRun);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ============================================================
    // 内部数据
    // ============================================================

    /** 单个任务的运行时状态（注意：这是可变对象，仅 manager 自身串行修改）。 */
    private static final class ManagedTask {
        final ScheduledTaskDefinition definition;
        final Runnable runnable;

        String currentCron;
        boolean currentEnabled;
        volatile String lastErrorText;

        ScheduledFuture<?> future;

        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicReference<LocalDateTime> lastStartTime = new AtomicReference<>();
        final AtomicReference<LocalDateTime> lastEndTime = new AtomicReference<>();
        final AtomicLong lastDurationMs = new AtomicLong(-1);
        final AtomicReference<String> lastStatus = new AtomicReference<>();
        final AtomicLong successCount = new AtomicLong(0);
        final AtomicLong failureCount = new AtomicLong(0);

        ManagedTask(ScheduledTaskDefinition definition, Runnable runnable) {
            this.definition = definition;
            this.runnable = runnable;
        }

        void cancelFuture() {
            if (future != null) {
                future.cancel(false);
                future = null;
            }
        }
    }

    /**
     * Spring TaskScheduler 的 TriggerContext 内部实现拷贝（spring 8.x 仍提供该接口，
     * 但 SimpleTriggerContext 在某些版本签名变化，这里写一个轻量版本避免依赖差异）。
     */
    private record SimpleTriggerContext(
            java.time.Instant lastScheduledExecution,
            java.time.Instant lastActualExecution,
            java.time.Instant lastCompletion) implements org.springframework.scheduling.TriggerContext {
        @Override public java.time.Instant lastScheduledExecution() { return lastScheduledExecution; }
        @Override public java.time.Instant lastActualExecution() { return lastActualExecution; }
        @Override public java.time.Instant lastCompletion() { return lastCompletion; }
    }

    /** 给 UI 拿的状态 DTO。 */
    public record ScheduledTaskStatus(
            String taskCode,
            String displayName,
            String category,
            String description,
            String cron,
            String defaultCron,
            boolean enabled,
            boolean cronEditable,
            boolean manualTriggerable,
            String zoneId,
            boolean running,
            LocalDateTime lastStartTime,
            LocalDateTime lastEndTime,
            long lastDurationMs,
            String lastStatus,
            String lastErrorText,
            long successCount,
            long failureCount,
            LocalDateTime nextRunTime) {}

    // 让其他包能拿到 TaskScheduler（如 SystemConfigService 想做更复杂的延后任务）
    public TaskScheduler getScheduler() {
        return scheduler;
    }
}
