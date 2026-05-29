package com.am.server.insight.aggregate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserSlashInvocationExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void claudeDoesNotAnnotate() {
        UserSlashInvocationExtractor.Annotation a =
                UserSlashInvocationExtractor.annotateUserContent("/intake\n/fix\n/usage\n", "claude");
        assertEquals(0, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
        assertNull(a.slashHitsJson());
    }

    @Test
    void codexDollarHyphenatedSkillName() {
        UserSlashInvocationExtractor.Annotation a =
                UserSlashInvocationExtractor.annotateUserContent("$skill-creator 这是什么", "codex");
        assertEquals(0, a.slashCommandCount());
        assertEquals(1, a.slashSkillCount());
    }

    @Test
    void codexDollarSkillsOnly() {
        UserSlashInvocationExtractor.Annotation a =
                UserSlashInvocationExtractor.annotateUserContent("$review\n/help\n/fix\n", "codex");
        assertEquals(0, a.slashCommandCount());
        assertEquals(1, a.slashSkillCount());
    }

    @Test
    void codexDollarAnywhereInLine() {
        UserSlashInvocationExtractor.Annotation a = UserSlashInvocationExtractor.annotateUserContent(
                "please run $review on this file\n$lint /help", "codex");
        assertEquals(0, a.slashCommandCount());
        assertEquals(2, a.slashSkillCount());
    }

    @Test
    void codexIgnoresDollarNumbers() {
        UserSlashInvocationExtractor.Annotation a =
                UserSlashInvocationExtractor.annotateUserContent("cost is $100 and $200", "codex");
        assertEquals(0, a.slashSkillCount());
    }

    @Test
    void cursorSlashAfterWhitespaceAnywhere() {
        UserSlashInvocationExtractor.Annotation a = UserSlashInvocationExtractor.annotateUserContent(
                "please /fix and /run-skill args\nsrc/foo/bar.go no slash cmd", "cursor");
        assertEquals(1, a.slashCommandCount());
        assertEquals(1, a.slashSkillCount());
    }

    @Test
    void multiLineUserMessage() throws Exception {
        String body = "hello\n/fix bug\nmore\n/run-skill x\n";
        UserSlashInvocationExtractor.Annotation a = UserSlashInvocationExtractor.annotateUserContent(body, "cursor");
        assertEquals(1, a.slashCommandCount());
        assertEquals(1, a.slashSkillCount());
        assertNotNull(a.slashHitsJson());
        JsonNode arr = MAPPER.readTree(a.slashHitsJson());
        assertEquals(2, arr.size());
    }

    @Test
    void noiseDoesNotIncrementCounts() {
        UserSlashInvocationExtractor.Annotation a =
                UserSlashInvocationExtractor.annotateUserContent("/usage\n/fix\n");
        assertEquals(1, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
    }

    @Test
    void emptyContent() {
        UserSlashInvocationExtractor.Annotation a =
                UserSlashInvocationExtractor.annotateUserContent("no slash");
        assertEquals(0, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
        assertNull(a.slashHitsJson());
    }

    @Test
    void cursorIgnoresBareSlashAndPathFragments() {
        String body = """
                /
                /. 用启发式拆成
                / (command
                please /fix the bug
                """;
        UserSlashInvocationExtractor.Annotation a =
                UserSlashInvocationExtractor.annotateUserContent(body, "cursor");
        assertEquals(1, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
        assertNotNull(a.slashHitsJson());
    }
}
