package com.am.server.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * AI trailer 匹配规则库（《管理后台-产出归因与能力使用分析 v1.0》§2.2 B 档）：
 * {@code git_commit.message_body} 命中任一规则 → tier=B「确定 AI 产出」，误报≈0。
 *
 * <p>内置常量规则穷举已知 agent 签名；sys_config {@code attribution.trailer_rules}（JSON 数组
 * {@code [{kind, pattern, target_type}]}）在其上追加，新工具签名无需发版。所有 pattern 大小写不敏感、
 * 按 {@link Pattern#find} 子串语义匹配。
 *
 * <p>已知上限：message_body 入库截断 8192 字符（GitCommitIngestService），超长 commit message
 * 的尾部 trailer 可能被截掉——B 档漏报向 A 档兜底，口径释义需注明。
 * gz
 */
public final class AiTrailerRules {

    private static final Logger log = LoggerFactory.getLogger(AiTrailerRules.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一条匹配规则：kind 落 trailer_kind 列；targetType 为归因工具（可为非监控 provider，如 copilot）。 */
    public record TrailerRule(String kind, Pattern pattern, String targetType) {}

    /**
     * 内置规则。顺序即优先级（一条命中即停）：同一 message 可能同时含
     * "Generated with [Claude Code]" 与 "Co-Authored-By: Claude"，kind 归并到更具体的在前。
     */
    private static final List<TrailerRule> BUILT_IN = List.of(
            rule("claude_code", "generated with \\[?claude code\\]?", "claude"),
            rule("claude_code", "claude-session:\\s*\\S+", "claude"),
            rule("claude_coauthor", "co-authored-by:\\s*claude", "claude"),
            rule("codex", "co-authored-by:\\s*(openai[- ])?codex", "codex"),
            rule("codex", "generated with (openai )?codex", "codex"),
            rule("cursor", "co-authored-by:\\s*cursor", "cursor"),
            rule("cursor", "generated with cursor", "cursor"),
            rule("copilot", "co-authored-by:\\s*(github[- ])?copilot", "copilot"),
            rule("aider", "co-authored-by:\\s*aider", "aider"),
            rule("gemini", "co-authored-by:\\s*gemini", "gemini"));

    private AiTrailerRules() {}

    private static TrailerRule rule(String kind, String regex, String targetType) {
        return new TrailerRule(kind, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), targetType);
    }

    /** 内置规则 + sys_config 追加规则（追加排在内置之后；非法条目跳过并告警，不影响其余）。 */
    public static List<TrailerRule> merged(String sysConfigJson) {
        if (sysConfigJson == null || sysConfigJson.isBlank() || "[]".equals(sysConfigJson.trim())) {
            return BUILT_IN;
        }
        List<TrailerRule> out = new ArrayList<>(BUILT_IN);
        try {
            JsonNode arr = MAPPER.readTree(sysConfigJson);
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String kind = n.path("kind").asText("");
                    String pattern = n.path("pattern").asText("");
                    String targetType = n.path("target_type").asText("");
                    if (kind.isBlank() || pattern.isBlank()) {
                        continue;
                    }
                    try {
                        out.add(new TrailerRule(kind,
                                Pattern.compile(pattern, Pattern.CASE_INSENSITIVE),
                                targetType.isBlank() ? null : targetType));
                    } catch (Exception badPattern) {
                        log.warn("attribution.trailer_rules bad pattern skipped: kind={} pattern={}", kind, pattern);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("attribution.trailer_rules parse failed, using built-in only: {}", e.toString());
        }
        return out;
    }

    /** message_body 命中的第一条规则；null / 空 body 返回 empty。 */
    public static Optional<TrailerRule> match(String messageBody, List<TrailerRule> rules) {
        if (messageBody == null || messageBody.isBlank()) {
            return Optional.empty();
        }
        for (TrailerRule r : rules) {
            if (r.pattern().matcher(messageBody).find()) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
