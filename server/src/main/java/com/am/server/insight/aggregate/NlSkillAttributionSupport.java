package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

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
        return reconcile(messageRepository.findByAiSessionIdOrderBySequenceNoAsc(sessionId),
                msg -> messageRepository.save(msg));
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
     * <p>读取走列投影（{@link AiSessionMessageRepository#findNlSkillReconcileRowsFrom}）：判定只需要 role / 序号 /
     * tool_name / slash 三列，以及<b>仅 agent 侧消息</b>的 content_parts_json（无 parts 时才要 content_text）——
     * user 消息的正文（常是粘贴的长日志）不再进内存。改写走 {@code updateSlashHits} 只更新 slash 三列，
     * 不再对整行实体 save（那会把 content_text / content_parts_json 一并重写、ROW binlog 记两份整行）。
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
        List<AiSessionMessage> msgs = new ArrayList<>();
        for (AiSessionMessageRepository.NlSkillReconcileRow row
                : messageRepository.findNlSkillReconcileRowsFrom(sessionId, from)) {
            msgs.add(toTransientMessage(row));
        }
        if (hasTurnStart && (msgs.isEmpty() || !isUserRole(msgs.get(0).getRole()))) {
            // SQL 与 Java 对 user 的判定不一致（排序规则 / 空白字符）或序号并列：起点不可靠，保守退回整段重扫
            return reconcileSession(messageRepository, sessionId);
        }
        return reconcile(msgs, msg -> messageRepository.updateSlashHits(
                msg.getId(), msg.getSlashHitsJson(), msg.getSlashCommandCount(), msg.getSlashSkillCount()));
    }

    /**
     * 投影行 → 仅填充判定所需字段的<b>游离</b>实体（只在内存里喂给 {@link NlSkillExecutionSupport} /
     * {@link SlashHitsJsonSupport}，绝不能 {@code save}——缺失的列会被写成 NULL）。
     */
    private static AiSessionMessage toTransientMessage(AiSessionMessageRepository.NlSkillReconcileRow row) {
        AiSessionMessage m = new AiSessionMessage();
        m.setId(row.getId());
        m.setRole(row.getRole());
        m.setSequenceNo(row.getSequenceNo());
        m.setToolName(row.getToolName());
        m.setSlashHitsJson(row.getSlashHitsJson());
        m.setSlashCommandCount(row.getSlashCommandCount());
        m.setSlashSkillCount(row.getSlashSkillCount());
        m.setContentPartsJson(row.getContentPartsJson());
        m.setContentText(row.getContentText());
        return m;
    }

    /** @param persist 有变更的 user 消息如何落库（整行实体 save，或只回写 slash 三列） */
    private static int reconcile(List<AiSessionMessage> msgs, Consumer<AiSessionMessage> persist) {
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
                persist.accept(msg);
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
