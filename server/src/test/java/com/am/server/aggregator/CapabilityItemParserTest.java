package com.am.server.aggregator;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CapabilityItemParser：MCP 工具名（大小写 / 非法名容错）、技能 token 归一、插件命名空间解析。
 * gz
 */
class CapabilityItemParserTest {

    // ---------- parseMcpToolName ----------

    @Test
    void mcpLowercaseCanonical() {
        CapabilityItemParser.McpTool mt =
                CapabilityItemParser.parseMcpToolName("mcp__exa__web_search").orElseThrow();
        assertEquals("exa", mt.server());
        assertEquals("web_search", mt.tool());
    }

    @Test
    void mcpAgentNormalizedCapitalizedMergesToSameKey() {
        // agent 端 NormalizeToolName 会首字母大写为 Mcp__...；解析后与全小写变体同 key
        CapabilityItemParser.McpTool a =
                CapabilityItemParser.parseMcpToolName("Mcp__Exa__Web_Search").orElseThrow();
        CapabilityItemParser.McpTool b =
                CapabilityItemParser.parseMcpToolName("MCP__exa__web_search").orElseThrow();
        assertEquals(a, b);
        assertEquals("exa", a.server());
        assertEquals("web_search", a.tool());
    }

    @Test
    void mcpToolMayContainDoubleUnderscore() {
        CapabilityItemParser.McpTool mt =
                CapabilityItemParser.parseMcpToolName("mcp__server__tool__extra").orElseThrow();
        assertEquals("server", mt.server());
        assertEquals("tool__extra", mt.tool());
    }

    @Test
    void mcpTrimsSurroundingWhitespace() {
        CapabilityItemParser.McpTool mt =
                CapabilityItemParser.parseMcpToolName("  mcp__exa__t  ").orElseThrow();
        assertEquals("exa", mt.server());
        assertEquals("t", mt.tool());
    }

    @Test
    void mcpMalformedNamesRejected() {
        assertTrue(CapabilityItemParser.parseMcpToolName(null).isEmpty());
        assertTrue(CapabilityItemParser.parseMcpToolName("").isEmpty());
        assertTrue(CapabilityItemParser.parseMcpToolName("Read").isEmpty());
        assertTrue(CapabilityItemParser.parseMcpToolName("mcp__").isEmpty());
        assertTrue(CapabilityItemParser.parseMcpToolName("mcp__server").isEmpty());     // 缺 tool 段
        assertTrue(CapabilityItemParser.parseMcpToolName("mcp__server__").isEmpty());   // tool 空
        assertTrue(CapabilityItemParser.parseMcpToolName("mcp____tool").isEmpty());     // server 空
        assertTrue(CapabilityItemParser.parseMcpToolName("mcpx__a__b").isEmpty());      // 前缀不符
        assertTrue(CapabilityItemParser.parseMcpToolName("mcp__ser ver__tool").isEmpty()); // 含空白
    }

    // ---------- normalizeSkillItem ----------

    @Test
    void normalizeStripsPrefixes() {
        assertEquals("pdf", CapabilityItemParser.normalizeSkillItem("/pdf"));
        assertEquals("review", CapabilityItemParser.normalizeSkillItem("$review"));      // codex
        assertEquals("review", CapabilityItemParser.normalizeSkillItem("＄review"));      // codex 全角
        assertEquals("humanizer-zh", CapabilityItemParser.normalizeSkillItem("/skills/humanizer-zh")); // nl_skill
        assertEquals("foo", CapabilityItemParser.normalizeSkillItem("/SKILLS/Foo"));
        assertEquals("plain", CapabilityItemParser.normalizeSkillItem("plain"));
    }

    @Test
    void normalizeBlankResultsAreNull() {
        assertNull(CapabilityItemParser.normalizeSkillItem(null));
        assertNull(CapabilityItemParser.normalizeSkillItem(""));
        assertNull(CapabilityItemParser.normalizeSkillItem("/"));
        assertNull(CapabilityItemParser.normalizeSkillItem("/skills/"));
    }

    // ---------- parseNamespaceSkill ----------

    @Test
    void namespaceSkillParsed() {
        CapabilityItemParser.NamespaceSkill ns =
                CapabilityItemParser.parseNamespaceSkill("superpowers:brainstorming").orElseThrow();
        assertEquals("superpowers", ns.namespace());
        assertEquals("brainstorming", ns.skill());
        assertTrue(CapabilityItemParser.parseNamespaceSkill("product-management:write-spec").isPresent());
    }

    @Test
    void namespaceSkillSplitsOnFirstColonOnly() {
        CapabilityItemParser.NamespaceSkill ns =
                CapabilityItemParser.parseNamespaceSkill("a:b:c").orElseThrow();
        assertEquals("a", ns.namespace());
        assertEquals("b:c", ns.skill());
    }

    @Test
    void namespaceSkillNoiseRejected() {
        assertTrue(CapabilityItemParser.parseNamespaceSkill(null).isEmpty());
        assertTrue(CapabilityItemParser.parseNamespaceSkill("no-colon").isEmpty());
        assertTrue(CapabilityItemParser.parseNamespaceSkill("12:30").isEmpty());        // 时间碎片
        assertTrue(CapabilityItemParser.parseNamespaceSkill("http://x").isEmpty());     // URL
        assertTrue(CapabilityItemParser.parseNamespaceSkill(":x").isEmpty());
        assertTrue(CapabilityItemParser.parseNamespaceSkill("x:").isEmpty());
        assertTrue(CapabilityItemParser.parseNamespaceSkill("ns:sk ill").isEmpty());    // 含空白
    }

    @Test
    void normalizeThenParseNamespaceEndToEnd() {
        String item = CapabilityItemParser.normalizeSkillItem("/Product-Management:Write-Spec");
        Optional<CapabilityItemParser.NamespaceSkill> ns = CapabilityItemParser.parseNamespaceSkill(item);
        assertEquals("product-management", ns.orElseThrow().namespace());
        assertEquals("write-spec", ns.orElseThrow().skill());
    }
}
