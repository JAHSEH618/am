package com.am.server.agent.security;

import com.am.server.common.LogThrottle;
import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentAlert;
import com.am.server.domain.agent.AgentAlertRepository;
import com.am.server.domain.agent.AlertType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警服务，提供基础写入入口供过滤器与业务层调用。
 *
 * <p><b>写入放大问题</b>：{@link AgentSignatureFilter} 在验签<b>之前</b>就会写告警（缺签名头、时钟漂移的 agent
 * 每个 tick 一行），而 {@code agent_alert} 此前既不限速也没有保留期——一台时钟偏了的机器一天就是上千行，
 * 伪造 agent id 的未认证请求更可以无限刷。所以这里做三层保护，且<b>告警写入永远不能让请求失败或变慢</b>：
 * <ol>
 *   <li><b>同 (agentId, type, level) 限速</b>：{@link AgentProperties#getAlertDedupWindowMinutes()}（默认 5 分钟）内只落一条，
 *       其余只计数；窗口过后的下一条 message 末尾追加「+N similar suppressed」，被压掉的次数不会凭空消失。
 *       key 表是有界 LRU（伪造的 agent id 不能撑爆内存，淘汰只会多放过一条告警）。</li>
 *   <li><b>全局每分钟上限</b>：{@link AgentProperties#getAlertMaxPerMinute()}（默认 120），超出丢弃并（节流地）打 WARN。</li>
 *   <li><b>异步写入</b>：告警落库交给单线程有界队列（256），请求线程只 offer 就返回。原先是同步 save——连接池打满时
 *       每条告警都要在请求线程里白等 30s 连接超时，而且此时的告警恰恰最多。队列满直接丢弃并计数。</li>
 * </ol>
 * 保留期见 {@link AgentAlertRetentionCleaner}。
 *
 * gz
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** 各列长度（与 schema.sql 一致）：伪造头可以塞任意长度，超长会让 INSERT 失败并刷 WARN。 */
    private static final int MAX_AGENT_ID = 64;
    private static final int MAX_USER_CODE = 64;
    private static final int MAX_HOST_HASH = 128;
    private static final int MAX_TYPE = 64;
    private static final int MAX_MESSAGE = 510;

    /** 每 key 限速表的容量上限（LRU）。 */
    private static final int MAX_TRACKED_KEYS = 4096;

    private static final int WRITE_QUEUE_CAPACITY = 256;

    private final AgentAlertRepository alertRepository;
    private final Clock clock;
    private final Executor writer;
    private final ThreadPoolExecutor ownedWriter;
    private final long dedupWindowMs;
    private final int maxPerMinute;

    private final Map<String, Window> windows = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
            return size() > MAX_TRACKED_KEYS;
        }
    };
    private long globalMinute = Long.MIN_VALUE;
    private int globalCount;

    private final AtomicLong dropped = new AtomicLong();
    private final LogThrottle dropLog = new LogThrottle(60_000L);
    private final LogThrottle persistFailLog = new LogThrottle(30_000L);

    private static final class Window {
        long lastWrittenAt;
        long suppressed;
    }

    @Autowired
    public AlertService(AgentAlertRepository alertRepository, AgentProperties props) {
        this(alertRepository, props, Clock.systemDefaultZone(), null);
    }

    /** 单测入口：可注入时钟与同步 executor（{@code Runnable::run}）。writer 为 null 时自建后台线程。 */
    AlertService(AgentAlertRepository alertRepository, AgentProperties props, Clock clock, Executor writer) {
        this.alertRepository = alertRepository;
        this.clock = clock;
        this.dedupWindowMs = Math.max(0L, props.getAlertDedupWindowMinutes()) * 60_000L;
        this.maxPerMinute = props.getAlertMaxPerMinute();
        if (writer != null) {
            this.writer = writer;
            this.ownedWriter = null;
        } else {
            ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY), r -> {
                Thread t = new Thread(r, "agent-alert-writer");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
            this.writer = ex;
            this.ownedWriter = ex;
        }
    }

    /**
     * 记录一条告警：限速 → 入队异步写。永不抛异常、永不阻塞调用线程。
     */
    public void record(String agentId, String userCode, String hostHash,
                       String alertType, String alertLevel, String message) {
        try {
            String base = message;
            long now = clock.millis();
            long suppressedBefore;
            synchronized (this) {
                // level 也进 key：同一 agent 先来一条 WARN（缺头）不该把随后的 ERROR（签名不匹配，可能是攻击）压掉
                String key = agentId + '\0' + alertType + '\0' + alertLevel;
                Window w = windows.computeIfAbsent(key, k -> {
                    Window nw = new Window();
                    nw.lastWrittenAt = Long.MIN_VALUE / 2;
                    return nw;
                });
                if (now - w.lastWrittenAt < dedupWindowMs) {
                    w.suppressed++;
                    return;
                }
                long minute = now / 60_000L;
                if (minute != globalMinute) {
                    globalMinute = minute;
                    globalCount = 0;
                }
                if (globalCount >= maxPerMinute) {
                    w.suppressed++;
                    onDropped();
                    return;
                }
                globalCount++;
                suppressedBefore = w.suppressed;
                w.suppressed = 0;
                w.lastWrittenAt = now;
            }
            if (suppressedBefore > 0) {
                // 后缀放在截断之后拼，否则长 message 会把「被压掉了多少条」这个信息一起截掉
                String suffix = " (+" + suppressedBefore + " similar suppressed in last "
                        + Math.max(1, dedupWindowMs / 60_000L) + "min)";
                base = truncate(base == null ? "" : base, MAX_MESSAGE - suffix.length()) + suffix;
            }
            AgentAlert alert = new AgentAlert();
            alert.setAgentId(truncate(agentId, MAX_AGENT_ID));
            alert.setUserCode(truncate(userCode, MAX_USER_CODE));
            alert.setHostHash(truncate(hostHash, MAX_HOST_HASH));
            alert.setAlertType(truncate(alertType, MAX_TYPE));
            alert.setAlertLevel(alertLevel);
            alert.setMessage(truncate(base, MAX_MESSAGE));
            alert.setEventTime(LocalDateTime.now(clock));
            try {
                writer.execute(() -> persist(alert));
            } catch (RejectedExecutionException e) {
                onDropped();
            }
        } catch (RuntimeException e) {
            // 告警是尽力而为：这里的任何失败都不能传给请求线程
            log.warn("failed to record alert {} {}: {}", alertType, agentId, e.toString());
        }
    }

    private void persist(AgentAlert alert) {
        try {
            alertRepository.save(alert);
        } catch (Exception e) {
            long n = persistFailLog.tryEmit();
            if (n > 0) {
                log.warn("failed to persist alert {} {} ({} failure(s) since last log): {}",
                        alert.getAlertType(), alert.getAgentId(), n, e.toString());
            }
        }
    }

    private void onDropped() {
        dropped.incrementAndGet();
        long n = dropLog.tryEmit();
        if (n > 0) {
            log.warn("agent alerts dropped by rate limit / full write queue: {} in last 60s "
                    + "(max_per_minute={}, total dropped={})", n, maxPerMinute, dropped.get());
        }
    }

    /** 观测 / 测试用：因全局上限或队列满而丢弃的告警总数。 */
    long droppedCount() {
        return dropped.get();
    }

    public void warn(String agentId, String userCode, String hostHash, String type, String message) {
        record(agentId, userCode, hostHash, type, AlertType.LEVEL_WARN, message);
    }

    public void error(String agentId, String userCode, String hostHash, String type, String message) {
        record(agentId, userCode, hostHash, type, AlertType.LEVEL_ERROR, message);
    }

    @PreDestroy
    void shutdown() {
        if (ownedWriter != null) {
            ownedWriter.shutdown();
            try {
                if (!ownedWriter.awaitTermination(2, TimeUnit.SECONDS)) {
                    ownedWriter.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ownedWriter.shutdownNow();
            }
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
