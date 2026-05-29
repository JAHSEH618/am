package com.am.server.insight.aggregate;

import com.am.server.agent.api.dto.ContentPartDto;
import com.am.server.domain.ai.AiSessionMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 判断自然语言触发的 Skill 是否<strong>已执行</strong>（而非仅 Read {@code SKILL.md}）。
 *
 * <p>启发式：在同一轮 user 消息之后、下一条 user 之前，若 agent 先 Read 某 skill 的 {@code SKILL.md}，
 * 再出现至少一次<strong>落地类</strong>工具调用（Edit / Write / Bash / Agent 等），则把该轮<strong>最后一次</strong>
 * skill Read 记为已执行。仅 Grep / Glob / WebSearch 等探索类工具不算执行。
 * 连续 Read 多个 skill 而无落地工具 → 视为浏览/发现，不计入。
 *
 * <p>显式 {@code /技能}、Codex {@code $技能} 仍由 {@link UserSlashInvocationExtractor} 统计，不经本类。
 * gz
 */
public final class NlSkillExecutionSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 视为 skill「已落地执行」的工具（对齐 Cursor {@code normalizeToolName} 常见写操作）。
     * Grep / Glob / List / Web* 等探索类不计入，避免「Read 多个 SKILL 后搜代码」误判为执行 skill。
     */
    private static final Set<String> SKILL_EXECUTION_TOOL_NAMES = Set.of(
            "bash", "shell", "edit", "write", "apply_patch", "strreplace", "delete",
            "agent", "task", "subagent", "notebookedit", "mcp");

    private NlSkillExecutionSupport() {}

    /**
     * @param ordered 会话内按 {@code sequence_no} 升序的消息
     * @return user 消息的 {@code sequence_no} → 该轮已执行 skill 列表（token 去重、保序）
     */
    public static Map<Integer, List<NlSkillInvocationDetector.NlSkillHit>> attributeExecutedSkills(
            List<AiSessionMessage> ordered) {
        if (ordered == null || ordered.isEmpty()) {
            return Map.of();
        }
        Map<Integer, LinkedHashSet<String>> tokensByUserSeq = new LinkedHashMap<>();
        Integer currentUserSeq = null;
        List<NlSkillInvocationDetector.NlSkillHit> pending = new ArrayList<>();

        for (AiSessionMessage msg : ordered) {
            if (msg == null) {
                continue;
            }
            String role = msg.getRole();
            if (role != null && "user".equalsIgnoreCase(role.trim())) {
                currentUserSeq = msg.getSequenceNo();
                pending.clear();
                continue;
            }
            if (currentUserSeq == null || !NlSkillAttributionSupport.isAgentRolePublic(role)) {
                continue;
            }
            List<NlSkillInvocationDetector.NlSkillHit> reads =
                    NlSkillInvocationDetector.detectFromMessage(msg);
            if (!reads.isEmpty()) {
                for (NlSkillInvocationDetector.NlSkillHit hit : reads) {
                    if (hit == null || hit.token() == null || hit.token().isBlank()) {
                        continue;
                    }
                    boolean dup = false;
                    for (NlSkillInvocationDetector.NlSkillHit p : pending) {
                        if (hit.token().equalsIgnoreCase(p.token())) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        pending.add(hit);
                    }
                }
            }
            if (hasSkillExecutionSignal(msg) && !pending.isEmpty()) {
                NlSkillInvocationDetector.NlSkillHit executed = pending.get(pending.size() - 1);
                tokensByUserSeq
                        .computeIfAbsent(currentUserSeq, k -> new LinkedHashSet<>())
                        .add(executed.token().toLowerCase(Locale.ROOT));
                pending.clear();
            }
        }

        Map<Integer, List<NlSkillInvocationDetector.NlSkillHit>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, LinkedHashSet<String>> e : tokensByUserSeq.entrySet()) {
            List<NlSkillInvocationDetector.NlSkillHit> hits = new ArrayList<>(e.getValue().size());
            for (String token : e.getValue()) {
                hits.add(new NlSkillInvocationDetector.NlSkillHit(token));
            }
            out.put(e.getKey(), hits);
        }
        return out;
    }

    /** agent 侧消息是否包含 skill 落地执行信号（非 Read 且非探索类工具）。 */
    static boolean hasSkillExecutionSignal(AiSessionMessage msg) {
        if (msg == null) {
            return false;
        }
        if (isSkillExecutionTool(msg.getToolName())) {
            return true;
        }
        return hasSkillExecutionToolInContentParts(msg.getContentPartsJson());
    }

    static boolean isSkillExecutionTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (NlSkillInvocationDetector.isReadTool(toolName)) {
            return false;
        }
        String norm = toolName.trim().toLowerCase(Locale.ROOT);
        if (SKILL_EXECUTION_TOOL_NAMES.contains(norm)) {
            return true;
        }
        // 未归一化的 Cursor 原名：run_terminal_command_v2、edit_file_v2 …
        return norm.contains("edit") || norm.contains("write") || norm.contains("terminal")
                || norm.contains("bash") || norm.contains("shell") || norm.contains("agent")
                || norm.contains("patch") || norm.contains("delete");
    }

    private static boolean hasSkillExecutionToolInContentParts(String contentPartsJson) {
        if (contentPartsJson == null || contentPartsJson.isBlank()) {
            return false;
        }
        try {
            List<ContentPartDto> parts = MAPPER.readValue(contentPartsJson, new TypeReference<>() {});
            for (ContentPartDto p : parts) {
                if (p == null || !"tool_call".equals(p.getType())) {
                    continue;
                }
                if (isSkillExecutionTool(p.getToolName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return false;
    }
}
