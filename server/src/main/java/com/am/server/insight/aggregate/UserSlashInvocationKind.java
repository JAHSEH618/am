package com.am.server.insight.aggregate;

import java.util.Locale;
import java.util.Set;

/**
 * 用户输入框里以 {@code /}（及 Codex 的 {@code $}，见 {@link UserSlashInvocationExtractor}）
 * 发起的<strong>主动调用</strong>在 <em>Cursor</em> 场景下的 token 级启发式分类。
 *
 * <p>Claude Code 路径下不调用本套启发式（见 {@link UserSlashInvocationExtractor}）。
 *
 * <p>与 agent 侧 {@code TOOL_CALL}、模型工具调用<strong>无关</strong>。具体「从全文抽命中」见
 * {@link UserSlashInvocationExtractor}；本类只负责 token 级启发式分类。
 *
 * <p><b>技能</b>：小写 token 含 {@code /skills/} 或含 {@code skill}；
 * <b>命令</b>：其余非噪声斜杠。<b>噪声</b>：{@code /usage}、{@code /exit} 不计入 command/skill 次数，但可落入明细 JSON。
 * gz
 */
public final class UserSlashInvocationKind {

    private static final Set<String> NOISE_SLASH_LOWER = Set.of("/usage", "/exit");

    private UserSlashInvocationKind() {}

    /**
     * Cursor 输入框斜杠是否像真实快捷调用（{@code /fix}、{@code /run-skill}、技能路径等），
     * 排除裸 {@code /}、{@code /.}、{@code /(…} 等正文或路径碎片。
     */
    public static boolean isPlausibleCursorSlashToken(String token) {
        if (token == null || token.length() < 2 || !token.startsWith("/")) {
            return false;
        }
        String tl = token.toLowerCase(Locale.ROOT);
        if (isNoiseSlashKey(tl)) {
            return true;
        }
        if (isSkillSlashKey(tl)) {
            return Character.isLetterOrDigit(token.charAt(1));
        }
        if (!Character.isLetter(token.charAt(1))) {
            return false;
        }
        for (int i = 2; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** 是否属于应排除的本地噪声斜杠（小写首 token，含前导 {@code /}）。 */
    public static boolean isNoiseSlashKey(String slashKeyLower) {
        if (slashKeyLower == null) {
            return true;
        }
        return NOISE_SLASH_LOWER.contains(slashKeyLower.trim());
    }

    public static boolean isSkillSlashKey(String slashKeyLower) {
        if (slashKeyLower == null || slashKeyLower.isBlank()) {
            return false;
        }
        String k = slashKeyLower.toLowerCase(Locale.ROOT);
        if (k.contains("/skills/")) {
            return true;
        }
        return k.contains("skill");
    }

    public static String kindOfSlashKey(String slashKeyLower) {
        return isSkillSlashKey(slashKeyLower) ? "skill" : "command";
    }
}
