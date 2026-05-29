package com.am.server.system;

import com.am.server.agent.api.dto.MonitorAgentPolicyDto;
import com.am.server.domain.monitor.MonitorTarget;
import com.am.server.domain.monitor.MonitorTargetRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * "激活" 的 target_type 白名单单点服务。
 *
 * <p>所有需要按 target_type 过滤的查询 / 聚合任务都通过本服务拿白名单：
 * <pre>
 *   Collection<String> active = activeTargetTypesProvider.getActiveTypes();
 *   if (!active.isEmpty()) { ... WHERE s.targetType IN :active ... }
 * </pre>
 *
 * <p>白名单数据源 = {@code monitor_target.enabled=1}。客户端 ingest 路径**不**检查 enabled，
 * enabled=0 的 agent 数据仍正常入库，只是不进入聚合 / 展示。这样未来想"复活"某 agent
 * 历史数据不会断档。
 *
 * <p>缓存策略：
 * <ul>
 *   <li>启动加载 + 5 分钟兜底刷新</li>
 *   <li>{@link com.am.server.web.MonitorTargetController} 改完调 {@link #invalidate}</li>
 *   <li>{@link CopyOnWriteArraySet} 保证无锁读 + 写时复制</li>
 * </ul>
 * gz
 */
@Service
@RequiredArgsConstructor
public class ActiveTargetTypesProvider {

    /** 下发给 Agent 的策略 TTL：5min；供客户端观测，未来可作轮询兜底。 */
    public static final long MONITOR_AGENT_POLICY_TTL_MS = 300_000L;

    private static final Logger log = LoggerFactory.getLogger(ActiveTargetTypesProvider.class);

    private final MonitorTargetRepository monitorTargetRepository;

    /** 当前活跃的 target_type 集合（小写）。 */
    private final Set<String> activeTypes = new CopyOnWriteArraySet<>();

    /** Agent 采集策略代数：enabled 集合变化时递增（启动首次加载不改变初始值）。 */
    private final AtomicLong monitorAgentPolicyVersion = new AtomicLong(1);

    private volatile boolean monitorPolicyHydrated;

    private volatile String lastMonitorPolicyFingerprint = "";

    @PostConstruct
    public void init() {
        reload();
    }

    /** 失效缓存并立刻重载（PATCH /monitor-targets 后调）。 */
    public void invalidate() {
        reload();
    }

    /** 每 5 分钟兜底刷新，防止漏 invalidate。 */
    @Scheduled(fixedRate = 5 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void reload() {
        try {
            List<MonitorTarget> enabled = monitorTargetRepository.findAllByEnabled(1);
            Set<String> next = new LinkedHashSet<>();
            for (MonitorTarget t : enabled) {
                if (t.getTypeCode() != null) {
                    next.add(t.getTypeCode().toLowerCase(Locale.ROOT));
                }
            }

            String fingerprint = next.stream()
                    .sorted()
                    .collect(Collectors.joining(","));

            activeTypes.clear();
            activeTypes.addAll(next);
            bumpMonitorAgentVersionIfFingerprintChanged(fingerprint);
            log.info("active target types reloaded: {}", next);
        } catch (Exception ex) {
            log.warn("active target types reload failed (keeping previous snapshot)", ex);
        }
    }

    private void bumpMonitorAgentVersionIfFingerprintChanged(String fingerprint) {
        synchronized (monitorAgentPolicyVersion) {
            if (!monitorPolicyHydrated) {
                monitorPolicyHydrated = true;
                lastMonitorPolicyFingerprint = fingerprint;
                return;
            }
            if (!fingerprint.equals(lastMonitorPolicyFingerprint)) {
                lastMonitorPolicyFingerprint = fingerprint;
                long v = monitorAgentPolicyVersion.incrementAndGet();
                log.info("monitor agent policy version bumped to {} (types fingerprint changed)", v);
            }
        }
    }

    /**
     * 当前发往 Agent（注册 / report 响应附带）的快照策略。
     */
    public MonitorAgentPolicyDto snapshotAgentPolicy() {
        List<String> types = activeTypes.stream()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toCollection(ArrayList::new));
        return MonitorAgentPolicyDto.builder()
                .enabledMonitorTypes(types)
                .version(monitorAgentPolicyVersion.get())
                .ttlMs(MONITOR_AGENT_POLICY_TTL_MS)
                .build();
    }

    /** 主要用于测试断言或运维观测。 */
    public long monitorAgentPolicyVersion() {
        return monitorAgentPolicyVersion.get();
    }

    /**
     * 当前激活的 target_type 列表（小写）。永不返回 null。
     * 如果返回空集合，说明所有 agent 都被禁用了——调用方应该跳过聚合或返回空结果。
     */
    public Collection<String> getActiveTypes() {
        return List.copyOf(activeTypes);
    }

    /** 全部 6 种 agent 都激活时返回 true，调用方可以省去 IN 过滤。 */
    public boolean isAllActive() {
        return activeTypes.size() == 6;
    }

    /** 单值判定。 */
    public boolean shouldInclude(String targetType) {
        return targetType != null && activeTypes.contains(targetType.toLowerCase());
    }
}
