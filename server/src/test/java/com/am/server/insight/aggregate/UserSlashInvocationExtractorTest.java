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

    // ---------- codex：只认 <skill> 执行信封 ----------

    @Test
    void codexSkillEnvelopeCountsOnce() throws Exception {
        String body = """
                <skill>
                <name>humanizer-zh</name>
                <path>/Users/x/.agents/skills/humanizer-zh/SKILL.md</path>
                ---
                name: humanizer-zh
                description: 去除文本中的 AI 生成痕迹
                """;
        UserSlashInvocationExtractor.Annotation a = annotate(body, "codex");
        assertEquals(0, a.slashCommandCount());
        assertEquals(1, a.slashSkillCount());
        JsonNode arr = MAPPER.readTree(a.slashHitsJson());
        assertEquals(1, arr.size());
        assertEquals("$humanizer-zh", arr.get(0).path("token").asText());
        assertEquals("skill", arr.get(0).path("kind").asText());
    }

    @Test
    void codexEnvelopeBodyDollarTokensNotCounted() {
        // SKILL.md 正文里的 $foo-bar 不再产生额外命中，整条信封只记 1 次
        String body = """
                <skill>
                <name>impeccable</name>
                <path>/x/SKILL.md</path>
                用法：先跑 $lint 再看 $config-name 输出
                """;
        UserSlashInvocationExtractor.Annotation a = annotate(body, "codex");
        assertEquals(1, a.slashSkillCount());
    }

    @Test
    void codexEnvelopeNameNormalizedToLowercase() throws Exception {
        UserSlashInvocationExtractor.Annotation a =
                annotate("<skill>\n<name>Create-Readme</name>\n<path>/x</path>", "codex");
        assertEquals(1, a.slashSkillCount());
        JsonNode arr = MAPPER.readTree(a.slashHitsJson());
        assertEquals("$create-readme", arr.get(0).path("token").asText());
    }

    @Test
    void codexEnvelopeWithoutNameIgnored() {
        assertNull(annotate("<skill>\n<path>/x/SKILL.md</path>", "codex").slashHitsJson());
        assertNull(annotate("<skill>\n<name>   </name>", "codex").slashHitsJson());
        assertNull(annotate("<skill>\n<name>two words</name>", "codex").slashHitsJson());
    }

    @Test
    void codexNameTagInBodyOnlyDoesNotMatch() {
        // <name> 不紧跟信封头（如用户贴的 XML 片段）不算
        UserSlashInvocationExtractor.Annotation a =
                annotate("<skill>\n<path>/x</path>\n<name>later</name>", "codex");
        assertEquals(0, a.slashSkillCount());
    }

    // ---------- codex：正文 $xxx 一律不计（变量误报修复主目标） ----------

    @Test
    void codexTypedDollarMentionsNotCounted() {
        // 显式 $技能提及若真触发，Codex 会另注入 <skill> 信封；正文本身不再计数
        UserSlashInvocationExtractor.Annotation a =
                annotate("$skill-creator 这是什么，先 $review 一下", "codex");
        assertEquals(0, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
        assertNull(a.slashHitsJson());
    }

    @Test
    void codexShellAndTemplateVarsNotCounted() {
        UserSlashInvocationExtractor.Annotation a =
                annotate("echo $path 然后 rm $file，schema 里有 $ref 和 $id", "codex");
        assertEquals(0, a.slashSkillCount());
        assertNull(a.slashHitsJson());
    }

    @Test
    void codexSlashTokensStillIgnored() {
        UserSlashInvocationExtractor.Annotation a = annotate("/help\n/fix\n", "codex");
        assertEquals(0, a.slashCommandCount());
        assertEquals(0, a.slashSkillCount());
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
