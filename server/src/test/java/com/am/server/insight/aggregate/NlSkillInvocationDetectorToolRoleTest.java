package com.am.server.insight.aggregate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NlSkillInvocationDetectorToolRoleTest {

    @Test
    void detectFromContentText_cursorToolFlattened() {
        String text = """
                [tool_call Read]
                {"path":"/Users/x/.cursor/skills/any-skill/SKILL.md","limit":80}
                """;
        var hits = NlSkillInvocationDetector.detectFromContentText(text);
        assertEquals(1, hits.size());
        assertEquals("/skills/any-skill", hits.get(0).token());
    }

    @Test
    void ignoresNonSkillPathsInContentText() {
        String text = """
                [tool_call Read]
                {"path":"/Users/x/projects/am/server/src/main/java/Foo.java"}
                """;
        assertEquals(0, NlSkillInvocationDetector.detectFromContentText(text).size());
    }
}
