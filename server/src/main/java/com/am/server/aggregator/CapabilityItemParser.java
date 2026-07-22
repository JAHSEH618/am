package com.am.server.aggregator;

import java.util.Locale;
import java.util.Optional;

/**
 * 能力维度名解析器（《管理后台-产出归因与能力使用分析 v1.0》§3.2）：
 * 从原始 tool_name / slash token 解析出 capability_daily 的一级/二级维度。
 *
 * <ul>
 *   <li><b>MCP 工具名</b>：{@code mcp__<server>__<tool>}。大小写不敏感——agent 端
 *       {@code NormalizeToolName} 会把首字母大写为 {@code Mcp__...}，统一 lowercase 后合并；
 *       server 取第一段，tool 取其余（tool 自身可含 {@code __}）。缺段 / 空段 / 含空白视为非法。</li>
 *   <li><b>技能 token 归一</b>：剥前导 {@code /}（cursor 首 token）、{@code $}/{@code ＄}（codex）、
 *       {@code skills/} 前缀（nl_skill token 形如 {@code /skills/<name>}），得到裸技能名。</li>
 *   <li><b>插件命名空间</b>：归一后技能名形如 {@code <namespace>:<skill>}
 *       （如 {@code superpowers:brainstorming}）按首个冒号拆分；namespace 须以字母开头且仅含
 *       {@code [a-z0-9_-]}（排除 {@code 12:30} 这类时间碎片），skill 段须以字母/数字开头且不含
 *       {@code /}（排除 {@code http://x} 这类 URL）。</li>
 * </ul>
 * gz
 */
public final class CapabilityItemParser {

    private static final String MCP_PREFIX = "mcp__";
    private static final String MCP_SEPARATOR = "__";

    private CapabilityItemParser() {}

    /** MCP 工具引用：server / tool 均已 lowercase。 */
    public record McpTool(String server, String tool) {}

    /** 插件命名空间技能引用：namespace / skill 均已 lowercase。 */
    public record NamespaceSkill(String namespace, String skill) {}

    /**
     * 解析 {@code mcp__<server>__<tool>} 形态的工具名；非 MCP 形态或非法（缺段/空段/含空白）返回 empty。
     */
    public static Optional<McpTool> parseMcpToolName(String toolName) {
        if (toolName == null) {
            return Optional.empty();
        }
        String t = toolName.trim().toLowerCase(Locale.ROOT);
        if (!t.startsWith(MCP_PREFIX)) {
            return Optional.empty();
        }
        String rest = t.substring(MCP_PREFIX.length());
        int sep = rest.indexOf(MCP_SEPARATOR);
        if (sep <= 0) {
            return Optional.empty();
        }
        String server = rest.substring(0, sep);
        String tool = rest.substring(sep + MCP_SEPARATOR.length());
        if (tool.isBlank() || hasWhitespace(server) || hasWhitespace(tool)) {
            return Optional.empty();
        }
        return Optional.of(new McpTool(server, tool));
    }

    /**
     * slash token → 裸技能名：lowercase + 剥前导 {@code /} / {@code $} / {@code ＄} 与
     * {@code skills/} 前缀；剥完为空返回 {@code null}。
     */
    public static String normalizeSkillItem(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (!t.isEmpty() && (t.charAt(0) == '/' || t.charAt(0) == '$' || t.charAt(0) == '＄')) {
            t = t.substring(1);
        }
        if (t.startsWith("skills/")) {
            t = t.substring("skills/".length());
        }
        return t.isBlank() ? null : t;
    }

    /**
     * 归一后技能名中的插件命名空间：{@code <namespace>:<skill>} 按首个冒号拆分（skill 段可再含冒号）。
     * 不满足命名约束（见类注释）返回 empty。
     */
    public static Optional<NamespaceSkill> parseNamespaceSkill(String normalizedItem) {
        if (normalizedItem == null) {
            return Optional.empty();
        }
        int colon = normalizedItem.indexOf(':');
        if (colon <= 0 || colon == normalizedItem.length() - 1) {
            return Optional.empty();
        }
        String namespace = normalizedItem.substring(0, colon);
        String skill = normalizedItem.substring(colon + 1);
        if (!isValidNamespace(namespace) || !isValidSkillName(skill)) {
            return Optional.empty();
        }
        return Optional.of(new NamespaceSkill(namespace, skill));
    }

    private static boolean isValidNamespace(String s) {
        if (s.isEmpty() || !isAsciiLetter(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(isAsciiLetter(c) || isAsciiDigit(c) || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidSkillName(String s) {
        if (s.isEmpty() || !(isAsciiLetter(s.charAt(0)) || isAsciiDigit(s.charAt(0)))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean hasWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
