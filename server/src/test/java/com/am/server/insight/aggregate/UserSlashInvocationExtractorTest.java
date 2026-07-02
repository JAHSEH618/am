package com.am.server.insight.aggregate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserSlashInvocationExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static UserSlashInvocationExtractor.Annotation annotate(String text, String targetType) {
        return UserSlashInvocationExtractor.annotateUserContent(text, targetType);
    }

    // ---------- claude：不解析（不变） ----------

    @Test
    void claudeDoesNotAnnotate() {
        UserSlashInvocationExtractor.Annotation a = annotate("/intake\n/fix\n/usage\n", "claude");
        assertEquals(0, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
        assertNull(a.slashHitsJson());
    }

    // ---------- codex：合法 $技能 保留（原有用例不动） ----------

    @Test
    void codexDollarHyphenatedSkillName() {
        UserSlashInvocationExtractor.Annotation a = annotate("$skill-creator 这是什么", "codex");
        assertEquals(0, a.slashCommandCount());
        assertEquals(1, a.slashSkillCount());
    }

    @Test
    void codexDollarSkillsOnly() {
        UserSlashInvocationExtractor.Annotation a = annotate("$review\n/help\n/fix\n", "codex");
        assertEquals(0, a.slashCommandCount());
        assertEquals(1, a.slashSkillCount());
    }

    @Test
    void codexDollarAnywhereInLine() {
        UserSlashInvocationExtractor.Annotation a =
                annotate("please run $review on this file\n$lint /help", "codex");
        assertEquals(0, a.slashCommandCount());
        assertEquals(2, a.slashSkillCount());
    }

    @Test
    void codexIgnoresDollarNumbers() {
        UserSlashInvocationExtractor.Annotation a = annotate("cost is $100 and $200", "codex");
        assertEquals(0, a.slashSkillCount());
    }

    @Test
    void codexFullWidthDollar() {
        UserSlashInvocationExtractor.Annotation a = annotate("＄review 一下这个文件", "codex");
        assertEquals(1, a.slashSkillCount());
    }

    // ---------- codex：变量误报排除（新增，修复主目标） ----------

    @Test
    void codexRejectsUpperCaseEnvVars() {
        UserSlashInvocationExtractor.Annotation a =
                annotate("请把 $HOME 和 $AM_SERVER_URL 换成实际值", "codex");
        assertEquals(0, a.slashSkillCount());
        assertNull(a.slashHitsJson());
    }

    @Test
    void codexRejectsSnakeAndCamelCaseVars() {
        // $foo_bar 尾随 '_'、$myVar 尾随大写字母 → 均按变量论
        UserSlashInvocationExtractor.Annotation a =
                annotate("$foo_bar 与 $myVar 都是变量", "codex");
        assertEquals(0, a.slashSkillCount());
    }

    @Test
    void codexRejectsDollarWithoutLeadingBoundary() {
        // 引号内 "$path"、赋值 =$path、花括号 ${var} 均无"行首/空白"前界
        UserSlashInvocationExtractor.Annotation a =
                annotate("echo \"$path\" 然后 PATH=$path:/usr/bin 以及 ${var}", "codex");
        assertEquals(0, a.slashSkillCount());
    }

    @Test
    void codexRejectsSingleLetterName() {
        UserSlashInvocationExtractor.Annotation a = annotate("$a 是位置变量", "codex");
        assertEquals(0, a.slashSkillCount());
    }

    @Test
    void codexSkipsFencedCodeBlocks() {
        String body = """
                请看这段脚本
                ```bash
                export $config-name
                ```
                然后 $review 一下
                """;
        UserSlashInvocationExtractor.Annotation a = annotate(body, "codex");
        assertEquals(1, a.slashSkillCount());
    }

    @Test
    void codexSkipsInlineBacktickSpans() {
        UserSlashInvocationExtractor.Annotation a =
                annotate("先跑 `echo $var` 再 $review", "codex");
        assertEquals(1, a.slashSkillCount());
    }

    // ---------- cursor/默认：只认消息首 token（新语义） ----------

    @Test
    void cursorCountsOnlyMessageLeadingToken() throws Exception {
        UserSlashInvocationExtractor.Annotation a =
                annotate("/fix bug\n后面是上下文 /run-skill 不算", "cursor");
        assertEquals(1, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
        JsonNode arr = MAPPER.readTree(a.slashHitsJson());
        assertEquals(1, arr.size());
        assertEquals("/fix", arr.get(0).path("token").asText());
    }

    @Test
    void cursorLeadingSkillToken() {
        UserSlashInvocationExtractor.Annotation a = annotate("/run-skill x", "cursor");
        assertEquals(0, a.slashCommandCount());
        assertEquals(1, a.slashSkillCount());
    }

    @Test
    void cursorLeadingSkillPathToken() {
        UserSlashInvocationExtractor.Annotation a =
                annotate("/repo/.cursor/skills/foo 跑一下", "cursor");
        assertEquals(1, a.slashSkillCount());
    }

    @Test
    void cursorIgnoresMidTextSlash() {
        UserSlashInvocationExtractor.Annotation a =
                annotate("please /fix and /run-skill args\nsrc/foo/bar.go no slash cmd", "cursor");
        assertEquals(0, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
        assertNull(a.slashHitsJson());
    }

    @Test
    void cursorIgnoresPathAndRouteMentions() {
        UserSlashInvocationExtractor.Annotation a =
                annotate("接口 /api 返回 404，查一下 /tmp 目录", "cursor");
        assertEquals(0, a.slashCommandCount());
        assertNull(a.slashHitsJson());
    }

    @Test
    void cursorLeadingNoiseRecordedButNotCounted() throws Exception {
        UserSlashInvocationExtractor.Annotation a = annotate("/usage\n/fix 第二行不算", "cursor");
        assertEquals(0, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
        assertNotNull(a.slashHitsJson());
        JsonNode arr = MAPPER.readTree(a.slashHitsJson());
        assertEquals(1, arr.size());
        assertEquals("noise", arr.get(0).path("kind").asText());
    }

    @Test
    void cursorIgnoresBareSlashAndFragmentLeads() {
        assertNull(annotate("/ 单斜杠", "cursor").slashHitsJson());
        assertNull(annotate("/. 用启发式拆成", "cursor").slashHitsJson());
        assertNull(annotate("/(command 括号碎片", "cursor").slashHitsJson());
    }

    @Test
    void defaultTargetTypeUsesFirstTokenRule() {
        // 单参重载 = 默认(cursor 式)路径，hermes/openclaw 等未识别类型同走此路径
        UserSlashInvocationExtractor.Annotation a =
                UserSlashInvocationExtractor.annotateUserContent("/fix bug");
        assertEquals(1, a.slashCommandCount());
    }

    @Test
    void emptyContent() {
        UserSlashInvocationExtractor.Annotation a =
                UserSlashInvocationExtractor.annotateUserContent("no slash");
        assertEquals(0, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
        assertNull(a.slashHitsJson());
    }
}
