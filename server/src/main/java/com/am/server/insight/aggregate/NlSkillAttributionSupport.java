package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将<strong>已执行</strong>的 NL skill 归因到对应 user 消息的 {@code slash_hits_json}（{@code kind=nl_skill}）。
 *
 * <p>仅 Read {@code SKILL.md} 不算执行，见 {@link NlSkillExecutionSupport}。
 * Cursor 把工具 bubble 存为 role=tool；Claude 等可能在 assistant 的 content_parts 里带 tool_call。
 * gz
 */
public final class NlSkillAttributionSupport {

    private NlSkillAttributionSupport() {}

    /**
     * 按 sequence 重扫整段会话，幂等写入各 user 轮已执行 skill（会清除旧的 {@code nl_skill} 行）。
     *
     * @return 被更新的 user 消息条数
     */
    public static int reconcileSession(
            AiSessionMessageRepository messageRepository, Long sessionId) {
        if (messageRepository == null || sessionId == null) {
            return 0;
        }
        List<AiSessionMessage> msgs = messageRepository.findByAiSessionIdOrderBySequenceNoAsc(sessionId);
        Map<Integer, List<NlSkillInvocationDetector.NlSkillHit>> byUserSeq =
                NlSkillExecutionSupport.attributeExecutedSkills(msgs);
        int updated = 0;
        for (AiSessionMessage msg : msgs) {
            if (msg.getRole() == null || !"user".equalsIgnoreCase(msg.getRole().trim())) {
                continue;
            }
            int seq = msg.getSequenceNo() == null ? 0 : msg.getSequenceNo();
            List<NlSkillInvocationDetector.NlSkillHit> expected =
                    byUserSeq.getOrDefault(seq, List.of());
            if (SlashHitsJsonSupport.replaceNlSkills(msg, expected)) {
                messageRepository.save(msg);
                updated++;
            }
        }
        return updated;
    }

    public static boolean isAgentRolePublic(String role) {
        if (role == null) {
            return false;
        }
        String r = role.trim().toLowerCase(Locale.ROOT);
        return "tool".equals(r) || "assistant".equals(r);
    }
}
