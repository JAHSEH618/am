package com.am.server.agent.security;

import com.am.server.common.ErrorCode;
import com.am.server.common.R;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.domain.agent.AlertType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Agent 上报接口签名校验过滤器
 *
 * 校验顺序：
 * 1. 头齐全：X-Agent-Id / X-Agent-Ts / X-Agent-Nonce / X-Agent-Sign
 * 2. 时间戳在 ±300 秒窗口内
 * 3. nonce 在 (agent_id, nonce) 唯一索引上未占用
 * 4. agent_id 存在 agent_device 且 status=ACTIVE
 * 5. HMAC-SHA256(agent_secret, body+ts+nonce) 与 X-Agent-Sign 等值（常量时间比较）
 *
 * 不参与校验的路径：/api/v1/agent/register（首次注册时还没有密钥）
 *
 * gz
 */
@Component
@RequiredArgsConstructor
public class AgentSignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentSignatureFilter.class);

    public static final String HEADER_AGENT_ID = "X-Agent-Id";
    public static final String HEADER_TS = "X-Agent-Ts";
    public static final String HEADER_NONCE = "X-Agent-Nonce";
    public static final String HEADER_SIGN = "X-Agent-Sign";

    private static final long TIMESTAMP_WINDOW_MS = 300_000L;
    private static final String GUARDED_PREFIX = "/api/v1/agent/";
    private static final String UNGUARDED_REGISTER = "/api/v1/agent/register";

    private final AgentDeviceRepository deviceRepository;
    private final NonceStoreService nonceStoreService;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(GUARDED_PREFIX) || path.equals(UNGUARDED_REGISTER);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CachedBodyHttpServletRequest wrapped;
        try {
            wrapped = new CachedBodyHttpServletRequest(request);
        } catch (IOException e) {
            // body 读取或 gzip 解压失败：当作无效签名拒绝，避免暴露底层异常给员工 client
            log.warn("cache request body failed: {}", e.toString());
            writeFail(response, ErrorCode.INVALID_SIGNATURE, "invalid request body");
            return;
        }

        String agentId = wrapped.getHeader(HEADER_AGENT_ID);
        String ts = wrapped.getHeader(HEADER_TS);
        String nonce = wrapped.getHeader(HEADER_NONCE);
        String sign = wrapped.getHeader(HEADER_SIGN);

        if (isBlank(agentId) || isBlank(ts) || isBlank(nonce) || isBlank(sign)) {
            writeFail(response, ErrorCode.INVALID_SIGNATURE, "missing signature headers");
            alertService.warn(agentId, null, null, AlertType.SIGNATURE_INVALID, "missing signature headers");
            return;
        }

        long requestTs;
        try {
            requestTs = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            writeFail(response, ErrorCode.TIMESTAMP_OUT_OF_WINDOW, "invalid timestamp format");
            alertService.warn(agentId, null, null, AlertType.SIGNATURE_INVALID, "invalid timestamp format");
            return;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - requestTs) > TIMESTAMP_WINDOW_MS) {
            writeFail(response, ErrorCode.TIMESTAMP_OUT_OF_WINDOW,
                    "timestamp out of ±300s window");
            alertService.warn(agentId, null, null, AlertType.SIGNATURE_INVALID,
                    "timestamp drift " + (now - requestTs) + "ms");
            return;
        }

        AgentDevice device = deviceRepository.findByAgentId(agentId).orElse(null);
        if (device == null || !AgentDevice.STATUS_ACTIVE.equals(device.getStatus())) {
            writeFail(response, ErrorCode.AGENT_NOT_FOUND, "agent not found or inactive");
            alertService.error(agentId, null, null, AlertType.SIGNATURE_INVALID,
                    "agent not found or inactive");
            return;
        }

        if (!nonceStoreService.tryClaim(agentId, nonce, ts)) {
            writeFail(response, ErrorCode.NONCE_REPLAY, "nonce replay detected");
            alertService.error(agentId, device.getUserCode(), device.getHostHash(),
                    AlertType.NONCE_REPLAY, "nonce=" + nonce);
            return;
        }

        String expected = HmacUtil.sign(device.getAgentSecret(), wrapped.getCachedBody(), ts, nonce);
        if (!HmacUtil.equalsConstantTime(expected, sign.toLowerCase())) {
            writeFail(response, ErrorCode.INVALID_SIGNATURE, "signature mismatch");
            alertService.error(agentId, device.getUserCode(), device.getHostHash(),
                    AlertType.SIGNATURE_INVALID, "signature mismatch");
            return;
        }

        wrapped.setAttribute(SignatureContext.ATTR,
                new SignatureContext(agentId, device.getUserCode(), device.getHostHash()));

        chain.doFilter(wrapped, response);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private void writeFail(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), R.fail(code, message));
    }
}
