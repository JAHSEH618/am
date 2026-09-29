package com.am.server.agent.security;

import com.am.server.config.AgentProperties;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
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
 * <p><b>硬上限（内存保护）</b>——整包进内存是 HMAC 的代价，所以读和解压都必须有上限，不能信任客户端：
 * <ul>
 *   <li><b>线上字节</b> ≤ {@link BodyLimits#maxBodyBytes()}：Content-Length 已知且超限直接抛
 *       {@link BodyTooLargeException}（一个字节都不读）；chunked（长度未知）时限长读取，读到上限 + 1 即中止。</li>
 *   <li><b>解压后字节</b> ≤ {@link BodyLimits#maxDecodedBytes()}，且 ≤ 线上字节 × {@link BodyLimits#maxGzipRatio()}
 *       （下限 {@value #MIN_DECODED_ALLOWANCE} 字节，避免极小 body 被比例误伤）：解压是流式的，
 *       一超限立刻中止并抛 {@link BodyTooLargeException}——1KB 的 gzip 炸弹展开不到 1MB 就被掐断，
 *       不会把 GB 级数据展开进堆。</li>
 *   <li>传入 {@link IngestByteBudget.Lease} 时，读完 body 校正预占额、解压时随输出增长追加预占；
 *       预算不足抛 {@link BodyBudgetExceededException}（→ 503，不是 413：这不是请求本身的问题）。</li>
 * </ul>
 * 1.3.3 之前的老客户端首次回填可能产生超限的 body：它们会持续被 413，这是有意的（见 {@code ErrorCode.PAYLOAD_TOO_LARGE}）。
 *
 * gz
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    /** 与 agent 端 reporter.EncodingGzip 对齐 */
    private static final String ENCODING_GZIP = "gzip";

    /** 压缩比限制的保底解压额度：再小的 body 也至少允许展开到这么多。 */
    static final long MIN_DECODED_ALLOWANCE = 1024L * 1024L;

    private static final int READ_CHUNK = 64 * 1024;

    /** JVM 对数组长度的实际上限（略小于 Integer.MAX_VALUE）。 */
    private static final long MAX_ARRAY_BYTES = Integer.MAX_VALUE - 8L;

    /** body / 解压后大小上限。 */
    public record BodyLimits(long maxBodyBytes, long maxDecodedBytes, int maxGzipRatio) {

        /** 与 {@link AgentProperties} 默认值一致，供不经 Spring 装配的调用方 / 老测试使用。 */
        public static final BodyLimits DEFAULT = new BodyLimits(32L << 20, 64L << 20, 100);

        public static BodyLimits from(AgentProperties p) {
            return new BodyLimits(p.getMaxBodyBytes(), p.getMaxDecodedBodyBytes(), p.getMaxGzipRatio());
        }
    }

    /** 线上字节或解压后字节超限（含 gzip 炸弹）。filter 据此回 HTTP 413。 */
    public static final class BodyTooLargeException extends IOException {
        private final boolean decoded;
        private final long limit;

        BodyTooLargeException(String message, boolean decoded, long limit) {
            super(message);
            this.decoded = decoded;
            this.limit = limit;
        }

        /** true = 解压后超限（或压缩比失常）；false = 线上字节超限。 */
        public boolean isDecoded() {
            return decoded;
        }

        public long getLimit() {
            return limit;
        }
    }

    /** 在途字节预算不足。filter 据此回 HTTP 503 + 50301。 */
    public static final class BodyBudgetExceededException extends IOException {
        BodyBudgetExceededException(String message) {
            super(message);
        }
    }

    private final byte[] cachedBody;
    /** 解压缓冲区，有效长度 decodedLength（可能有尾部空余，不再拷贝一份精确长度的数组以免瞬时翻倍）。 */
    private final byte[] decodedBody;
    private final int decodedLength;

    /** 使用默认上限、无预算租约。 */
    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        this(request, BodyLimits.DEFAULT, null);
    }

    /**
     * @param lease 在途字节预算租约（可为 null = 不记账，如轻请求 / 单测）
     * @throws BodyTooLargeException       线上字节或解压后字节超限
     * @throws BodyBudgetExceededException 预算不足
     */
    public CachedBodyHttpServletRequest(HttpServletRequest request, BodyLimits limits,
                                        IngestByteBudget.Lease lease) throws IOException {
        super(request);
        // 数组下标是 int：再大的配置值也不可能装进一个 byte[]
        long maxBody = Math.min(limits.maxBodyBytes(), MAX_ARRAY_BYTES);
        long declared = request.getContentLengthLong();
        if (declared > maxBody) {
            throw new BodyTooLargeException("request body " + declared + " bytes exceeds limit "
                    + maxBody, false, maxBody);
        }
        boolean gzip = isGzip(request.getHeader("Content-Encoding"));
        this.cachedBody = readBounded(request.getInputStream(), declared, maxBody);
        if (lease != null && !lease.tryResize(IngestByteBudget.entryCharge(cachedBody.length, gzip))) {
            // 只可能出现在「预占额小于实际」时（Content-Length 撒谎）；正常路径是缩小，必成功。
            throw new BodyBudgetExceededException("ingest byte budget exhausted while caching body");
        }
        if (gzip && cachedBody.length > 0) {
            DecodedBody d = gunzipBounded(cachedBody, limits, lease);
            this.decodedBody = d.bytes();
            this.decodedLength = d.length();
        } else {
            this.decodedBody = cachedBody;
            this.decodedLength = cachedBody.length;
        }
    }

    /** 返回 client 上送的"线上字节"（含 gzip 头），仅供 HMAC 校验使用。 */
    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedServletInputStream(new ByteArrayInputStream(decodedBody, 0, decodedLength));
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding() != null
                ? java.nio.charset.Charset.forName(getCharacterEncoding())
                : java.nio.charset.StandardCharsets.UTF_8));
    }

    static boolean isGzip(String contentEncoding) {
        return contentEncoding != null && ENCODING_GZIP.equalsIgnoreCase(contentEncoding.trim());
    }

    /**
     * 限长读取：最多读 {@code max} 字节，再多一个字节即抛 {@link BodyTooLargeException}。
     * Content-Length 已知（且调用方已确认 ≤ max）时按它精确分配、一次读满，没有扩容也没有多余拷贝；
     * 未知（chunked）时从 8KB 起倍增，缓冲区上限 max + 1——读到 max + 1 就说明超限。
     */
    private static byte[] readBounded(InputStream in, long declared, long max) throws IOException {
        if (declared >= 0) {
            byte[] exact = new byte[(int) declared];
            int got = 0;
            while (got < exact.length) {
                int n = in.read(exact, got, exact.length - got);
                if (n < 0) {
                    break;   // 客户端中途断开：返回已读到的部分，HMAC 必然不匹配
                }
                got += n;
            }
            return got == exact.length ? exact : Arrays.copyOf(exact, got);
        }
        byte[] buf = new byte[(int) Math.min(8 * 1024, max + 1)];
        int len = 0;
        while (true) {
            if (len == buf.length) {
                buf = Arrays.copyOf(buf, (int) Math.min((long) len * 2, max + 1));
            }
            int n = in.read(buf, len, buf.length - len);
            if (n < 0) {
                break;
            }
            len += n;
            if (len > max) {
                throw new BodyTooLargeException("request body exceeds limit " + max + " bytes", false, max);
            }
        }
        return len == buf.length ? buf : Arrays.copyOf(buf, len);
    }

    private record DecodedBody(byte[] bytes, int length) {
    }

    /**
     * 流式 gzip 解压，输出超过 {@code min(maxDecoded, max(MIN_DECODED_ALLOWANCE, raw × ratio))} 立即中止。
     * 每读一块就向预算租约追加 {@code 3 × 已解压字节}（解压副本 + 对象图，见 {@link IngestByteBudget}）。
     */
    private static DecodedBody gunzipBounded(byte[] raw, BodyLimits limits, IngestByteBudget.Lease lease)
            throws IOException {
        long byRatio = Math.max(MIN_DECODED_ALLOWANCE, (long) raw.length * Math.max(1, limits.maxGzipRatio()));
        long allowed = Math.min(Math.min(limits.maxDecodedBytes(), MAX_ARRAY_BYTES - 1), byRatio);
        boolean ratioBound = byRatio < limits.maxDecodedBytes();
        long rawCharge = IngestByteBudget.entryCharge(raw.length, true);

        byte[] out = new byte[(int) Math.min(allowed + 1, Math.max(8 * 1024, (long) raw.length * 4))];
        int len = 0;
        byte[] chunk = new byte[Math.min(READ_CHUNK, (int) Math.min(allowed + 1, READ_CHUNK))];
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            while (true) {
                int n = gz.read(chunk, 0, chunk.length);
                if (n < 0) {
                    break;
                }
                if (len + (long) n > allowed) {
                    throw new BodyTooLargeException(ratioBound
                            ? "gzip body inflates beyond " + limits.maxGzipRatio() + ":1 (limit " + allowed + " bytes)"
                            : "decoded body exceeds limit " + allowed + " bytes", true, allowed);
                }
                if (len + n > out.length) {
                    out = Arrays.copyOf(out, (int) Math.min(Math.max((long) out.length * 2, len + n), allowed + 1));
                }
                System.arraycopy(chunk, 0, out, len, n);
                len += n;
                if (lease != null && !lease.tryResize(rawCharge + (long) IngestByteBudget.HEAP_FACTOR * len)) {
                    throw new BodyBudgetExceededException("ingest byte budget exhausted while decoding gzip body");
                }
            }
        } catch (BodyTooLargeException | BodyBudgetExceededException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("decode gzip body failed: " + e.getMessage(), e);
        }
        return new DecodedBody(out, len);
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

        @Override
        public int read(byte[] b, int off, int len) {
            return input.read(b, off, len);
        }
    }
}
