package com.am.server.agent.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.zip.GZIPInputStream;

/**
 * 带 body 缓存的 HttpServletRequest 包装器
 *
 * <p>让 SignatureFilter 与 Controller 都能多次读取请求体；并在收到 Content-Encoding: gzip 时
 * 自动解压：HMAC 仍对**线上字节**（cachedBody）计算（与 client 端签名口径一致），Controller 拿到的
 * getInputStream / getReader 已经是解压后的 JSON。
 *
 * <p>设计要点：
 * <ul>
 *   <li>cachedBody 永远是"线上字节"——AgentSignatureFilter HMAC 校验唯一来源</li>
 *   <li>decodedBody = Content-Encoding=gzip 时为解压字节，否则与 cachedBody 同引用</li>
 *   <li>getInputStream / getReader 返回 decodedBody，让下游 @RequestBody 无感知</li>
 *   <li>构造函数解压失败抛 IOException，由 filter 转成 INVALID_SIGNATURE 业务错误</li>
 * </ul>
 *
 * gz
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    /** 与 agent 端 reporter.EncodingGzip 对齐 */
    private static final String ENCODING_GZIP = "gzip";

    private final byte[] cachedBody;
    private final byte[] decodedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
        this.decodedBody = maybeDecode(request.getHeader("Content-Encoding"), this.cachedBody);
    }

    /** 返回 client 上送的"线上字节"（含 gzip 头），仅供 HMAC 校验使用。 */
    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedServletInputStream(new ByteArrayInputStream(decodedBody));
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding() != null
                ? java.nio.charset.Charset.forName(getCharacterEncoding())
                : java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 当 Content-Encoding 为 gzip 时把 cached 字节解压；其它情况按原样返回（避免无意义拷贝）。
     * 解压结果体积通常 5~10x cached，不做硬上限——agent 端 reporter 已经在 Snapshot 阶段做切片，
     * 单次 body 体积可控。
     */
    private static byte[] maybeDecode(String contentEncoding, byte[] raw) throws IOException {
        if (contentEncoding == null || raw == null || raw.length == 0) {
            return raw;
        }
        if (!ENCODING_GZIP.equalsIgnoreCase(contentEncoding.trim())) {
            return raw;
        }
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return StreamUtils.copyToByteArray(gz);
        } catch (IOException e) {
            throw new IOException("decode gzip body failed: " + e.getMessage(), e);
        }
    }

    private static class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        CachedServletInputStream(ByteArrayInputStream input) {
            this.input = input;
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
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
            return input.read();
        }
    }
}
