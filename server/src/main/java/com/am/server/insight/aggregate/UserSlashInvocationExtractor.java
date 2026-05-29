package com.am.server.insight.aggregate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从单条 user 消息的 {@code content_text} 中抽取「主动输入的快捷调用」：
 * 规则随 {@code target_type} 不同（与 Cursor / Claude Code / Codex CLI 等产品对齐）。
 *
 * <ul>
 *   <li><b>cursor</b>（及未识别的其它类型）：逐行扫描，在<strong>行首或空白之后</strong>出现的 {@code /…} token
 *       （不限于行首第一条），经 {@link UserSlashInvocationKind} 分为 command / skill / noise。</li>
 *   <li><b>claude</b>（Claude Code）：<strong>不解析、不统计</strong> slash/skill（显式 / 与自动技能均不靠正文启发式）。</li>
 *   <li><b>codex</b>（Codex CLI）：在全文中匹配 {@code $…} 或全角 {@code ＄…} 技能 token（不限于行首；忽略 {@code /}；command 恒为 0）。</li>
 * </ul>
 *
 * <p>入库字段名仍为 {@code slash_* }（历史原因），计数与明细 JSON 语义按上表解释。
 *
 * <p>自然语言触发且<strong>已执行</strong>的 Skill（Read {@code SKILL.md} 后出现非 Read 工具）由
 * {@link NlSkillExecutionSupport} / {@link NlSkillAttributionSupport} 写入对应 user 消息的
 * {@code slash_hits_json}（{@code kind=nl_skill}）。
 * gz
 */
public final class UserSlashInvocationExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Codex CLI 正文：任意位置的技能触发词，形如 {@code $review}、{@code $my-skill}；
     * ASCII {@code $} 与全角 {@code ＄} 等价；后跟须字母起头，避免误匹配 {@code $100}。
     */
    private static final Pattern CODEX_DOLLAR_SKILL =
            Pattern.compile("(?:\\$|\uFF04)[a-zA-Z][a-zA-Z0-9_-]*");

    private UserSlashInvocationExtractor() {}

    /**
     * @param slashHitsJson {@code null} 表示无命中；否则为 JSON 数组 {@code [{token,kind},...]}。
     */
    public record Annotation(int slashCommandCount, int slashSkillCount, String slashHitsJson) {
        public static Annotation empty() {
            return new Annotation(0, 0, null);
        }
    }

    /**
     * 与历史行为一致：默认按 Cursor 规则解析（显式请传 {@link #annotateUserContent(String, String)}）。
     */
    public static Annotation annotateUserContent(String contentText) {
        return annotateUserContent(contentText, null);
    }

    /**
     * @param targetType {@link com.am.server.domain.ai.AiSession#getTargetType()}，如 cursor / claude / codex
     */
    public static Annotation annotateUserContent(String contentText, String targetType) {
        if (contentText == null || contentText.isBlank()) {
            return Annotation.empty();
        }
        String tt = normalizeTargetType(targetType);
        if ("codex".equals(tt)) {
            return annotateCodexDollarSkills(contentText);
        }
        if ("claude".equals(tt)) {
            return Annotation.empty();
        }
        return annotateCursorStyleSlashLines(contentText);
    }

    private static String normalizeTargetType(String targetType) {
        if (targetType == null || targetType.isBlank()) {
            return "";
        }
        return targetType.trim().toLowerCase(Locale.ROOT);
    }

    /** Cursor 及默认：行内任意处「空白或行首后的 {@code /…}」token + command/skill 启发式（避免匹配 path/a/b 这类斜杠） */
    private static Annotation annotateCursorStyleSlashLines(String contentText) {
        if (contentText == null || contentText.isBlank()) {
            return Annotation.empty();
        }
        List<Map<String, String>> hits = new ArrayList<>();
        int cmd = 0;
        int sk = 0;
        for (String rawLine : contentText.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int i = 0;
            while (i < line.length()) {
                if (line.charAt(i) != '/' || (i > 0 && !Character.isWhitespace(line.charAt(i - 1)))) {
                    i++;
                    continue;
                }
                int end = i + 1;
                while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
                String token = line.substring(i, end);
                if (!UserSlashInvocationKind.isPlausibleCursorSlashToken(token)) {
                    i++;
                    continue;
                }
                String tl = token.toLowerCase(Locale.ROOT);
                Map<String, String> row = new LinkedHashMap<>();
                row.put("token", tl);
                if (UserSlashInvocationKind.isNoiseSlashKey(tl)) {
                    row.put("kind", "noise");
                    hits.add(row);
                    i = end;
                    continue;
                }
                if (UserSlashInvocationKind.isSkillSlashKey(tl)) {
                    row.put("kind", "skill");
                    sk++;
                } else {
                    row.put("kind", "command");
                    cmd++;
                }
                hits.add(row);
                i = end;
            }
        }
        return finalizeAnnotation(cmd, sk, hits);
    }

    /** Codex CLI：行内任意位置匹配 {@code $技能名}，忽略 {@code /} */
    private static Annotation annotateCodexDollarSkills(String contentText) {
        List<Map<String, String>> hits = new ArrayList<>();
        int sk = 0;
        for (String rawLine : contentText.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = CODEX_DOLLAR_SKILL.matcher(line);
            while (matcher.find()) {
                String tl = matcher.group().toLowerCase(Locale.ROOT);
                if (tl.length() < 2) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("token", tl);
                row.put("kind", "skill");
                sk++;
                hits.add(row);
            }
        }
        return finalizeAnnotation(0, sk, hits);
    }

    private static Annotation finalizeAnnotation(int cmd, int sk, List<Map<String, String>> hits) {
        if (hits.isEmpty()) {
            return Annotation.empty();
        }
        try {
            return new Annotation(cmd, sk, MAPPER.writeValueAsString(hits));
        } catch (JsonProcessingException e) {
            return new Annotation(cmd, sk, null);
        }
    }
}
