package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSessionMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashHitsJsonSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mergeNlSkills_incrementsSkillCountAndDedupesToken() throws Exception {
        AiSessionMessage user = new AiSessionMessage();
        user.setSlashHitsJson("""
                [{"token":"/fix","kind":"command"}]
                """);
        user.setSlashCommandCount(1);
        user.setSlashSkillCount(0);

        boolean changed = SlashHitsJsonSupport.mergeNlSkills(user,
                List.of(new NlSkillInvocationDetector.NlSkillHit("/skills/worklog-helper")));
        assertTrue(changed);
        assertEquals(1, user.getSlashCommandCount());
        assertEquals(1, user.getSlashSkillCount());

        assertFalse(SlashHitsJsonSupport.mergeNlSkills(user,
                List.of(new NlSkillInvocationDetector.NlSkillHit("/skills/worklog-helper"))));

        var arr = MAPPER.readTree(user.getSlashHitsJson());
        assertEquals(2, arr.size());
        assertEquals("nl_skill", arr.get(1).path("kind").asText());
    }
}
