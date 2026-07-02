package com.am.server.web.support;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandStatSupportTest {

    @Test
    void resolveSlashHitsJson_fallsBackToContentText() {
        String json = SlashCommandStatSupport.resolveSlashHitsJson(
                "/explore this", "cursor", null);
        assertNotNull(json);
        assertTrue(json.contains("/explore"));
    }

    @Test
    void mergeSlashHits_includesSkillKind() {
        Map<String, Long> counts = new HashMap<>();
        String json = """
                [{"token":"/fix","kind":"command"},{"token":"/skills/foo","kind":"skill"}]
                """;
        invokeMerge(counts, json);
        assertEquals(1L, counts.get("/fix"));
        assertEquals(1L, counts.get("/skills/foo"));
    }

    private static void invokeMerge(Map<String, Long> counts, String json) {
        try {
            var m = SlashCommandStatSupport.class.getDeclaredMethod(
                    "mergeSlashHits", Map.class, String.class);
            m.setAccessible(true);
            m.invoke(null, counts, json);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
