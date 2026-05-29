package com.am.server.insight.aggregate;

import com.am.server.agent.api.dto.ContentPartDto;
import com.am.server.domain.ai.AiSessionMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NlSkillExecutionSupportTest {

    @Test
    void readOnlySkills_notAttributed() {
        var user = msg(1, "user", 10, null, null);
        var read = msg(2, "tool", 11, "Read", skillReadArgs("worklog-helper"));
        assertTrue(NlSkillExecutionSupport.attributeExecutedSkills(List.of(user, read)).isEmpty());
    }

    @Test
    void readThenGrep_doesNotAttribute() {
        var user = msg(1, "user", 10, null, null);
        var read = msg(2, "tool", 11, "Read", skillReadArgs("deploy-staging"));
        var grep = msg(3, "tool", 12, "Grep", "{\"pattern\":\"foo\"}");
        assertTrue(NlSkillExecutionSupport.attributeExecutedSkills(List.of(user, read, grep)).isEmpty());
    }

    @Test
    void readThenBash_attributesLastSkillRead() {
        var user = msg(1, "user", 10, null, null);
        var readA = msg(2, "tool", 11, "Read", skillReadArgs("foo"));
        var readB = msg(3, "tool", 12, "Read", skillReadArgs("worklog-helper"));
        var bash = msg(4, "tool", 13, "Bash", "{\"command\":\"echo ok\"}");
        Map<Integer, List<NlSkillInvocationDetector.NlSkillHit>> got =
                NlSkillExecutionSupport.attributeExecutedSkills(List.of(user, readA, readB, bash));
        assertEquals(1, got.size());
        assertEquals("/skills/worklog-helper", got.get(10).get(0).token());
    }

    @Test
    void multipleExecutionsInOneUserTurn() {
        var user = msg(1, "user", 10, null, null);
        var read1 = msg(2, "tool", 11, "Read", skillReadArgs("deploy"));
        var bash1 = msg(3, "tool", 12, "Bash", "{}");
        var read2 = msg(4, "tool", 13, "Read", skillReadArgs("code-review"));
        var edit = msg(5, "tool", 14, "Edit", "{}");
        Map<Integer, List<NlSkillInvocationDetector.NlSkillHit>> got =
                NlSkillExecutionSupport.attributeExecutedSkills(
                        List.of(user, read1, bash1, read2, edit));
        assertEquals(2, got.get(10).size());
        assertEquals("/skills/deploy", got.get(10).get(0).token());
        assertEquals("/skills/code-review", got.get(10).get(1).token());
    }

    @Test
    void nonReadToolInAssistantParts_countsAsExecution() {
        var user = msg(1, "user", 10, null, null);
        var read = msg(2, "assistant", 11, null, null);
        read.setContentPartsJson(partsJson(
                skillReadPart("my-workflow"),
                editPart()));
        Map<Integer, List<NlSkillInvocationDetector.NlSkillHit>> got =
                NlSkillExecutionSupport.attributeExecutedSkills(List.of(user, read));
        assertEquals("/skills/my-workflow", got.get(10).get(0).token());
    }

    private static AiSessionMessage msg(int id, String role, int seq, String toolName, String contentText) {
        var m = new AiSessionMessage();
        m.setId((long) id);
        m.setRole(role);
        m.setSequenceNo(seq);
        m.setToolName(toolName);
        m.setContentText(contentText);
        return m;
    }

    private static String skillReadArgs(String name) {
        return "{\"path\":\"/Users/x/.cursor/skills/" + name + "/SKILL.md\"}";
    }

    private static String partsJson(ContentPartDto... parts) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of(parts));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ContentPartDto skillReadPart(String name) {
        var p = new ContentPartDto();
        p.setType("tool_call");
        p.setToolName("Read");
        p.setArgumentsJson(skillReadArgs(name));
        return p;
    }

    private static ContentPartDto editPart() {
        var p = new ContentPartDto();
        p.setType("tool_call");
        p.setToolName("Edit");
        p.setArgumentsJson("{\"path\":\"/repo/Foo.java\"}");
        return p;
    }
}
