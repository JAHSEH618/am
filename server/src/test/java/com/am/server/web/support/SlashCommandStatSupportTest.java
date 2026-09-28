package com.am.server.web.support;

import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.insight.aggregate.NlSkillInvocationDetector.NlSkillHit;
import com.am.server.insight.aggregate.SlashHitsJsonSupport;
import com.am.server.insight.aggregate.UserSlashInvocationExtractor;
import com.am.server.system.SlashAnnotationFullBackfillPatch;
import com.am.server.system.SystemConfigService;
import com.am.server.system.domain.SysConfig;
import com.am.server.web.dto.ToolStatDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /**
     * 全局排行只读 {@code slash_command_count + slash_skill_count > 0} 的行
     * （{@link AiSessionMessageRepository#loadCountedSlashHitsJsonInWindowGlobal}），前提是：
     * 每个写入方产出的 JSON 里，排行会数的命中条数 == 两列之和。逐个写入方钉住这条不变量——
     * 哪天新增了会进排行、却不进计数列的 kind，这里先红。
     */
    @Test
    void rankedHitsEqualStoredCountsForEveryWriter() {
        // ingest / 全量回填：提取器
        assertRankedEqualsCounts(annotate("/fix bug", "cursor"));
        assertRankedEqualsCounts(annotate("/run-skill x", "cursor"));
        assertRankedEqualsCounts(annotate("/repo/.cursor/skills/foo 跑一下", "cursor"));
        assertRankedEqualsCounts(annotate("/usage", "cursor"));
        assertRankedEqualsCounts(annotate("<skill>\n<name>impeccable</name>\n<path>/x</path>", "codex"));
        assertRankedEqualsCounts(annotate("/fix", "claude"));

        // NL skill 归因：在只有 noise 的行上追加 / 替换 nl_skill
        AiSessionMessage m = new AiSessionMessage();
        UserSlashInvocationExtractor.Annotation noise = annotate("/usage", "cursor");
        m.setSlashCommandCount(noise.slashCommandCount());
        m.setSlashSkillCount(noise.slashSkillCount());
        m.setSlashHitsJson(noise.slashHitsJson());
        assertTrue(SlashHitsJsonSupport.mergeNlSkills(m, List.of(new NlSkillHit("impeccable"))));
        assertRankedEquals(m.getSlashHitsJson(), m.getSlashCommandCount() + m.getSlashSkillCount(), 1);
        assertTrue(SlashHitsJsonSupport.replaceNlSkills(m, List.of(new NlSkillHit("a"), new NlSkillHit("b"))));
        assertRankedEquals(m.getSlashHitsJson(), m.getSlashCommandCount() + m.getSlashSkillCount(), 2);
        assertTrue(SlashHitsJsonSupport.replaceNlSkills(m, List.of()));
        assertRankedEquals(m.getSlashHitsJson(), m.getSlashCommandCount() + m.getSlashSkillCount(), 0);

        // 存量重算（SlashAnnotationBackfillPatch）：新鲜提取 + 保留旧 nl_skill
        SlashHitsJsonSupport.ExtractedRecompute r = SlashHitsJsonSupport.recomputeExtracted(
                "[{\"token\":\"x\",\"kind\":\"nl_skill\"},{\"token\":\"/old\",\"kind\":\"mcp\"}]", 0, 0,
                annotate("/fix", "cursor"));
        assertRankedEquals(r.hitsJson(), r.commandCount() + r.skillCount(), 2);
    }

    @Test
    void globalRankingReadsOnlyCountedRows() {
        AiSessionMessageRepository repo = mock(AiSessionMessageRepository.class);
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.find(SlashAnnotationFullBackfillPatch.MARKER_KEY)).thenReturn(Optional.of(new SysConfig()));
        when(repo.loadCountedSlashHitsJsonInWindowGlobal(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"u1", 1L, "[{\"token\":\"/fix\",\"kind\":\"command\"}]", "cursor"},
                new Object[]{"u2", 2L, "[{\"token\":\"/fix\",\"kind\":\"command\"}]", "cursor"},
                new Object[]{"u2", 2L, "[{\"token\":\"/run-skill\",\"kind\":\"skill\"}]", "cursor"}));

        List<ToolStatDto> out = new SlashCommandStatSupport(repo, config).topCommandTokensGlobal(
                LocalDateTime.of(2026, 9, 21, 0, 0), LocalDateTime.of(2026, 9, 28, 0, 0), List.of("cursor"), 10);

        assertEquals(2, out.size());
        assertEquals("/fix", out.get(0).getToolName());
        assertEquals(2L, out.get(0).getCount());
        assertEquals(2L, out.get(0).getUserCount());
        assertEquals(2L, out.get(0).getSessionCount());
        verify(repo, never()).loadSlashHitsJsonOnlyInWindowGlobal(any(), any(), any());
        verify(repo, never()).loadSlashFallbackContentInWindowGlobal(any(), any(), any());
    }

    private static UserSlashInvocationExtractor.Annotation annotate(String text, String targetType) {
        return UserSlashInvocationExtractor.annotateUserContent(text, targetType);
    }

    private static void assertRankedEqualsCounts(UserSlashInvocationExtractor.Annotation a) {
        assertEquals(a.slashCommandCount() + a.slashSkillCount(), rankedHits(a.slashHitsJson()),
                () -> "ranked hits != stored counts for " + a);
    }

    private static void assertRankedEquals(String json, int storedCounts, int expected) {
        assertEquals(expected, storedCounts, () -> "stored counts for " + json);
        assertEquals(expected, rankedHits(json), () -> "ranked hits for " + json);
    }

    private static long rankedHits(String json) {
        Map<String, Long> counts = new HashMap<>();
        invokeMerge(counts, json);
        return counts.values().stream().mapToLong(Long::longValue).sum();
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
