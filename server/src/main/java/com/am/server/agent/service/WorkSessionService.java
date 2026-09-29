package com.am.server.agent.service;

import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.session.WorkSession;
import com.am.server.domain.session.WorkSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工作会话管理服务（v1.3 语义）
 *
 * - online_seconds：每次收到上报，按 (capturedAt - prevSeen) 累加，单次封顶 perReportCapSeconds
 * - active_seconds：当本次上报存在非 idle 且 last_activity 在 activityWindow 内的 ai_session 时同样累加
 * - 跨 180s 间隔 = 新会话；旧会话关闭
 *
 * <p><b>并发</b>：同一 agent 的设备心跳（≤2KB，绕过 ingest 舱壁）与数据上报可以同时到达。
 * 调用方在各自请求开头各读一次 {@code device.last_seen} 当 {@code prevSeen}，于是两个并发请求拿到
 * <b>同一个</b> prevSeen，各自从它算到自己的 capturedAt——同一段时间被记两遍；同时「读当前 OPEN 会话 → 累加 → save」
 * 是无锁的读改写（丢更新），两个请求都看不到 OPEN 会话时还会各开一条（重复 OPEN）。修法两件事：
 * <ol>
 *   <li><b>同一 agent 的 advance 串行化</b>：按 agentId 分段的 striped lock，包住整个事务。锁在开事务<b>之前</b>取
 *       （{@link TransactionOperations} 在锁内才开），等锁的请求线程不占 Hikari 连接——2026-09 事故的教训是
 *       连接池比线程少，任何“持连接等别人”的写法都会放大成全员超时；</li>
 *   <li><b>单调时间基准</b>：锁内记住该 agent 已计到的最新 capturedAt（{@code advancedUpTo}），
 *       实际 prevSeen = max(调用方传入的 prevSeen, advancedUpTo)。后到的请求只记增量（乱序的旧 capturedAt 记 0），
 *       总记时长因此 ≤ 墙钟跨度。仅在事务<b>提交后</b>前移，回滚不丢秒。</li>
 * </ol>
 * 单实例部署下足够（与 DailySummaryAggregator 的 per-date 锁同一假设）；多实例需要把 advancedUpTo 落到
 * work_session 列上做条件 UPDATE。
 *
 * gz
 */
@Service
public class WorkSessionService {

    private static final Logger log = LoggerFactory.getLogger(WorkSessionService.class);

    private static final long GAP_SECONDS = 180;

    /** striped lock 段数：同段 agent 互相串行（一次 advance 只是几条走索引的语句），段数只影响碰撞概率。 */
    private static final int LOCK_STRIPES = 64;

    /** {@link #advancedUpTo} 超过这个条数时顺手清掉 1 小时前的项（停用 / 离职设备的残留），防止只增不减。 */
    private static final int ADVANCED_UP_TO_SOFT_LIMIT = 4096;

    private final WorkSessionRepository repository;
    private final AiSessionRepository aiSessionRepository;
    private final AgentProperties agentProperties;
    private final TransactionOperations tx;

    private final ReentrantLock[] stripes = newStripes();
    /** agentId → 已计入 work_session 的最新 capturedAt（agent 时钟）。只在对应 stripe 锁内读写。 */
    private final ConcurrentHashMap<String, LocalDateTime> advancedUpTo = new ConcurrentHashMap<>();

    @Autowired
    public WorkSessionService(WorkSessionRepository repository,
                              AiSessionRepository aiSessionRepository,
                              AgentProperties agentProperties,
                              PlatformTransactionManager transactionManager) {
        this(repository, aiSessionRepository, agentProperties, new TransactionTemplate(transactionManager));
    }

    /** 测试用：注入事务操作（如 {@link TransactionOperations#withoutTransaction()}）。 */
    WorkSessionService(WorkSessionRepository repository,
                       AiSessionRepository aiSessionRepository,
                       AgentProperties agentProperties,
                       TransactionOperations tx) {
        this.repository = repository;
        this.aiSessionRepository = aiSessionRepository;
        this.agentProperties = agentProperties;
        this.tx = tx;
    }

    private static ReentrantLock[] newStripes() {
        ReentrantLock[] out = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < out.length; i++) {
            out[i] = new ReentrantLock();
        }
        return out;
    }

    /**
     * 推进一次。<b>不加 {@code @Transactional}</b>：事务由本方法在 agent 锁内自己开，见类注释。
     *
     * @param prevSeen 调用方在本次请求开头读到的 device.last_seen（并发时可能已过期，由 {@link #advancedUpTo} 兜住）
     */
    public void advance(AgentDevice device,
                        LocalDateTime prevSeen,
                        LocalDateTime capturedAt,
                        boolean hasActiveSession) {
        if (capturedAt == null) {
            capturedAt = LocalDateTime.now();
        }
        final LocalDateTime captured = capturedAt;
        String agentId = device.getAgentId();
        ReentrantLock lock = stripes[stripeIndex(agentId)];
        lock.lock();
        try {
            LocalDateTime effectivePrev = laterOf(prevSeen, advancedUpTo.get(agentId));
            tx.executeWithoutResult(status -> doAdvance(device, effectivePrev, captured, hasActiveSession));
            advancedUpTo.merge(agentId, captured, WorkSessionService::laterOf);
            pruneAdvancedUpToIfLarge();
        } finally {
            lock.unlock();
        }
    }

    static int stripeIndex(String agentId) {
        return (agentId == null ? 0 : agentId.hashCode() & 0x7fffffff) % LOCK_STRIPES;
    }

    private static LocalDateTime laterOf(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private void pruneAdvancedUpToIfLarge() {
        if (advancedUpTo.size() <= ADVANCED_UP_TO_SOFT_LIMIT) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        advancedUpTo.values().removeIf(t -> t.isBefore(cutoff));
    }

    /** 事务内：与原实现逻辑一致，只是 prevSeen 已换成单调的 effectivePrev。 */
    private void doAdvance(AgentDevice device,
                           LocalDateTime prevSeen,
                           LocalDateTime capturedAt,
                           boolean hasActiveSession) {
        WorkSession current = repository
                .findFirstByAgentIdAndStatusOrderByStartTimeDesc(
                        device.getAgentId(), WorkSession.STATUS_OPEN)
                .orElse(null);

        long deltaSeconds = prevSeen == null ? 0L
                : Math.max(0L, Duration.between(prevSeen, capturedAt).getSeconds());

        if (deltaSeconds > GAP_SECONDS) {
            if (current != null) {
                current.setStatus(WorkSession.STATUS_CLOSED);
                current.setEndTime(prevSeen);
                repository.save(current);
                log.debug("work session closed by gap: agentId={} duration={}s active={}s",
                        device.getAgentId(), current.getDurationSeconds(), current.getActiveSeconds());
            }
            WorkSession ws = openNew(device, capturedAt);
            attachActiveProject(ws, device.getAgentId());
            repository.save(ws);
            return;
        }

        if (current == null) {
            WorkSession ws = openNew(device, capturedAt);
            attachActiveProject(ws, device.getAgentId());
            repository.save(ws);
            return;
        }

        long credit = Math.min(deltaSeconds, perReportCapSeconds());
        current.setDurationSeconds(nz(current.getDurationSeconds()) + credit);
        if (hasActiveSession) {
            current.setActiveSeconds(nz(current.getActiveSeconds()) + credit);
        }
        attachActiveProject(current, device.getAgentId());
        repository.save(current);
    }

    /** 把该 agent 最近一条活跃 ai_session 的 project / repo / branch 写到 work_session（仅更新非空值）。 */
    private void attachActiveProject(WorkSession ws, String agentId) {
        Optional<AiSession> latest = aiSessionRepository.findFirstByAgentIdOrderByLastActivityDesc(agentId);
        if (latest.isEmpty()) {
            return;
        }
        AiSession s = latest.get();
        if (s.getProjectName() != null) {
            ws.setProjectName(s.getProjectName());
        }
        if (s.getRepoUrl() != null) {
            ws.setRepoUrl(s.getRepoUrl());
        }
        if (s.getGitBranch() != null) {
            ws.setBranchName(s.getGitBranch());
        }
    }

    private WorkSession openNew(AgentDevice device, LocalDateTime capturedAt) {
        WorkSession s = new WorkSession();
        s.setAgentId(device.getAgentId());
        s.setUserCode(device.getUserCode());
        s.setHostHash(device.getHostHash());
        s.setStartTime(capturedAt);
        s.setStatus(WorkSession.STATUS_OPEN);
        s.setDurationSeconds(0L);
        s.setActiveSeconds(0L);
        return s;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private long perReportCapSeconds() {
        return agentProperties != null ? agentProperties.getPerReportCapSeconds() : 120L;
    }
}
