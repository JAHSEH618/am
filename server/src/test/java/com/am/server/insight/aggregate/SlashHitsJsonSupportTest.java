package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSessionMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void recomputeExtracted_preservesNlSkillAndDropsStaleHits() throws Exception {
        String old = """
                [{"token":"$home","kind":"skill"},{"token":"worklog-helper","kind":"nl_skill"}]
                """;
        SlashHitsJsonSupport.ExtractedRecompute r = SlashHitsJsonSupport.recomputeExtracted(
                old, 0, 2, UserSlashInvocationExtractor.Annotation.empty());
        assertTrue(r.changed());
        assertEquals(0, r.commandCount());
        assertEquals(1, r.skillCount());
        var arr = MAPPER.readTree(r.hitsJson());
        assertEquals(1, arr.size());
        assertEquals("nl_skill", arr.get(0).path("kind").asText());
        assertEquals("worklog-helper", arr.get(0).path("token").asText());
    }

    @Test
    void recomputeExtracted_freshRowWinsOverNlSkillOnSameToken() throws Exception {
        String old = """
                [{"token":"/run-skill","kind":"nl_skill"}]
                """;
        UserSlashInvocationExtractor.Annotation fresh =
                UserSlashInvocationExtractor.annotateUserContent("/run-skill x", "cursor");
        SlashHitsJsonSupport.ExtractedRecompute r =
                SlashHitsJsonSupport.recomputeExtracted(old, 0, 1, fresh);
        assertTrue(r.changed());
        var arr = MAPPER.readTree(r.hitsJson());
        assertEquals(1, arr.size());
        assertEquals("skill", arr.get(0).path("kind").asText());
        assertEquals(1, r.skillCount());
    }

    @Test
    void recomputeExtracted_noChangeWhenAlreadyClean() {
        UserSlashInvocationExtractor.Annotation fresh =
                UserSlashInvocationExtractor.annotateUserContent("/fix bug", "cursor");
        SlashHitsJsonSupport.ExtractedRecompute r =
                SlashHitsJsonSupport.recomputeExtracted(fresh.slashHitsJson(), 1, 0, fresh);
        assertFalse(r.changed());
    }

    @Test
    void recomputeExtracted_noChangeWhenOldJsonIsMysqlNormalized() {
        // MySQL JSON 列回读:键序重排(kind 在前)+ ": " 间隔;语义与 Jackson 序列化一致
        String mysqlNormalized = "[{\"kind\": \"command\", \"token\": \"/fix\"}]";
        UserSlashInvocationExtractor.Annotation fresh =
                UserSlashInvocationExtractor.annotateUserContent("/fix bug", "cursor");
        SlashHitsJsonSupport.ExtractedRecompute r =
                SlashHitsJsonSupport.recomputeExtracted(mysqlNormalized, 1, 0, fresh);
        assertFalse(r.changed());
    }

    @Test
    void recomputeExtracted_nullOldJsonWithStaleCounts() {
        SlashHitsJsonSupport.ExtractedRecompute r = SlashHitsJsonSupport.recomputeExtracted(
                null, 3, 2, UserSlashInvocationExtractor.Annotation.empty());
        assertTrue(r.changed());
        assertEquals(0, r.commandCount());
        assertEquals(0, r.skillCount());
        assertNull(r.hitsJson());
    }
}
