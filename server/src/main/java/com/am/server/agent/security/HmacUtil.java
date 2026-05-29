package com.am.server.agent.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * HMAC-SHA256 工具
 * 签名算法：sign = HMAC-SHA256(agent_secret, body || ts || nonce) → 小写 hex
 * 与 Go Agent 端 internal/security 保持一致
 * gz
 */
public final class HmacUtil {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HmacUtil() {
    }

    public static String sign(String secret, byte[] body, String timestamp, String nonce) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            mac.update(body);
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update(nonce.getBytes(StandardCharsets.UTF_8));
            return toHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("hmac signing failed", e);
        }
    }

    /** 常量时间字符串比较，避免计时攻击 */
    public static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
