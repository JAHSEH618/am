package com.am.server.agent.security;

import com.am.server.domain.agent.AgentDevice;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/** agent filter 系列测试的共用构造件。 */
final class AgentFilterTestSupport {

    static final String SECRET = "secret-xyz";

    private AgentFilterTestSupport() {
    }

    static AgentIngestMetrics noMetrics() {
        return new AgentIngestMetrics((MeterRegistry) null);
    }

    static AgentDevice device(String agentId, String version) {
        AgentDevice d = new AgentDevice();
        d.setAgentId(agentId);
        d.setStatus(AgentDevice.STATUS_ACTIVE);
        d.setAgentSecret(SECRET);
        d.setUserCode("U001");
        d.setHostHash("hh");
        d.setAgentVersion(version);
        return d;
    }

    /**
     * 记录「有没有人读过 body、读了多少字节」的请求；chunked=true 时模拟 Content-Length 未知。
     * body 走自己的计数流，不依赖 MockHttpServletRequest 的内部实现。
     */
    static final class TrackingRequest extends MockHttpServletRequest {

        final AtomicBoolean bodyTouched = new AtomicBoolean();
        final AtomicLong bytesRead = new AtomicLong();
        private final byte[] body;
        private final boolean chunked;

        TrackingRequest(String method, String path, byte[] body, boolean chunked) {
            super(method, path);
            this.chunked = chunked;
            this.body = body == null ? new byte[0] : body;
        }

        @Override
        public long getContentLengthLong() {
            return chunked ? -1L : body.length;
        }

        @Override
        public int getContentLength() {
            return chunked ? -1 : body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            bodyTouched.set(true);
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    int b = in.read();
                    if (b >= 0) {
                        bytesRead.incrementAndGet();
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    int n = in.read(b, off, len);
                    if (n > 0) {
                        bytesRead.addAndGet(n);
                    }
                    return n;
                }
            };
        }
    }

    /** 带完整且正确签名头的 POST（body 即线上字节）。 */
    static TrackingRequest signed(String path, String agentId, byte[] wireBody, String nonce, boolean gzip) {
        return signed(path, agentId, wireBody, nonce, gzip, false);
    }

    static TrackingRequest signed(String path, String agentId, byte[] wireBody, String nonce, boolean gzip,
                                  boolean chunked) {
        TrackingRequest req = new TrackingRequest("POST", path, wireBody, chunked);
        String ts = String.valueOf(System.currentTimeMillis());
        req.addHeader(AgentSignatureFilter.HEADER_AGENT_ID, agentId);
        req.addHeader(AgentSignatureFilter.HEADER_TS, ts);
        req.addHeader(AgentSignatureFilter.HEADER_NONCE, nonce);
        req.addHeader(AgentSignatureFilter.HEADER_SIGN, HmacUtil.sign(SECRET, wireBody, ts, nonce));
        if (gzip) {
            req.addHeader("Content-Encoding", "gzip");
        }
        return req;
    }

    static byte[] gzip(byte[] plain) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(plain);
            gz.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] zeros(int n) {
        return new byte[n];
    }

    static byte[] json(int approxBytes) {
        StringBuilder sb = new StringBuilder("{\"k\":\"");
        while (sb.length() < approxBytes) {
            sb.append('x');
        }
        return sb.append("\"}").toString().getBytes(StandardCharsets.UTF_8);
    }
}
