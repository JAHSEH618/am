package com.am.server.insight.aggregate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 *   <li><b>codex</b>（Codex CLI）：只认技能<strong>真正执行</strong>时 CLI 以 user 角色注入的
 *       {@code <skill><name>…</name>…} 信封，token 记为 {@code $技能名}；正文里的 {@code $xxx}
 *       <strong>不再启发式解析</strong>——正文提及既混入 {@code $ref}/{@code $path} 这类 JSON/shell
 *       变量，又把未触发的技能引用记成调用（本机实测 108 次行内 {@code $impeccable} 提及仅 16 次
 *       实际执行，而信封与执行一一对应）；command 恒为 0。</li>
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
     * Codex CLI 技能执行信封头：{@code <skill>}（后可带换行）紧跟 {@code <name>技能名</name>}。
     * 锚定信封起始，避免命中 SKILL.md 正文里出现的 {@code <name>} 标签。
     */
    private static final Pattern CODEX_SKILL_ENVELOPE_NAME =
            Pattern.compile("\\A<skill>\\s*<name>([^<>]{1,120})</name>");

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
            return annotateCodexSkillEnvelope(contentText);
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

    /** Codex CLI：只认 {@code <skill><name>…</name>} 执行信封，整条消息记 1 次 skill；其余正文一律不计 */
    private static Annotation annotateCodexSkillEnvelope(String contentText) {
        String trimmed = contentText.trim();
        if (!trimmed.startsWith("<skill>")) {
            return Annotation.empty();
        }
        Matcher matcher = CODEX_SKILL_ENVELOPE_NAME.matcher(trimmed);
        if (!matcher.find()) {
            return Annotation.empty();
        }
        String name = matcher.group(1).trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || hasWhitespace(name)) {
            return Annotation.empty();
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("token", "$" + name);
        row.put("kind", "skill");
        return finalizeAnnotation(0, 1, List.of(row));
    }

    private static boolean hasWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
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
