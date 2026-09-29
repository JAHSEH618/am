package com.am.server.agent.security;

import com.am.server.common.ErrorCode;
import com.am.server.common.OverloadFailures;
import com.am.server.common.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;

/**
 * agent 通道上「非 200 信封」响应的统一写法（舱壁与验签两个 filter 共用）。
 *
 * <p>都直接 {@code setStatus} 而不是 {@code sendError}：{@code sendError} 会触发 Boot 的 /error 转发，
 * 把 R 信封换成 BasicErrorController 的 JSON，客户端就认不出 50301 了。
 * gz
 */
final class AgentIngestResponses {

    private AgentIngestResponses() {
    }

    /** 503 + {@link ErrorCode#SERVER_BUSY} + Retry-After：客户端（1.3.3+）据此不落 outbox、退让重报。 */
    static void busy(HttpServletResponse response, ObjectMapper objectMapper, String message) throws IOException {
        response.setHeader("Retry-After", OverloadFailures.RETRY_AFTER_SECONDS);
        write(response, objectMapper, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ErrorCode.SERVER_BUSY, message);
    }

    /**
     * 413 + {@link ErrorCode#PAYLOAD_TOO_LARGE}。带 {@code Connection: close}：请求体没读完，
     * 连接上残留的字节不能被当作下一个请求的开头。
     */
    static void payloadTooLarge(HttpServletResponse response, ObjectMapper objectMapper, String message)
            throws IOException {
        response.setHeader("Connection", "close");
        write(response, objectMapper, 413, ErrorCode.PAYLOAD_TOO_LARGE, message);
    }

    static void write(HttpServletResponse response, ObjectMapper objectMapper, int status, int code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), R.fail(code, message));
    }
}
