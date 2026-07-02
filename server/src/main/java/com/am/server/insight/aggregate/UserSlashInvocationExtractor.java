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
 *   <li><b>cursor</b>（及未识别的其它类型）：只取整条消息 trim 后的<strong>首个空白分隔 token</strong>，
 *       为 {@code /…} 且过 {@link UserSlashInvocationKind} 形态校验才算（命令只在输入框开头才会执行，
 *       行中 {@code /xxx} 视为叙述引用，不计）。</li>
 *   <li><b>claude</b>（Claude Code）：<strong>不解析、不统计</strong> slash/skill（显式 / 与自动技能均不靠正文启发式）。</li>
 *   <li><b>codex</b>（Codex CLI）：行内匹配 {@code $技能名} 或全角 {@code ＄…}（技能引用可出现在行内），
 *       但仅认<strong>全小写 kebab</strong> 命名、{@code $} 前须行首或空白、跳过 ``` 围栏与行内反引号——
 *       排除 {@code $HOME}、{@code "$var"}、{@code $foo_bar} 这类 shell/模板变量；忽略 {@code /}；command 恒为 0。</li>
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
     * Codex CLI 正文：技能触发词，形如 {@code $review}、{@code $skill-creator}；
     * ASCII {@code $} 与全角 {@code ＄} 等价。仅全小写 kebab 且名字 ≥2 字符，
     * 配合 {@link #codexSkillTokensIn} 的前后边界判断排除 shell/模板变量。
     */
    private static final Pattern CODEX_DOLLAR_SKILL =
            Pattern.compile("(?:\\$|\uFF04)[a-z][a-z0-9-]+");

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
        return annotateFirstTokenSlash(contentText);
    }

    private static String normalizeTargetType(String targetType) {
        if (targetType == null || targetType.isBlank()) {
            return "";
        }
        return targetType.trim().toLowerCase(Locale.ROOT);
    }

    /** Cursor 及默认：只认整条消息 trim 后的首个空白分隔 token（命令只在输入框开头执行，行中 /xxx 是叙述） */
    private static Annotation annotateFirstTokenSlash(String contentText) {
        String trimmed = contentText.trim();
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        String token = trimmed.substring(0, end);
        if (token.charAt(0) != '/' || !UserSlashInvocationKind.isPlausibleCursorSlashToken(token)) {
            return Annotation.empty();
        }
        String tl = token.toLowerCase(Locale.ROOT);
        Map<String, String> row = new LinkedHashMap<>();
        row.put("token", tl);
        int cmd = 0;
        int sk = 0;
        if (UserSlashInvocationKind.isNoiseSlashKey(tl)) {
            row.put("kind", "noise");
        } else if (UserSlashInvocationKind.isSkillSlashKey(tl)) {
            row.put("kind", "skill");
            sk = 1;
        } else {
            row.put("kind", "command");
            cmd = 1;
        }
        return finalizeAnnotation(cmd, sk, List.of(row));
    }

    /** Codex CLI：行内 {@code $技能名}；跳过 ``` 围栏与行内反引号 span；{@code $} 前须行首或空白 */
    private static Annotation annotateCodexDollarSkills(String contentText) {
        List<Map<String, String>> hits = new ArrayList<>();
        int sk = 0;
        boolean inFence = false;
        for (String rawLine : contentText.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence || line.isEmpty()) {
                continue;
            }
            String[] segments = line.split("`", -1);
            for (int si = 0; si < segments.length; si += 2) {
                for (String token : codexSkillTokensIn(segments[si], si == 0)) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("token", token);
                    row.put("kind", "skill");
                    sk++;
                    hits.add(row);
                }
            }
        }
        return finalizeAnnotation(0, sk, hits);
    }

    /** 收集一段无行内代码文本里的 $技能 token；前界=段首（仅整行首算）或空白，后界=段尾或非 token 字符 */
    private static List<String> codexSkillTokensIn(String segment, boolean startIsLineStart) {
        List<String> out = new ArrayList<>();
        Matcher matcher = CODEX_DOLLAR_SKILL.matcher(segment);
        while (matcher.find()) {
            int start = matcher.start();
            boolean leadingOk = start == 0
                    ? startIsLineStart
                    : Character.isWhitespace(segment.charAt(start - 1));
            if (!leadingOk) {
                continue;
            }
            int end = matcher.end();
            if (end < segment.length() && isTokenChar(segment.charAt(end))) {
                continue;
            }
            out.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean isTokenChar(char c) {
        return c == '-' || c == '_'
                || (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z');
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
