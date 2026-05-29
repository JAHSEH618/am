package com.am.server.insight.aggregate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSlashInvocationKindTest {

    @Test
    void noiseKeys() {
        assertTrue(UserSlashInvocationKind.isNoiseSlashKey("/usage"));
        assertTrue(UserSlashInvocationKind.isNoiseSlashKey("/exit"));
        assertFalse(UserSlashInvocationKind.isNoiseSlashKey("/fix"));
    }

    @Test
    void plausibleCursorSlashToken() {
        assertFalse(UserSlashInvocationKind.isPlausibleCursorSlashToken("/"));
        assertFalse(UserSlashInvocationKind.isPlausibleCursorSlashToken("/."));
        assertFalse(UserSlashInvocationKind.isPlausibleCursorSlashToken("/("));
        assertFalse(UserSlashInvocationKind.isPlausibleCursorSlashToken("/. 用启发式拆成"));
        assertTrue(UserSlashInvocationKind.isPlausibleCursorSlashToken("/fix"));
        assertTrue(UserSlashInvocationKind.isPlausibleCursorSlashToken("/run-skill"));
        assertTrue(UserSlashInvocationKind.isPlausibleCursorSlashToken("/usage"));
        assertTrue(UserSlashInvocationKind.isPlausibleCursorSlashToken("/repo/.cursor/skills/foo"));
    }

    @Test
    void skillHeuristics() {
        assertTrue(UserSlashInvocationKind.isSkillSlashKey("/my-skill"));
        assertTrue(UserSlashInvocationKind.isSkillSlashKey("/repo/.cursor/skills/foo"));
        assertFalse(UserSlashInvocationKind.isSkillSlashKey("/fix"));
        assertEquals("command", UserSlashInvocationKind.kindOfSlashKey("/edit"));
        assertEquals("skill", UserSlashInvocationKind.kindOfSlashKey("/run-skill"));
    }
}
