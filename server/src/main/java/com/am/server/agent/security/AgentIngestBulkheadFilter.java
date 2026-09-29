package com.am.server.agent.security;

import com.am.server.common.LogThrottle;
import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * agent 上报入口舱壁：在<b>读 body、查库之前</b>用一组独立的限额挡住「服务还没准备好 / 请求太大 / 并发太多」，
 * 被拒一律 HTTP 503 + {@code ErrorCode.SERVER_BUSY}(50301) + {@code Retry-After}（超大 → 413）。
 *
 * <p>为什么需要：{@code /report} 是唯一由客户端数量决定并发的入口，每个会话一个短事务，
 * 但全员同时上报（尤其服务重启后每台 agent 的 outbox 一起补发）时，50 个 Tomcat 线程会把
 * 40 条 Hikari 连接全部占满——连 {@link AgentSignatureFilter} 的 {@code findByAgentId} 都等
 * 30s 超时，客户端失败后又把 body 落 outbox、下个 tick 补发，压力只增不减。
 *
 * <p>客户端契约：只有 HTTP 503 / 429 / 业务码 50301 才会让 1.3.3+ agent「不落 outbox、退回空闲节奏」，
 * 所以本过滤器与 {@link AgentSignatureFilter}、{@code GlobalExceptionHandler} 的所有「过载类」拒绝都用这一个形态。
 *
 * <h3>检查顺序（全部在读 body 之前，越靠前越便宜）</h3>
 * <ol>
 *   <li><b>就绪门</b>：{@link ApplicationAvailability#getReadinessState()} ≠ {@code ACCEPTING_TRAFFIC} → 503
 *       ({@code reason=not_ready})，轻请求也拦。启动期补丁 / 回填是 {@code ApplicationRunner}，在 Tomcat
 *       已经接流量之后才跑，而 Spring Boot 要等<b>所有 ApplicationRunner 跑完</b>才把 readiness 置为
 *       ACCEPTING_TRAFFIC（{@code /actuator/health} 只看 db / 磁盘，补丁没跑完也是 UP，不能拿它当就绪信号）。
 *       补丁 / 回填持有的间隙锁会让 ingest 的 INSERT 等锁 50s，所以这段时间 agent 一律 503 退让、不落 outbox。
 *       {@code /agent/register} 也拦：它写 employee / agent_device，同样会撞上补丁的锁；注册端有重试。
 *       （副作用：优雅停机时 readiness 变 REFUSING_TRAFFIC，也会让新上报 503，正好。）</li>
 *   <li><b>413</b>：Content-Length 已知且 &gt; {@code max-body-bytes}（register 用 {@code register-max-body-bytes}）→
 *       413，不占任何名额、不读 body。chunked 的限长读取在 {@link CachedBodyHttpServletRequest} 里做。</li>
 *   <li><b>轻请求</b>（Content-Length 已知且 ≤ {@value #LIGHT_BODY_BYTES} 字节：设备心跳 / 空闲 tick，以及 register）：
 *       独立小信号量 {@code ingest-light-max-concurrency}（默认 8），至多等 {@code ingest-light-acquire-timeout-ms}
 *       （默认 200ms）。不占重名额——心跳被重上报挤掉会让大盘误判离线——但也不能无上限地打进 DB。</li>
 *   <li><b>重请求</b>：信号量 {@code ingest-max-concurrency}（默认 16，远小于 Hikari 40），<b>默认不等</b>
 *       （{@code ingest-acquire-timeout-ms=0}）：等待会让被拒请求继续占着 Tomcat 线程，把其余 34 个线程也吃光。
 *       随后向字节预算预占（见下），预占不到 → 503 ({@code reason=bytes_budget})。</li>
 * </ol>
 * 验签通过之后的入场限制（同 agent 单在途、老客户端小名额）在 {@link AgentIngestGuard}，见其 Javadoc。
 *
 * <h3>内存预算：最坏情况算术</h3>
 * HMAC 要求整包进内存，gzip 请求还持一份解压副本，再加 Jackson 对象图，估算堆占用见 {@link IngestByteBudget}
 * （明文 3×线上字节；gzip = 线上字节 + 3×解压字节）。默认值下：
 * <ul>
 *   <li>单请求最坏：明文 32MB → 96MB；gzip 32MB 线上 / 64MB 解压 → 32 + 3×64 = <b>224MB</b>。</li>
 *   <li>没有字节预算时：16 并发 × 224MB = <b>3.5GB &gt; {@code -Xmx2g}</b>——并发数只限个数、不限大小，所以 1.3.3 的舱壁
 *       挡不住「少量超大请求」把堆撑爆。</li>
 *   <li>有预算（默认 512MB = 堆的 1/4）：所有在途重请求的估算堆占用 ≤ 512MB，与并发数 / 大小的组合无关——
 *       最多同时 2 个最坏 gzip 请求（448MB），或 5 个 32MB 明文请求；典型请求（300KB gzip → ~3MB 解压 ≈ 9.3MB 计价）
 *       能放下 50+ 个，此时是 16 的并发上限先起作用，预算不影响正常流量。</li>
 *   <li>轻请求：8 并发 × （≤2KB，解压至多 1MB × 3）≈ 24MB 上限，不记预算。</li>
 *   <li>预算需 ≥ 3×{@code max-body-bytes}（默认 96MB ≤ 512MB ✓），否则接近上限的合法请求永远 503；启动时会 WARN。</li>
 * </ul>
 * <b>已知取舍</b>：1.3.3 之前的老客户端首次回填可能产生超限 body，它们会持续收到 413（有意为之，日志里写明原因与版本）；
 * 新客户端按 MaxMessagesPerSession 切片、单包远小于上限，不会触发。
 *
 * <h3>预占发生在验签之前</h3>
 * 未认证请求也会短暂占用预算 / 名额（直到验签阶段在读 body 前因「缺头 / 时间戳失效 / agent 不存在」拒绝它们，
 * 那时立即归还）。要长时间占住预算，攻击者需要一个真实存在的 agent id + 有效时间戳，再慢速灌 body（受 Tomcat
 * connectionTimeout 约束）；这与 1.3.3 的名额被慢速请求占住是同一量级的既有风险，不因本次改动变差。
 * gz
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AgentIngestBulkheadFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentIngestBulkheadFilter.class);

    static final String REPORT_PATH = "/api/v1/agent/report";
    static final String REPORT_COMMITS_PATH = "/api/v1/agent/report-commits";
    static final String REGISTER_PATH = "/api/v1/agent/register";

    /** 请求属性：本请求的在途字节预算租约（{@link IngestByteBudget.Lease}），重请求才有。 */
    static final String ATTR_BYTE_LEASE = AgentIngestBulkheadFilter.class.getName() + ".byteLease";

    /**
     * 轻量通道阈值：设备心跳约 300~600 字节（<1KB 不压缩）；带几个会话的上报 gzip 后通常已过 2KB。
     * 阈值过宽会让老 agent 的小批量上报绕过重名额。
     */
    static final long LIGHT_BODY_BYTES = 2 * 1024;

    /** 拒绝日志节流：满载时每个请求都打 WARN 会把日志打爆。 */
    private static final long REJECT_LOG_INTERVAL_MS = 30_000L;

    /** 每个 agent 的 413 日志间隔：一台卡在 413 的老客户端每 8~45s 重试一次，不节流一天几千行。 */
    private static final long TOO_LARGE_LOG_INTERVAL_MS = 10 * 60_000L;

    private final AgentProperties props;
    private final Semaphore heavyPermits;
    private final Semaphore lightPermits;
    private final int maxConcurrency;
    private final int lightMaxConcurrency;
    private final long acquireTimeoutMs;
    private final long lightAcquireTimeoutMs;
    private final IngestByteBudget byteBudget;
    private final ObjectMapper objectMapper;
    private final ApplicationAvailability availability;
    private final AgentIngestMetrics metrics;
    private final AgentDeviceRepository deviceRepository;

    private final ConcurrentMap<String, LogThrottle> rejectThrottles = new ConcurrentHashMap<>();
    private final LogThrottle.Keyed tooLargeThrottle =
            new LogThrottle.Keyed(TOO_LARGE_LOG_INTERVAL_MS, 2048);

    @Autowired
    public AgentIngestBulkheadFilter(AgentProperties agentProperties,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<ApplicationAvailability> availability,
                                     AgentIngestMetrics metrics,
                                     AgentDeviceRepository deviceRepository) {
        this(agentProperties, objectMapper, availability.getIfAvailable(), metrics, deviceRepository);
    }

    /**
     * @param availability 缺失（未经 Spring Boot 装配的单测 / 切片）时视为已就绪，不拦截
     * @param deviceRepository 仅用于给（节流后的）413 日志补上 agent 版本，可为 null
     */
    AgentIngestBulkheadFilter(AgentProperties agentProperties,
                              ObjectMapper objectMapper,
                              ApplicationAvailability availability,
                              AgentIngestMetrics metrics,
                              AgentDeviceRepository deviceRepository) {
        this.props = agentProperties;
        this.maxConcurrency = Math.max(1, agentProperties.getIngestMaxConcurrency());
        this.lightMaxConcurrency = Math.max(1, agentProperties.getIngestLightMaxConcurrency());
        this.acquireTimeoutMs = Math.max(0L, agentProperties.getIngestAcquireTimeoutMs());
        this.lightAcquireTimeoutMs = Math.max(0L, agentProperties.getIngestLightAcquireTimeoutMs());
        this.heavyPermits = new Semaphore(maxConcurrency, true);
        this.lightPermits = new Semaphore(lightMaxConcurrency, true);
        this.byteBudget = new IngestByteBudget(agentProperties.getIngestInflightBytesBudget());
        this.objectMapper = objectMapper;
        this.availability = availability;
        this.metrics = metrics;
        this.deviceRepository = deviceRepository;
        if (byteBudget.capacity() < IngestByteBudget.HEAP_FACTOR * agentProperties.getMaxBodyBytes()) {
            log.warn("aiwatch.agent.ingest-inflight-bytes-budget={} is below {}x max-body-bytes={}: plain requests "
                            + "near the body limit can never reserve enough budget and will always get 503",
                    byteBudget.capacity(), IngestByteBudget.HEAP_FACTOR, agentProperties.getMaxBodyBytes());
        }
        metrics.bindGauges(this::inflightHeavy, byteBudget::inflightBytes);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !REPORT_PATH.equals(path) && !REPORT_COMMITS_PATH.equals(path) && !REGISTER_PATH.equals(path);
    }

    /** 未知长度（-1，chunked）按重请求处理。 */
    static boolean isLight(long contentLength) {
        return contentLength >= 0 && contentLength <= LIGHT_BODY_BYTES;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 1) 就绪门（含轻请求、含 register）
        if (!isReady()) {
            reject(request, response, AgentIngestMetrics.REASON_NOT_READY,
                    "server is starting (startup patches/backfills still running), retry later");
            return;
        }

        String path = request.getRequestURI();
        boolean register = REGISTER_PATH.equals(path);
        long contentLength = request.getContentLengthLong();

        // 2) 413：Content-Length 已知且超限，一个字节都不读、不占名额
        long maxBody = register ? props.getRegisterMaxBodyBytes() : props.getMaxBodyBytes();
        if (contentLength > maxBody) {
            tooLarge(request, response, contentLength, maxBody);
            return;
        }

        if (register || isLight(contentLength)) {
            doLight(request, response, chain);
        } else {
            doHeavy(request, response, chain, contentLength);
        }
    }

    private void doLight(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean acquired;
        try {
            acquired = lightAcquireTimeoutMs <= 0
                    ? lightPermits.tryAcquire()
                    : lightPermits.tryAcquire(lightAcquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            reject(request, response, AgentIngestMetrics.REASON_LIGHT, "agent ingest busy, retry next tick");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            lightPermits.release();
        }
    }

    private void doHeavy(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                         long contentLength) throws ServletException, IOException {
        boolean acquired;
        try {
            acquired = acquireTimeoutMs <= 0
                    ? heavyPermits.tryAcquire()
                    : heavyPermits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            reject(request, response, AgentIngestMetrics.REASON_CONCURRENCY, "agent ingest busy, retry next tick");
            return;
        }
        IngestByteBudget.Lease lease = null;
        try {
            boolean gzip = CachedBodyHttpServletRequest.isGzip(request.getHeader("Content-Encoding"));
            long raw = contentLength >= 0 ? contentLength : props.getMaxBodyBytes();
            lease = byteBudget.tryOpen(IngestByteBudget.entryCharge(raw, gzip));
            if (lease == null) {
                reject(request, response, AgentIngestMetrics.REASON_BYTES_BUDGET,
                        "agent ingest memory budget exhausted, retry next tick");
                return;
            }
            request.setAttribute(ATTR_BYTE_LEASE, lease);
            chain.doFilter(request, response);
        } finally {
            if (lease != null) {
                lease.close();
            }
            heavyPermits.release();
        }
    }

    private boolean isReady() {
        return availability == null || availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String reason, String message)
            throws IOException {
        metrics.rejected(reason);
        long n = rejectThrottles.computeIfAbsent(reason, r -> new LogThrottle(REJECT_LOG_INTERVAL_MS)).tryEmit();
        if (n > 0) {
            log.warn("agent ingest rejected (503): reason={} — {} request(s) in last {}s (heavy {}/{} in flight,"
                            + " light {}/{}, budget {}/{} bytes; latest {} agent={} bytes={})",
                    reason, n, REJECT_LOG_INTERVAL_MS / 1000, inflightHeavy(), maxConcurrency,
                    lightMaxConcurrency - lightPermits.availablePermits(), lightMaxConcurrency,
                    byteBudget.inflightBytes(), byteBudget.capacity(), request.getRequestURI(),
                    request.getHeader(AgentSignatureFilter.HEADER_AGENT_ID), request.getContentLengthLong());
        }
        AgentIngestResponses.busy(response, objectMapper, message);
    }

    private void tooLarge(HttpServletRequest request, HttpServletResponse response, long contentLength, long limit)
            throws IOException {
        metrics.rejected(AgentIngestMetrics.REASON_TOO_LARGE);
        String agentId = request.getHeader(AgentSignatureFilter.HEADER_AGENT_ID);
        long n = tooLargeThrottle.tryEmit(agentId);
        if (n > 0) {
            log.warn("agent request body too large, rejected with HTTP 413: path={} agent={} version={} "
                            + "content_length={} limit={} encoding={} ({} occurrence(s) since last log for this agent). "
                            + "This is deterministic — resending the same body will always be refused. Typical cause: "
                            + "a pre-1.3.3 client backfilling a huge first report (upgrade it, or drop its outbox files); "
                            + "raise aiwatch.agent.max-body-bytes only if the heap budget allows.",
                    request.getRequestURI(), agentId, lookupVersion(agentId), contentLength, limit,
                    request.getHeader("Content-Encoding"), n);
        }
        AgentIngestResponses.payloadTooLarge(response, objectMapper,
                "request body " + contentLength + " bytes exceeds the server limit of " + limit
                        + " bytes; split the report into smaller batches");
    }

    /** 仅在（节流后的）413 日志里调用：尽力而为地查出该 agent 的注册版本，失败一律 "unknown"。 */
    private String lookupVersion(String agentId) {
        if (deviceRepository == null || agentId == null || agentId.isBlank()) {
            return "unknown";
        }
        try {
            return deviceRepository.findByAgentId(agentId)
                    .map(AgentDevice::getAgentVersion)
                    .filter(v -> !v.isBlank())
                    .orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** 观测用：当前空闲的重名额。 */
    int availablePermits() {
        return heavyPermits.availablePermits();
    }

    /** 观测用：当前空闲的轻名额。 */
    int availableLightPermits() {
        return lightPermits.availablePermits();
    }

    /** 观测用：在途重请求数。 */
    int inflightHeavy() {
        return maxConcurrency - heavyPermits.availablePermits();
    }

    IngestByteBudget byteBudget() {
        return byteBudget;
    }
}
