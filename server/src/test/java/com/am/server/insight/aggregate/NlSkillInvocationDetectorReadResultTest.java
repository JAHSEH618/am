package com.am.server.insight.aggregate;

import com.am.server.agent.api.dto.ContentPartDto;
import com.am.server.domain.ai.AiSessionMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Read SKILL.md 后 tool_result 正文若引用其它 skill，不应误识别为本次 Read。
 */
class NlSkillInvocationDetectorReadResultTest {

    @Test
    void detectFromMessage_ignoresCrossReferencesInToolResult() {
        var call = new ContentPartDto();
        call.setType("tool_call");
        call.setToolName("Read");
        call.setArgumentsJson("{\"path\":\"/Users/x/.cursor/skills/babysit/SKILL.md\"}");

        var result = new ContentPartDto();
        result.setType("tool_result");
        result.setToolName("Read");
        result.setText("""
                See also ~/.cursor/skills/create-skill/SKILL.md and
                /skills/deploy-staging/SKILL.md for related workflows.
                """);

        var msg = new AiSessionMessage();
        msg.setRole("tool");
        msg.setToolName("Read");
        msg.setContentPartsJson(toJson(List.of(call, result)));

        var hits = NlSkillInvocationDetector.detectFromMessage(msg);
        assertEquals(1, hits.size());
        assertEquals("/skills/babysit", hits.get(0).token());
    }

    private static String toJson(List<ContentPartDto> parts) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parts);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
