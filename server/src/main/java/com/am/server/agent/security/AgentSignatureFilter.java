package com.am.server.agent.security;

import com.am.server.common.ErrorCode;
import com.am.server.common.LogThrottle;
import com.am.server.common.OverloadFailures;
import com.am.server.common.R;
import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.domain.agent.AlertType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Agent 上报接口签名校验过滤器
 *
 * <p>校验顺序（<b>除第 5 步外都在读 body 之前</b>——整包进内存是 HMAC 的代价，能不读就不读）：
 * <ol>
 *   <li>method 只允许 POST、路径只允许已知的受保护端点（{@code /report}、{@code /report-commits}）：
 *       非 POST / 未知的 {@code /api/v1/agent/*} 直接 405 / 404</li>
 *   <li>头齐全：X-Agent-Id / X-Agent-Ts / X-Agent-Nonce / X-Agent-Sign</li>
 *   <li>时间戳在 ±300 秒窗口内</li>
 *   <li>agent_id 存在 agent_device 且 status=ACTIVE（DB 访问；连接池 / 超时类失败 → 503 + 50301）</li>
 *   <li>Content-Length 超过 {@code max-body-bytes} → 413（舱壁通常已先拦，这里兜底）；
 *       然后限长读取 body（chunked 读到上限 + 1 即 413；gzip 解压有大小与压缩比上限，超限 413）</li>
 *   <li>HMAC-SHA256(agent_secret, body+ts+nonce) 与 X-Agent-Sign 等值（常量时间比较）</li>
 *   <li>入场限制（{@link AgentIngestGuard}）：同一 agent 同时最多一个重请求、老客户端小名额；超额 503 + 50301</li>
 *   <li>nonce 在 (agent_id, nonce) 唯一索引上未占用（验签通过后才写 DB）</li>
 * </ol>
 *
 * <p>错误响应的两种形态（客户端据此分流，改动前务必对照 {@code agent/internal/apiclient}）：
 * <ul>
 *   <li>鉴权类（缺头、时间戳、agent 不存在、签名错、重放）：HTTP 200 + {@code R.fail(10001..10004)}——
 *       {@code AGENT_NOT_FOUND} 会触发客户端清凭证重新注册，语义不能变；</li>
 *   <li>过载类：HTTP 503 + 50301 + Retry-After；超大：HTTP 413 + 41301；method / path 不对：405 / 404。</li>
 * </ul>
 *
 * <p><b>本类里的 DB 访问</b>（{@code findByAgentId}、{@code nonceStoreService.tryClaim}）发生在
 * filter 层，抛出的异常<b>不会</b>经过 {@code @RestControllerAdvice}，所以这里自己 try/catch 并按
 * {@link OverloadFailures} 判定：过载类 → 503；其它 → 500 + {@code log.error}。告警写入
 * （{@link AlertService}）是异步、限速、吞异常的，写失败绝不影响本请求。
 *
 * <p>不参与校验的路径：/api/v1/agent/register（首次注册时还没有密钥）
 *
 * gz
 */
@Component
public class AgentSignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentSignatureFilter.class);

    public static final String HEADER_AGENT_ID = "X-Agent-Id";
    public static final String HEADER_TS = "X-Agent-Ts";
    public static final String HEADER_NONCE = "X-Agent-Nonce";
    public static final String HEADER_SIGN = "X-Agent-Sign";

    private static final long TIMESTAMP_WINDOW_MS = 300_000L;
    private static final String GUARDED_PREFIX = "/api/v1/agent/";
    private static final String UNGUARDED_REGISTER = "/api/v1/agent/register";

    /** 需要验签的端点白名单。新增受保护的 agent 端点时必须同步加进来，否则会被当作未知路径 404。 */
    private static final Set<String> GUARDED_ENDPOINTS = Set.of(
            AgentIngestBulkheadFilter.REPORT_PATH,
            AgentIngestBulkheadFilter.REPORT_COMMITS_PATH);

    private static final long REJECT_LOG_INTERVAL_MS = 30_000L;

    private final AgentDeviceRepository deviceRepository;
    private final NonceStoreService nonceStoreService;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;
    private final AgentProperties props;
    private final AgentIngestGuard ingestGuard;
    private final AgentIngestMetrics metrics;

    private final LogThrottle dbFailureThrottle = new LogThrottle(REJECT_LOG_INTERVAL_MS);
    private final LogThrottle guardRejectThrottle = new LogThrottle(REJECT_LOG_INTERVAL_MS);
    private final LogThrottle.Keyed tooLargeThrottle = new LogThrottle.Keyed(10 * 60_000L, 2048);

    @Autowired
    public AgentSignatureFilter(AgentDeviceRepository deviceRepository,
                                NonceStoreService nonceStoreService,
                                AlertService alertService,
                                ObjectMapper objectMapper,
                                AgentProperties props,
                                AgentIngestGuard ingestGuard,
                                AgentIngestMetrics metrics) {
        this.deviceRepository = deviceRepository;
        this.nonceStoreService = nonceStoreService;
        this.alertService = alertService;
        this.objectMapper = objectMapper;
        this.props = props;
        this.ingestGuard = ingestGuard;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(GUARDED_PREFIX) || path.equals(UNGUARDED_REGISTER);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 1) method / path：读 body 之前就拒绝，扫描器和误配置的客户端不该让我们分配任何缓冲区
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.setHeader("Allow", "POST");
            AgentIngestResponses.write(response, objectMapper, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    ErrorCode.PARAM_INVALID, "method not allowed, use POST");
            return;
        }
        if (!GUARDED_ENDPOINTS.contains(request.getRequestURI())) {
            AgentIngestResponses.write(response, objectMapper, HttpServletResponse.SC_NOT_FOUND,
                    ErrorCode.RESOURCE_NOT_FOUND, "unknown agent endpoint");
            return;
        }

        // 2) 头齐全（只读头，不读 body）
        String agentId = request.getHeader(HEADER_AGENT_ID);
        String ts = request.getHeader(HEADER_TS);
        String nonce = request.getHeader(HEADER_NONCE);
        String sign = request.getHeader(HEADER_SIGN);

        if (isBlank(agentId) || isBlank(ts) || isBlank(nonce) || isBlank(sign)) {
            writeFail(response, ErrorCode.INVALID_SIGNATURE, "missing signature headers");
            warn(agentId, null, null, AlertType.SIGNATURE_INVALID, "missing signature headers");
            return;
        }

        // 3) 时间戳窗口
        long requestTs;
        try {
            requestTs = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            writeFail(response, ErrorCode.TIMESTAMP_OUT_OF_WINDOW, "invalid timestamp format");
            warn(agentId, null, null, AlertType.SIGNATURE_INVALID, "invalid timestamp format");
            return;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - requestTs) > TIMESTAMP_WINDOW_MS) {
            writeFail(response, ErrorCode.TIMESTAMP_OUT_OF_WINDOW,
                    "timestamp out of ±300s window");
            warn(agentId, null, null, AlertType.SIGNATURE_INVALID,
                    "timestamp drift " + (now - requestTs) + "ms");
            return;
        }

        // 4) agent 存在且 ACTIVE（DB；filter 层异常不经过 ControllerAdvice，必须自己映射）
        AgentDevice device;
        try {
            device = deviceRepository.findByAgentId(agentId).orElse(null);
        } catch (RuntimeException e) {
            writeDbFailure(request, response, "agent lookup", e);
            return;
        }
        if (device == null || !AgentDevice.STATUS_ACTIVE.equals(device.getStatus())) {
            writeFail(response, ErrorCode.AGENT_NOT_FOUND, "agent not found or inactive");
            error(agentId, null, null, AlertType.SIGNATURE_INVALID,
                    "agent not found or inactive");
            return;
        }

        // 5) 读 body（限长；超限 413，预算不足 503）
        long declared = request.getContentLengthLong();
        if (declared > props.getMaxBodyBytes()) {
            writeTooLarge(request, response, device, "request body " + declared + " bytes exceeds limit "
                    + props.getMaxBodyBytes(), declared);
            return;
        }
        Object leaseAttr = request.getAttribute(AgentIngestBulkheadFilter.ATTR_BYTE_LEASE);
        IngestByteBudget.Lease lease = leaseAttr instanceof IngestByteBudget.Lease l ? l : null;
        CachedBodyHttpServletRequest wrapped;
        try {
            wrapped = new CachedBodyHttpServletRequest(request,
                    CachedBodyHttpServletRequest.BodyLimits.from(props), lease);
        } catch (CachedBodyHttpServletRequest.BodyTooLargeException e) {
            writeTooLarge(request, response, device, e.getMessage(), declared);
            return;
        } catch (CachedBodyHttpServletRequest.BodyBudgetExceededException e) {
            metrics.rejected(AgentIngestMetrics.REASON_BYTES_BUDGET);
            logGuardReject(request, agentId, AgentIngestMetrics.REASON_BYTES_BUDGET);
            AgentIngestResponses.busy(response, objectMapper, "agent ingest memory budget exhausted, retry next tick");
            return;
        } catch (IOException e) {
            // body 读取或 gzip 解压失败：当作无效签名拒绝，避免暴露底层异常给员工 client
            log.warn("cache request body failed: {}", e.toString());
            writeFail(response, ErrorCode.INVALID_SIGNATURE, "invalid request body");
            return;
        }

        // 6) 先验签(无状态、纯 CPU):坏签名直接拒,避免给伪造请求写 agent_nonce 行
        String expected = HmacUtil.sign(device.getAgentSecret(), wrapped.getCachedBody(), ts, nonce);
        if (!HmacUtil.equalsConstantTime(expected, sign.toLowerCase())) {
            writeFail(response, ErrorCode.INVALID_SIGNATURE, "signature mismatch");
            error(agentId, device.getUserCode(), device.getHostHash(),
                    AlertType.SIGNATURE_INVALID, "signature mismatch");
            return;
        }

        // 7) 入场限制：验签通过才证明请求真来自该 agent，此时才能占它的 per-agent 名额（见 AgentIngestGuard）。
        //    只限「重」请求（与舱壁同一判据：Content-Length 未知或 >2KB），心跳不受影响。
        boolean heavy = !AgentIngestBulkheadFilter.isLight(declared);
        try (AgentIngestGuard.Admission admission = heavy
                ? ingestGuard.tryAdmit(agentId, device.getAgentVersion())
                : AgentIngestGuard.Admission.NONE) {
            if (admission.denied() != null) {
                metrics.rejected(admission.denied());
                logGuardReject(request, agentId, admission.denied());
                AgentIngestResponses.busy(response, objectMapper,
                        AgentIngestMetrics.REASON_PER_AGENT.equals(admission.denied())
                                ? "another heavy report from this agent is still in flight, retry next tick"
                                : "legacy client quota exhausted, upgrade the agent to 1.3.3+ or retry next tick");
                return;
            }

            // 8) 验签通过后再占 nonce(此时才有写 DB 的资格)
            boolean claimed;
            try {
                claimed = nonceStoreService.tryClaim(agentId, nonce, ts);
            } catch (RuntimeException e) {
                writeDbFailure(request, response, "nonce claim", e);
                return;
            }
            if (!claimed) {
                writeFail(response, ErrorCode.NONCE_REPLAY, "nonce replay detected");
                error(agentId, device.getUserCode(), device.getHostHash(),
                        AlertType.NONCE_REPLAY, "nonce=" + nonce);
                return;
            }

            wrapped.setAttribute(SignatureContext.ATTR,
                    new SignatureContext(agentId, device.getUserCode(), device.getHostHash()));

            chain.doFilter(wrapped, response);
        }
    }

    /** 告警是尽力而为：AlertService 自身已经异步 + 吞异常，这里再兜一层，确保任何实现都不会让请求失败。 */
    private void warn(String agentId, String userCode, String hostHash, String type, String message) {
        try {
            alertService.warn(agentId, userCode, hostHash, type, message);
        } catch (RuntimeException e) {
            log.warn("alert write failed (ignored) {} {}: {}", type, agentId, e.toString());
        }
    }

    private void error(String agentId, String userCode, String hostHash, String type, String message) {
        try {
            alertService.error(agentId, userCode, hostHash, type, message);
        } catch (RuntimeException e) {
            log.warn("alert write failed (ignored) {} {}: {}", type, agentId, e.toString());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    /** 鉴权类失败：HTTP 200 + R.fail(业务码)，客户端按业务码分流（AGENT_NOT_FOUND 会触发重新注册）。 */
    private void writeFail(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), R.fail(code, message));
    }

    /**
     * filter 层 DB 访问失败：过载 / 连接不可用 → 503 + 50301（客户端不落 outbox、退让）；
     * 其它 → 500 + log.error（是 bug，要让人看见，且不能伪装成 200）。
     */
    private void writeDbFailure(HttpServletRequest request, HttpServletResponse response, String what,
                                RuntimeException e) throws IOException {
        if (OverloadFailures.isOverload(e)) {
            metrics.rejected(AgentIngestMetrics.REASON_DB_UNAVAILABLE);
            long n = dbFailureThrottle.tryEmit();
            if (n > 0) {
                log.warn("agent signature filter: {} failed, answering 503 ({} request(s) since last log): {}",
                        what, n, e.toString());
            }
            AgentIngestResponses.busy(response, objectMapper, "database busy, retry next tick");
            return;
        }
        log.error("agent signature filter: unexpected failure during {} on {}", what, request.getRequestURI(), e);
        AgentIngestResponses.write(response, objectMapper, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR, "internal server error");
    }

    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response, AgentDevice device,
                               String detail, long declared) throws IOException {
        metrics.rejected(AgentIngestMetrics.REASON_TOO_LARGE);
        long n = tooLargeThrottle.tryEmit(device.getAgentId());
        if (n > 0) {
            log.warn("agent request body too large, rejected with HTTP 413: path={} agent={} version={} "
                            + "content_length={} encoding={} — {} ({} occurrence(s) since last log for this agent). "
                            + "Deterministic: resending the same body will always be refused. Typical cause: a "
                            + "pre-1.3.3 client backfilling a huge first report (upgrade it or drop its outbox files), "
                            + "or a decompression bomb.",
                    request.getRequestURI(), device.getAgentId(), device.getAgentVersion(), declared,
                    request.getHeader("Content-Encoding"), detail, n);
        }
        AgentIngestResponses.payloadTooLarge(response, objectMapper,
                "request body too large: " + detail + "; split the report into smaller batches");
    }

    private void logGuardReject(HttpServletRequest request, String agentId, String reason) {
        long n = guardRejectThrottle.tryEmit();
        if (n > 0) {
            log.warn("agent ingest rejected (503): reason={} — {} request(s) since last log; latest {} agent={}",
                    reason, n, request.getRequestURI(), agentId);
        }
    }
}
