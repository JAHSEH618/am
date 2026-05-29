package com.am.server.insight.aggregate;

import com.am.server.agent.api.dto.ContentPartDto;
import com.am.server.domain.ai.AiSessionMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 agent 消息中识别 Read 类工具打开的 {@code .../skills/<name>/SKILL.md} 路径。
 * 是否记为已执行 skill 见 {@link NlSkillExecutionSupport}（Read 后须有落地类工具，非 Grep 等探索）。
 *
 * <p>与 {@link UserSlashInvocationExtractor} 互补：
 * <ul>
 *   <li>Cursor / Claude / OpenHarness / OpenClaw / Hermes：Read + SKILL.md 路径（见各 Provider {@code content_parts}）</li>
 *   <li>Codex 显式 {@code $skill}：仍由 {@link UserSlashInvocationExtractor} 在 user 正文统计（{@code kind=skill}）</li>
 *   <li>Codex NL：若后续 Read SKILL.md，本类从 {@code role=tool} + {@code tool_name=Read} + 参数 JSON 识别</li>
 * </ul>
 * gz
 */
public final class NlSkillInvocationDetector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 常见 skill 根目录（顺序无关，独立匹配）：
     * {@code .../skills/<name>/SKILL.md}、{@code .../skills-cursor/<name>/SKILL.md}、
     * {@code .../.agents/skills/<name>/SKILL.md}。
     */
    private static final Pattern SKILL_MD_UNDER_SKILLS =
            Pattern.compile("(?:^|[/\\\\])skills[/\\\\]([a-zA-Z0-9][a-zA-Z0-9_-]*)[/\\\\]SKILL\\.md",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILL_MD_UNDER_SKILLS_CURSOR =
            Pattern.compile("(?:^|[/\\\\])skills-cursor[/\\\\]([a-zA-Z0-9][a-zA-Z0-9_-]*)[/\\\\]SKILL\\.md",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILL_MD_UNDER_AGENTS_SKILLS =
            Pattern.compile("(?:^|[/\\\\])\\.agents[/\\\\]skills[/\\\\]([a-zA-Z0-9][a-zA-Z0-9_-]*)[/\\\\]SKILL\\.md",
                    Pattern.CASE_INSENSITIVE);

    /** 与 agent {@code common.NormalizeToolName} 的 Read 族对齐 */
    private static final Set<String> READ_TOOL_NAMES = Set.of(
            "read", "read_file", "read_file_v2", "view", "view_file");

    private NlSkillInvocationDetector() {}

    /**
     * @param token 归一化展示 token，形如 {@code /skills/<name>}（不绑定具体 skill 名）
     */
    public record NlSkillHit(String token) {}

    /** 单条消息入口：优先 content_parts 中 Read 的 tool_call 参数；无 parts 时再回退扁平 content_text */
    public static List<NlSkillHit> detectFromMessage(AiSessionMessage msg) {
        if (msg == null) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        boolean hasParts = msg.getContentPartsJson() != null && !msg.getContentPartsJson().isBlank();
        if (hasParts) {
            collectFromContentPartsJson(msg.getContentPartsJson(), tokens);
        } else if (isReadTool(msg.getToolName())) {
            collectPathFromReadToolArguments(msg.getContentText(), tokens);
        } else if (looksLikeToolInvocationText(msg.getContentText(), msg.getToolName())) {
            collectPathFromReadToolArguments(msg.getContentText(), tokens);
        }
        return toHits(tokens);
    }

    public static List<NlSkillHit> detectFromContentPartsJson(String contentPartsJson) {
        Set<String> tokens = new LinkedHashSet<>();
        collectFromContentPartsJson(contentPartsJson, tokens);
        return toHits(tokens);
    }

    public static List<NlSkillHit> detectFromContentParts(List<ContentPartDto> parts) {
        Set<String> tokens = new LinkedHashSet<>();
        if (parts != null) {
            collectFromContentParts(parts, tokens);
        }
        return toHits(tokens);
    }

    /** 仅在有 tool 调用上下文时使用（避免 tool_result 正文误报） */
    public static List<NlSkillHit> detectFromContentText(String contentText) {
        Set<String> tokens = new LinkedHashSet<>();
        if (looksLikeToolInvocationText(contentText, null)) {
            collectPathFromReadToolArguments(contentText, tokens);
        }
        return toHits(tokens);
    }

    private static void collectFromContentPartsJson(String contentPartsJson, Set<String> tokens) {
        if (contentPartsJson == null || contentPartsJson.isBlank()) {
            return;
        }
        try {
            List<ContentPartDto> parts = MAPPER.readValue(contentPartsJson, new TypeReference<>() {});
            collectFromContentParts(parts, tokens);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static void collectFromContentParts(List<ContentPartDto> parts, Set<String> tokens) {
        for (ContentPartDto p : parts) {
            if (p == null || p.getType() == null) {
                continue;
            }
            if (!"tool_call".equals(p.getType()) || !isReadTool(p.getToolName())) {
                continue;
            }
            collectPathFromReadToolArguments(p.getArgumentsJson(), tokens);
        }
    }

    /**
     * 只从 Read 工具调用的 JSON 参数里取 path；不对整段正文做正则扫描（避免 SKILL.md 内交叉引用误报）。
     */
    private static void collectPathFromReadToolArguments(String raw, Set<String> tokens) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String path = extractPathFromFlattenedOrJson(raw);
        if (path != null) {
            collectSkillNamesFromPath(path, tokens);
        }
    }

    private static boolean looksLikeToolInvocationText(String contentText, String toolName) {
        if (contentText == null || contentText.isBlank()) {
            return false;
        }
        if (isReadTool(toolName)) {
            return true;
        }
        String t = contentText.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            return true;
        }
        return t.contains("[tool_call");
    }

    static boolean isReadTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        return READ_TOOL_NAMES.contains(toolName.trim().toLowerCase(Locale.ROOT));
    }

    private static String extractPathFromArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(argumentsJson);
            return pathFieldFromJson(root);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 扁平化 content_text 中可能含 {@code [tool_call Read]} 头，从首个 {@code {} } 起解析参数 */
    private static String extractPathFromFlattenedOrJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String path = extractPathFromArguments(raw);
        if (path != null) {
            return path;
        }
        int brace = raw.indexOf('{');
        if (brace >= 0) {
            return extractPathFromArguments(raw.substring(brace));
        }
        return null;
    }

    private static String pathFieldFromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        for (String key : List.of(
                "path", "target_file", "file", "file_path", "targetFile", "relativeWorkspacePath")) {
            JsonNode n = root.get(key);
            if (n != null && n.isTextual()) {
                String v = n.asText().trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return null;
    }

    static void collectSkillNamesFromPath(String pathOrText, Set<String> tokens) {
        if (pathOrText == null || pathOrText.isBlank()) {
            return;
        }
        String norm = pathOrText.replace('\\', '/');
        for (Pattern p : List.of(SKILL_MD_UNDER_SKILLS, SKILL_MD_UNDER_SKILLS_CURSOR, SKILL_MD_UNDER_AGENTS_SKILLS)) {
            Matcher m = p.matcher(norm);
            while (m.find()) {
                addSkillToken(m.group(1), tokens);
            }
        }
    }

    static String skillNameFromPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Set<String> tokens = new LinkedHashSet<>();
        collectSkillNamesFromPath(path, tokens);
        if (tokens.isEmpty()) {
            return null;
        }
        String token = tokens.iterator().next();
        return token.substring("/skills/".length());
    }

    private static void addSkillToken(String name, Set<String> tokens) {
        if (name == null || name.isBlank()) {
            return;
        }
        tokens.add("/skills/" + name.toLowerCase(Locale.ROOT));
    }

    private static List<NlSkillHit> toHits(Set<String> tokens) {
        List<NlSkillHit> out = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            out.add(new NlSkillHit(t));
        }
        return out;
    }
}
