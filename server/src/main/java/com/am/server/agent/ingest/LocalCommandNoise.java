package com.am.server.agent.ingest;

/**
 * Claude Code 等客户端把本地斜杠命令（{@code /usage}、{@code /exit} 等）包装成 user 消息上报；
 * 这类内容从未到达模型，应在本层丢弃入库（前缀规则以本类为准，与运维侧按前缀 DELETE 噪声消息的口径一致）。
 *
 * <p>仅命中下列前缀的<b>纯包装文本</b>才算噪声（与 SQL {@code LIKE 'xxx%' } 前缀清理等价）。
 */
public final class LocalCommandNoise {

    private LocalCommandNoise() {}

    /**
     * 是否应在 ingest 时丢弃该条消息。
     *
     * @param role    消息角色（不区分大小写）
     * @param content 消息原文
     * @return 仅 {@code role=user} 且正文以既定本地命令包装前缀开头时为 {@code true}
     */
    public static boolean isNoise(String role, String content) {
        if (role == null || !"user".equalsIgnoreCase(role)) {
            return false;
        }
        if (content == null || content.isEmpty()) {
            return false;
        }
        return content.startsWith("<local-command-")
                || content.startsWith("<command-name>")
                || content.startsWith("<command-message>")
                || content.startsWith("<command-args>")
                || content.startsWith("Unknown command:");
    }
}
