package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NlSkillAttributionSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiSessionMessageRepository repo = mock(AiSessionMessageRepository.class);

    @Test
    void reconcileSessionFrom_loadsOnlyFromTurnContainingFirstNewMessage() throws Exception {
        // 旧轮 user#10 已读 SKILL.md（#11），本拍新到的 Bash（#12）让它变成"已执行"
        var user = row("user", 10, null, null, null);
        var read = row("tool", 11, "Read", null, skillReadArgs("worklog-helper"));
        var bash = row("tool", 12, "Bash", null, "{\"command\":\"echo ok\"}");
        when(repo.findUserSequenceNosBefore(eq(9L), eq(12), any(Pageable.class))).thenReturn(List.of(10));
        when(repo.findNlSkillReconcileRowsFrom(9L, 10)).thenReturn(List.of(user, read, bash));

        int updated = NlSkillAttributionSupport.reconcileSessionFrom(repo, 9L, 12);

        assertEquals(1, updated);
        // 只回写 slash 三列（不 save 整行实体——投影出来的游离对象缺列，save 会把它们写成 NULL）
        ArgumentCaptor<String> hits = ArgumentCaptor.forClass(String.class);
        verify(repo).updateSlashHits(eq(10L), hits.capture(), eq(0), eq(1));
        verify(repo, never()).save(any(AiSessionMessage.class));
        verify(repo, never()).findByAiSessionIdOrderBySequenceNoAsc(anyLong());
        verify(repo, never()).findByAiSessionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(anyLong(), any());
        var arr = MAPPER.readTree(hits.getValue());
        assertEquals("/skills/worklog-helper", arr.get(0).path("token").asText());
        assertEquals("nl_skill", arr.get(0).path("kind").asText());
    }

    @Test
    void reconcileSessionFrom_keepsExistingCommandHitsAndCountsWhenAddingNlSkill() throws Exception {
        var user = row("user", 10, null, "[{\"token\":\"/fix\",\"kind\":\"command\"}]", null);
        user.commandCount = 1;
        var read = row("tool", 11, "Read", null, skillReadArgs("deploy"));
        var edit = row("assistant", 12, "Edit", null, "{}");
        when(repo.findUserSequenceNosBefore(eq(9L), eq(12), any(Pageable.class))).thenReturn(List.of(10));
        when(repo.findNlSkillReconcileRowsFrom(9L, 10)).thenReturn(List.of(user, read, edit));

        assertEquals(1, NlSkillAttributionSupport.reconcileSessionFrom(repo, 9L, 12));

        ArgumentCaptor<String> hits = ArgumentCaptor.forClass(String.class);
        verify(repo).updateSlashHits(eq(10L), hits.capture(), eq(1), eq(1));
        var arr = MAPPER.readTree(hits.getValue());
        assertEquals(2, arr.size());
        assertEquals("command", arr.get(0).path("kind").asText());
        assertEquals("nl_skill", arr.get(1).path("kind").asText());
    }

    @Test
    void reconcileSessionFrom_partsJsonDrivesDetectionWhenPresent() {
        // Claude 等把 tool_call 放在 assistant 的 content_parts_json 里；有 parts 时 content_text 不取（投影为 null）
        var user = row("user", 10, null, null, null);
        var assistant = row("assistant", 11, null, null, null);
        assistant.partsJson = "[{\"type\":\"tool_call\",\"tool_name\":\"Read\","
                + "\"arguments_json\":\"{\\\"path\\\":\\\"/x/.claude/skills/review/SKILL.md\\\"}\"},"
                + "{\"type\":\"tool_call\",\"tool_name\":\"Bash\",\"arguments_json\":\"{}\"}]";
        when(repo.findUserSequenceNosBefore(eq(9L), eq(11), any(Pageable.class))).thenReturn(List.of(10));
        when(repo.findNlSkillReconcileRowsFrom(9L, 10)).thenReturn(List.of(user, assistant));

        assertEquals(1, NlSkillAttributionSupport.reconcileSessionFrom(repo, 9L, 11));
        verify(repo).updateSlashHits(eq(10L), any(), eq(0), eq(1));
    }

    @Test
    void reconcileSessionFrom_unchangedAttributionWritesNothing() {
        var user = row("user", 10, null, "[{\"token\":\"/skills/deploy\",\"kind\":\"nl_skill\"}]", null);
        user.skillCount = 1;
        var read = row("tool", 11, "Read", null, skillReadArgs("deploy"));
        var bash = row("tool", 12, "Bash", null, "{}");
        when(repo.findUserSequenceNosBefore(eq(9L), eq(12), any(Pageable.class))).thenReturn(List.of(10));
        when(repo.findNlSkillReconcileRowsFrom(9L, 10)).thenReturn(List.of(user, read, bash));

        assertEquals(0, NlSkillAttributionSupport.reconcileSessionFrom(repo, 9L, 12));
        verify(repo, never()).updateSlashHits(anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    void reconcileSessionFrom_noEarlierUserStartsAtFirstNewSeq() {
        when(repo.findUserSequenceNosBefore(eq(9L), eq(3), any(Pageable.class))).thenReturn(List.of());
        when(repo.findNlSkillReconcileRowsFrom(9L, 3)).thenReturn(List.of(row("user", 3, null, null, null)));

        assertEquals(0, NlSkillAttributionSupport.reconcileSessionFrom(repo, 9L, 3));
        verify(repo).findNlSkillReconcileRowsFrom(9L, 3);
        verify(repo, never()).findByAiSessionIdOrderBySequenceNoAsc(anyLong());
    }

    @Test
    void reconcileSessionFrom_fallsBackToFullScanWhenTurnStartIsNotUser() {
        // SQL 认定的 user 起点在 Java 判据下不是 user：起点不可靠，退回整段重扫
        when(repo.findUserSequenceNosBefore(eq(9L), eq(12), any(Pageable.class))).thenReturn(List.of(10));
        when(repo.findNlSkillReconcileRowsFrom(9L, 10)).thenReturn(List.of(row("tool", 10, "Read", null, "{}")));
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(9L)).thenReturn(List.of());

        NlSkillAttributionSupport.reconcileSessionFrom(repo, 9L, 12);

        verify(repo).findByAiSessionIdOrderBySequenceNoAsc(9L);
    }

    @Test
    void reconcileSession_fullScanStillSavesTheWholeEntity() {
        // 整段重扫（回填 patch 用）保持整行实体 + save，行为不变
        var user = msg("user", 10, null, null);
        var read = msg("tool", 11, "Read", skillReadArgs("deploy"));
        var bash = msg("tool", 12, "Bash", "{}");
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(9L)).thenReturn(List.of(user, read, bash));

        assertEquals(1, NlSkillAttributionSupport.reconcileSession(repo, 9L));

        verify(repo).save(user);
        verify(repo, never()).updateSlashHits(anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    void attribution_isTurnLocal_suffixFromTurnStartMatchesFullScan() {
        // 增量重算成立的前提：各轮互不影响——从某条 user 起的后缀算出的结果，与整段算出的同一段一致
        List<AiSessionMessage> full = List.of(
                msg("tool", 1, "Read", skillReadArgs("orphan")),      // 首条 user 之前，不属于任何轮
                msg("user", 2, null, null),
                msg("tool", 3, "Read", skillReadArgs("deploy")),
                msg("tool", 4, "Bash", "{}"),
                msg("user", 5, null, null),
                msg("tool", 6, "Read", skillReadArgs("code-review")),
                msg("user", 7, null, null),
                msg("tool", 8, "Read", skillReadArgs("worklog-helper")),
                msg("tool", 9, "Edit", "{}"));
        Map<Integer, List<NlSkillInvocationDetector.NlSkillHit>> all =
                NlSkillExecutionSupport.attributeExecutedSkills(full);
        for (int from : List.of(2, 5, 7)) {
            final int f = from;
            Map<Integer, List<NlSkillInvocationDetector.NlSkillHit>> suffix =
                    NlSkillExecutionSupport.attributeExecutedSkills(
                            full.stream().filter(m -> m.getSequenceNo() >= f).toList());
            Map<Integer, List<NlSkillInvocationDetector.NlSkillHit>> expected = all.entrySet().stream()
                    .filter(e -> e.getKey() >= f)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertEquals(expected, suffix, "from=" + from);
        }
    }

    /** 可变的投影行桩：只暴露 {@link AiSessionMessageRepository.NlSkillReconcileRow} 声明的列。 */
    private static final class Row implements AiSessionMessageRepository.NlSkillReconcileRow {
        long id;
        String role;
        int seq;
        String tool;
        String hits;
        int commandCount;
        int skillCount;
        String partsJson;
        String text;

        @Override public Long getId() { return id; }
        @Override public String getRole() { return role; }
        @Override public Integer getSequenceNo() { return seq; }
        @Override public String getToolName() { return tool; }
        @Override public String getSlashHitsJson() { return hits; }
        @Override public Integer getSlashCommandCount() { return commandCount; }
        @Override public Integer getSlashSkillCount() { return skillCount; }
        @Override public String getContentPartsJson() { return partsJson; }
        @Override public String getContentText() { return text; }
    }

    private static Row row(String role, int seq, String toolName, String hitsJson, String contentText) {
        Row r = new Row();
        r.id = seq;
        r.role = role;
        r.seq = seq;
        r.tool = toolName;
        r.hits = hitsJson;
        r.text = contentText;
        return r;
    }

    private static AiSessionMessage msg(String role, int seq, String toolName, String contentText) {
        var m = new AiSessionMessage();
        m.setId((long) seq);
        m.setRole(role);
        m.setSequenceNo(seq);
        m.setToolName(toolName);
        m.setContentText(contentText);
        return m;
    }

    private static String skillReadArgs(String name) {
        return "{\"path\":\"/Users/x/.cursor/skills/" + name + "/SKILL.md\"}";
    }
}
