package com.am.server.insight.aggregate;

import com.am.server.agent.api.dto.ContentPartDto;
import com.am.server.domain.ai.AiSessionMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 各 Agent 上报形态下的 NL skill 识别（不绑定具体 skill 名）。
 */
class NlSkillInvocationDetectorAgentMatrixTest {

    @Test
    void cursor_toolRole_toolCallPart() {
        ContentPartDto call = new ContentPartDto();
        call.setType("tool_call");
        call.setToolName("Read");
        call.setArgumentsJson("{\"path\":\"/Users/x/.cursor/skills/my-workflow/SKILL.md\"}");
        assertToken(NlSkillInvocationDetector.detectFromContentParts(List.of(call)), "/skills/my-workflow");
    }

    @Test
    void claude_assistant_embeddedToolUse_filePath() {
        ContentPartDto call = new ContentPartDto();
        call.setType("tool_call");
        call.setToolName("Read");
        call.setArgumentsJson("{\"file_path\":\"/home/x/.claude/skills/deploy/SKILL.md\"}");
        assertToken(NlSkillInvocationDetector.detectFromContentParts(List.of(call)), "/skills/deploy");
    }

    @Test
    void openclaw_assistant_agentsSkillsPath() {
        ContentPartDto call = new ContentPartDto();
        call.setType("tool_call");
        call.setToolName("Read");
        call.setArgumentsJson("{\"path\":\"~/.agents/skills/self-improvement/SKILL.md\"}");
        assertToken(NlSkillInvocationDetector.detectFromContentParts(List.of(call)), "/skills/self-improvement");
    }

    @Test
    void cursor_skillsCursorDirectory() {
        ContentPartDto call = new ContentPartDto();
        call.setType("tool_call");
        call.setToolName("Read");
        call.setArgumentsJson("{\"path\":\"/Users/x/.cursor/skills-cursor/create-skill/SKILL.md\"}");
        assertToken(NlSkillInvocationDetector.detectFromContentParts(List.of(call)), "/skills/create-skill");
    }

    @Test
    void codex_toolRole_jsonInContentText() {
        AiSessionMessage msg = new AiSessionMessage();
        msg.setRole("tool");
        msg.setToolName("Read");
        msg.setContentText("{\"path\":\"/tmp/proj/skills/code-review/SKILL.md\"}");
        assertToken(NlSkillInvocationDetector.detectFromMessage(msg), "/skills/code-review");
    }

    @Test
    void projectScopedSkillsPath() {
        ContentPartDto call = new ContentPartDto();
        call.setType("tool_call");
        call.setToolName("read_file_v2");
        call.setArgumentsJson("{\"target_file\":\"/repo/.cursor/skills/foo/SKILL.md\"}");
        assertToken(NlSkillInvocationDetector.detectFromContentParts(List.of(call)), "/skills/foo");
    }

    @Test
    void ignoresNonSkillRead() {
        ContentPartDto call = new ContentPartDto();
        call.setType("tool_call");
        call.setToolName("Read");
        call.setArgumentsJson("{\"path\":\"/repo/src/main/java/Foo.java\"}");
        assertTrue(NlSkillInvocationDetector.detectFromContentParts(List.of(call)).isEmpty());
    }

    @Test
    void ignoresToolResultBodyWithoutToolContext() {
        String body = "See also /skills/other/SKILL.md in docs";
        assertTrue(NlSkillInvocationDetector.detectFromContentText(body).isEmpty());
    }

    @Test
    void cursorFlattenedToolText() {
        String text = """
                [tool_call Read]
                {"path":"/Users/x/.cursor/skills/any-skill/SKILL.md"}
                """;
        assertToken(NlSkillInvocationDetector.detectFromContentText(text), "/skills/any-skill");
    }

    private static void assertToken(List<NlSkillInvocationDetector.NlSkillHit> hits, String expectedToken) {
        assertEquals(1, hits.size());
        assertEquals(expectedToken, hits.get(0).token());
    }
}
