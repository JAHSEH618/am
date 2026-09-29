package com.am.server.agent.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * agent 上报入口的指标：被拒计数 + 在途 gauge。
 *
 * <ul>
 *   <li>{@code aiwatch.agent.ingest.rejected{reason=…}}：counter，reason 取 {@code concurrency}（重名额满）/
 *       {@code light}（轻名额满）/ {@code per_agent}（同 agent 已有重请求在途）/ {@code legacy}（老客户端小名额满）/
 *       {@code bytes_budget}（在途字节预算满）/ {@code not_ready}（启动期补丁未跑完）/
 *       {@code too_large}（413）/ {@code db_unavailable}（验签 filter 里的 DB 访问因连接池 / 超时 / 锁冲突失败）。</li>
 *   <li>{@code aiwatch.agent.ingest.inflight}：gauge，在途重请求数。</li>
 *   <li>{@code aiwatch.agent.ingest.inflight.bytes}：gauge，在途估算堆字节（见 {@link IngestByteBudget}）。</li>
 * </ul>
 *
 * <p>{@link MeterRegistry} 经 {@link ObjectProvider} 可选注入，缺失时只维护进程内计数（{@link #rejectedCount}），
 * 不报错。actuator 目前只暴露 health,info（见 application.yml），指标要接 Prometheus / 开 {@code metrics} 端点后才能被抓到。
 * gz
 */
@Component
public class AgentIngestMetrics {

    public static final String REASON_CONCURRENCY = "concurrency";
    public static final String REASON_LIGHT = "light";
    public static final String REASON_PER_AGENT = "per_agent";
    public static final String REASON_LEGACY = "legacy";
    public static final String REASON_BYTES_BUDGET = "bytes_budget";
    public static final String REASON_NOT_READY = "not_ready";
    public static final String REASON_TOO_LARGE = "too_large";
    /** 验签 filter 里的 DB 访问因连接池 / 超时 / 锁冲突失败而回 503。 */
    public static final String REASON_DB_UNAVAILABLE = "db_unavailable";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LongAdder> local = new ConcurrentHashMap<>();

    @Autowired
    public AgentIngestMetrics(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfUnique());
    }

    AgentIngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void rejected(String reason) {
        local.computeIfAbsent(reason, r -> new LongAdder()).increment();
        if (registry != null) {
            counters.computeIfAbsent(reason, r -> Counter.builder("aiwatch.agent.ingest.rejected")
                    .description("agent ingest requests rejected before business processing")
                    .tag("reason", r)
                    .register(registry)).increment();
        }
    }

    /** 进程内累计的被拒数（不依赖 MeterRegistry，测试 / 排障用）。 */
    public long rejectedCount(String reason) {
        LongAdder a = local.get(reason);
        return a == null ? 0L : a.sum();
    }

    /** 绑定两个 gauge；registry 缺失时忽略。重复绑定同名 gauge 时 Micrometer 保留最先注册的那个。 */
    public void bindGauges(IntSupplier inflightRequests, LongSupplier inflightBytes) {
        if (registry == null) {
            return;
        }
        // 用 Supplier 重载：它对 supplier 持强引用；(obj, fn) 重载只持弱引用，lambda 被 GC 后 gauge 会变 NaN。
        Gauge.builder("aiwatch.agent.ingest.inflight", () -> inflightRequests.getAsInt())
                .description("heavy agent ingest requests currently in flight")
                .register(registry);
        Gauge.builder("aiwatch.agent.ingest.inflight.bytes", () -> inflightBytes.getAsLong())
                .description("estimated heap bytes reserved by in-flight heavy agent ingest requests")
                .baseUnit("bytes")
                .register(registry);
    }
}
