package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;
import org.springframework.data.domain.PageRequest;

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
        return reconcile(messageRepository, messageRepository.findByAiSessionIdOrderBySequenceNoAsc(sessionId));
    }

    /**
     * ingest 增量路径：只重算本拍新消息（序号 ≥ {@code firstNewSeq}）落入的那一轮及其后各轮。
     *
     * <p>{@link NlSkillExecutionSupport#attributeExecutedSkills} 以 user 消息切轮、各轮互不影响（遇 user 即清空
     * pending），而 ingest 新消息的 sequence_no 一律追加在末尾（max+1 起）——所以旧轮里唯一可能变化的，是
     * {@code firstNewSeq} 之前最后一条 user 消息开启的那一轮；更早各轮输入一条没变，结果也不会变。
     * 这里从那条 user 消息起按 idx_session_seq 区间取数，不再每拍把整段会话（含 MEDIUMTEXT）读进内存。
     * 之前没有 user 消息时，{@code firstNewSeq} 之前的消息不属于任何轮（整段重扫也会跳过），直接从它起。
     *
     * @return 被更新的 user 消息条数
     */
    public static int reconcileSessionFrom(
            AiSessionMessageRepository messageRepository, Long sessionId, int firstNewSeq) {
        if (messageRepository == null || sessionId == null) {
            return 0;
        }
        List<Integer> turnStart =
                messageRepository.findUserSequenceNosBefore(sessionId, firstNewSeq, PageRequest.of(0, 1));
        boolean hasTurnStart = !turnStart.isEmpty() && turnStart.get(0) != null;
        int from = hasTurnStart ? turnStart.get(0) : firstNewSeq;
        List<AiSessionMessage> msgs =
                messageRepository.findByAiSessionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(sessionId, from);
        if (hasTurnStart && (msgs.isEmpty() || !isUserRole(msgs.get(0).getRole()))) {
            // SQL 与 Java 对 user 的判定不一致（排序规则 / 空白字符）或序号并列：起点不可靠，保守退回整段重扫
            return reconcileSession(messageRepository, sessionId);
        }
        return reconcile(messageRepository, msgs);
    }

    private static int reconcile(AiSessionMessageRepository messageRepository, List<AiSessionMessage> msgs) {
        Map<Integer, List<NlSkillInvocationDetector.NlSkillHit>> byUserSeq =
                NlSkillExecutionSupport.attributeExecutedSkills(msgs);
        int updated = 0;
        for (AiSessionMessage msg : msgs) {
            if (!isUserRole(msg.getRole())) {
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

    private static boolean isUserRole(String role) {
        return role != null && "user".equalsIgnoreCase(role.trim());
    }

    public static boolean isAgentRolePublic(String role) {
        if (role == null) {
            return false;
        }
        String r = role.trim().toLowerCase(Locale.ROOT);
        return "tool".equals(r) || "assistant".equals(r);
    }
}
