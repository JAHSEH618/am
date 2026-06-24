package com.am.server.insight.audit;

import java.util.regex.Pattern;

/**
 * LLM 外发脱敏：拼 Judge prompt 前对常见密钥 / 令牌 / 私钥打码，
 * 降低把会话原文里的密钥发给第三方模型网关的泄露风险。
 *
 * <p>保守模式——只命中明确的密钥格式，尽量不误伤正常源码（密钥不是能力评估信号，打码不损审计语义）。
 * gz
 */
public final class SecretRedactor {

    private SecretRedactor() {
    }

    private static final Pattern[] PATTERNS = {
            // AWS Access Key Id
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            // OpenAI 风格 sk-...
            Pattern.compile("sk-[A-Za-z0-9]{20,}"),
            // GitHub token (ghp_/gho_/ghu_/ghs_/ghr_ / github_pat_)
            Pattern.compile("(gh[pousr]|github_pat)_[A-Za-z0-9_]{20,}"),
            // Bearer <token>
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{20,}"),
            // PEM 私钥块
            Pattern.compile("-----BEGIN[^-]*PRIVATE KEY-----[\\s\\S]*?-----END[^-]*PRIVATE KEY-----"),
            // key=value / key: value 形态的敏感键
            Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|token)\\s*[:=]\\s*[\"']?[A-Za-z0-9._\\-]{12,}")
    };

    /** 对文本打码；null/空原样返回。 */
    public static String redact(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = s;
        for (Pattern p : PATTERNS) {
            out = p.matcher(out).replaceAll("[REDACTED]");
        }
        return out;
    }
}
