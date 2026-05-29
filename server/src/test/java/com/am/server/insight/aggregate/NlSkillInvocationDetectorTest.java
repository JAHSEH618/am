package com.am.server.insight.aggregate;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NlSkillInvocationDetectorTest {

    @Test
    void detectsReadSkillMdFromToolCall() {
        var call = new com.am.server.agent.api.dto.ContentPartDto();
        call.setType("tool_call");
        call.setToolName("Read");
        call.setArgumentsJson("{\"path\":\"/Users/x/.cursor/skills/worklog-helper/SKILL.md\"}");

        var hits = NlSkillInvocationDetector.detectFromContentParts(java.util.List.of(call));
        assertEquals(1, hits.size());
        assertEquals("/skills/worklog-helper", hits.get(0).token());
    }

    @Test
    void ignoresNonReadTools() {
        var call = new com.am.server.agent.api.dto.ContentPartDto();
        call.setType("tool_call");
        call.setToolName("Grep");
        call.setArgumentsJson("{\"path\":\"/.cursor/skills/foo/SKILL.md\"}");

        assertTrue(NlSkillInvocationDetector.detectFromContentParts(java.util.List.of(call)).isEmpty());
    }

    @Test
    void dedupesMultipleReadsOfSameSkill() {
        var a = new com.am.server.agent.api.dto.ContentPartDto();
        a.setType("tool_call");
        a.setToolName("read_file_v2");
        a.setArgumentsJson("{\"target_file\":\"/proj/.cursor/skills/foo/SKILL.md\"}");
        var b = new com.am.server.agent.api.dto.ContentPartDto();
        b.setType("tool_call");
        b.setToolName("Read");
        b.setArgumentsJson("{\"path\":\"/proj/.cursor/skills/foo/SKILL.md\"}");

        assertEquals(1, NlSkillInvocationDetector.detectFromContentParts(java.util.List.of(a, b)).size());
    }

    @Test
    void skillNameFromPath_windowsAndUnix() {
        assertEquals("bar-baz", NlSkillInvocationDetector.skillNameFromPath(
                "C:\\Users\\x\\.cursor\\skills\\bar-baz\\SKILL.md"));
    }

    @Test
    void agentsSkillsPath() {
        Set<String> tokens = new LinkedHashSet<>();
        NlSkillInvocationDetector.collectSkillNamesFromPath(
                "~/.agents/skills/self-improvement/SKILL.md", tokens);
        assertEquals(1, tokens.size());
        assertTrue(tokens.contains("/skills/self-improvement"));
    }
}
