package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
        var user = msg("user", 10, null, null);
        var read = msg("tool", 11, "Read", skillReadArgs("worklog-helper"));
        var bash = msg("tool", 12, "Bash", "{\"command\":\"echo ok\"}");
        when(repo.findUserSequenceNosBefore(eq(9L), eq(12), any(Pageable.class))).thenReturn(List.of(10));
        when(repo.findByAiSessionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(9L, 10))
                .thenReturn(List.of(user, read, bash));

        int updated = NlSkillAttributionSupport.reconcileSessionFrom(repo, 9L, 12);

        assertEquals(1, updated);
        verify(repo).save(user);
        verify(repo, never()).findByAiSessionIdOrderBySequenceNoAsc(anyLong());
        var arr = MAPPER.readTree(user.getSlashHitsJson());
        assertEquals("/skills/worklog-helper", arr.get(0).path("token").asText());
        assertEquals("nl_skill", arr.get(0).path("kind").asText());
    }

    @Test
    void reconcileSessionFrom_noEarlierUserStartsAtFirstNewSeq() {
        when(repo.findUserSequenceNosBefore(eq(9L), eq(3), any(Pageable.class))).thenReturn(List.of());
        when(repo.findByAiSessionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(9L, 3))
                .thenReturn(List.of(msg("user", 3, null, null)));

        assertEquals(0, NlSkillAttributionSupport.reconcileSessionFrom(repo, 9L, 3));
        verify(repo).findByAiSessionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(9L, 3);
        verify(repo, never()).findByAiSessionIdOrderBySequenceNoAsc(anyLong());
    }

    @Test
    void reconcileSessionFrom_fallsBackToFullScanWhenTurnStartIsNotUser() {
        // SQL 认定的 user 起点在 Java 判据下不是 user：起点不可靠，退回整段重扫
        when(repo.findUserSequenceNosBefore(eq(9L), eq(12), any(Pageable.class))).thenReturn(List.of(10));
        when(repo.findByAiSessionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(9L, 10))
                .thenReturn(List.of(msg("tool", 10, "Read", "{}")));
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(9L)).thenReturn(List.of());

        NlSkillAttributionSupport.reconcileSessionFrom(repo, 9L, 12);

        verify(repo).findByAiSessionIdOrderBySequenceNoAsc(9L);
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
