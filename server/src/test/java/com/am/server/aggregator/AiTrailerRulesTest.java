package com.am.server.aggregator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiTrailerRules：内置签名命中（大小写不敏感）、sys_config 追加规则、坏配置容错。
 * gz
 */
class AiTrailerRulesTest {

    private static Optional<AiTrailerRules.TrailerRule> match(String body) {
        return AiTrailerRules.match(body, AiTrailerRules.merged("[]"));
    }

    @Test
    void claudeSignaturesMatched() {
        assertEquals("claude_coauthor",
                match("fix: xxx\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>").orElseThrow().kind());
        assertEquals("claude_code",
                match("🤖 Generated with [Claude Code](https://claude.com/claude-code)").orElseThrow().kind());
        assertEquals("claude_code",
                match("Claude-Session: https://claude.ai/code/session_123").orElseThrow().kind());
        assertEquals("claude", match("co-authored-by: claude opus <x@y>").orElseThrow().targetType());
    }

    @Test
    void otherToolSignaturesMatched() {
        assertEquals("codex", match("Co-Authored-By: OpenAI-Codex <bot@openai.com>").orElseThrow().targetType());
        assertEquals("codex", match("co-authored-by: codex <bot@openai.com>").orElseThrow().targetType());
        assertEquals("cursor", match("Co-authored-by: Cursor Agent <hi@cursor.com>").orElseThrow().targetType());
        assertEquals("copilot", match("Co-authored-by: GitHub-Copilot <copilot@github.com>").orElseThrow().targetType());
        assertEquals("aider", match("Co-authored-by: aider (gpt-5) <aider@aider.chat>").orElseThrow().targetType());
    }

    @Test
    void plainMessagesDoNotMatch() {
        assertTrue(match(null).isEmpty());
        assertTrue(match("").isEmpty());
        assertTrue(match("fix: 修复渗透率统计口径").isEmpty());
        assertTrue(match("讨论了 claude 的用法").isEmpty());       // 提到工具名但不是 trailer 形态
        assertTrue(match("Co-authored-by: 张三 <z@x.com>").isEmpty()); // 人类 co-author
    }

    @Test
    void sysConfigRulesAppendedAfterBuiltIn() {
        List<AiTrailerRules.TrailerRule> rules = AiTrailerRules.merged(
                "[{\"kind\":\"windsurf\",\"pattern\":\"co-authored-by:\\\\s*windsurf\",\"target_type\":\"windsurf\"}]");
        AiTrailerRules.TrailerRule hit =
                AiTrailerRules.match("Co-Authored-By: Windsurf <bot@windsurf.ai>", rules).orElseThrow();
        assertEquals("windsurf", hit.kind());
        assertEquals("windsurf", hit.targetType());
        // 内置规则依然优先：同 body 同时命中内置与追加时取内置
        AiTrailerRules.TrailerRule builtin = AiTrailerRules.match(
                "Co-Authored-By: Claude\nCo-Authored-By: Windsurf", rules).orElseThrow();
        assertEquals("claude_coauthor", builtin.kind());
    }

    @Test
    void badSysConfigToleratedAsBuiltInOnly() {
        assertEquals(AiTrailerRules.merged("[]").size(), AiTrailerRules.merged("not json").size());
        // 坏正则条目跳过，好条目保留
        List<AiTrailerRules.TrailerRule> rules = AiTrailerRules.merged(
                "[{\"kind\":\"bad\",\"pattern\":\"([\"},{\"kind\":\"ok\",\"pattern\":\"foo-bot\"}]");
        assertTrue(AiTrailerRules.match("made by foo-bot", rules).isPresent());
    }
}
