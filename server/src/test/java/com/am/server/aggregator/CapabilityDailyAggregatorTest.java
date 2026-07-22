package com.am.server.aggregator;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.summary.CapabilityDaily;
import com.am.server.domain.summary.CapabilityDailyRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CapabilityDailyAggregator：三路取数合并计数、MCP 双粒度、大小写变体合并、
 * parts 兜底路的会话让位、整日 delete + insert。
 * gz
 */
class CapabilityDailyAggregatorTest {

    private final AiSessionMessageRepository messageRepository = mock(AiSessionMessageRepository.class);
    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final CapabilityDailyRepository capabilityRepository = mock(CapabilityDailyRepository.class);
    private final ActiveTargetTypesProvider activeTargetTypesProvider = mock(ActiveTargetTypesProvider.class);
    private final DynamicScheduledTaskManager scheduledTaskManager = mock(DynamicScheduledTaskManager.class);

    private final LocalDate day = LocalDate.of(2026, 7, 21);

    private CapabilityDailyAggregator aggregator;

    @BeforeEach
    void setUp() throws Exception {
        aggregator = new CapabilityDailyAggregator(
                messageRepository, eventRepository, capabilityRepository,
                activeTargetTypesProvider, scheduledTaskManager);
        // 单测无 Spring 代理，把 self 指回自身，让 aggregate -> self.replaceDay 自调用可解析。
        Field self = CapabilityDailyAggregator.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(aggregator, aggregator);

        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor", "claude", "codex"));
        when(messageRepository.loadSlashHitsJsonOnlyInWindowGlobal(any(), any(), any()))
                .thenReturn(List.of());
        when(eventRepository.aggregateMcpToolCallRowsInWindowAndTargetTypeIn(any(), any(), any()))
                .thenReturn(List.of());
        when(messageRepository.loadMcpContentPartsInWindowGlobal(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void noActiveTypesSkipsEntirely() {
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of());
        assertEquals(0, aggregator.aggregate(day));
        verify(capabilityRepository, never()).deleteByWorkDate(any());
        verify(capabilityRepository, never()).saveAll(any());
    }

    @Test
    void skillHitsCountedByKindCommandAndNoiseIgnored() {
        when(messageRepository.loadSlashHitsJsonOnlyInWindowGlobal(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"u1", 11L,
                        "[{\"token\":\"/fix\",\"kind\":\"command\"},"
                        + "{\"token\":\"/pdf-skill\",\"kind\":\"skill\"},"
                        + "{\"token\":\"/skills/humanizer-zh\",\"kind\":\"nl_skill\"},"
                        + "{\"token\":\"/usage\",\"kind\":\"noise\"}]", "cursor"},
                new Object[]{"u2", 21L,
                        "[{\"token\":\"$pdf-skill\",\"kind\":\"skill\"}]", "codex"}));

        List<CapabilityDaily> rows = runAndCapture();

        assertEquals(3, rows.size());
        assertRow(rows, "u1", CapabilityDaily.KIND_SKILL, "pdf-skill", "", 1, 1);
        assertRow(rows, "u1", CapabilityDaily.KIND_NL_SKILL, "humanizer-zh", "", 1, 1);
        assertRow(rows, "u2", CapabilityDaily.KIND_SKILL, "pdf-skill", "", 1, 1);
        // command / noise 不入表
        assertTrue(rows.stream().noneMatch(r -> r.getItem().contains("fix") || r.getItem().contains("usage")));
    }

    @Test
    void namespacedSkillAlsoLandsInPluginNsBothGranularities() {
        when(messageRepository.loadSlashHitsJsonOnlyInWindowGlobal(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"u1", 12L,
                        "[{\"token\":\"/superpowers:brainstorming\",\"kind\":\"skill\"}]", "cursor"}));

        List<CapabilityDaily> rows = runAndCapture();

        assertEquals(3, rows.size());
        assertRow(rows, "u1", CapabilityDaily.KIND_SKILL, "superpowers:brainstorming", "", 1, 1);
        assertRow(rows, "u1", CapabilityDaily.KIND_PLUGIN_NS, "superpowers", "", 1, 1);
        assertRow(rows, "u1", CapabilityDaily.KIND_PLUGIN_NS, "superpowers", "brainstorming", 1, 1);
    }

    @Test
    void mcpEventsMergeCaseVariantsAndKeepBothGranularities() {
        when(eventRepository.aggregateMcpToolCallRowsInWindowAndTargetTypeIn(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"u1", 11L, "mcp__Exa__web_search", 3L},
                new Object[]{"u1", 11L, "Mcp__exa__Web_Search", 2L},   // 大小写变体，同会话 → 合并
                new Object[]{"u1", 13L, "mcp__exa__web_fetch", 1L},
                new Object[]{"u2", 22L, "not_mcp_tool", 4L},           // 非 MCP，忽略
                new Object[]{"u2", 23L, "mcp____broken", 5L}));        // server 空段，忽略

        List<CapabilityDaily> rows = runAndCapture();

        assertEquals(3, rows.size());
        // 汇总行：session 去重跨 tool（11、13 两个会话）
        assertRow(rows, "u1", CapabilityDaily.KIND_MCP, "exa", "", 6, 2);
        assertRow(rows, "u1", CapabilityDaily.KIND_MCP, "exa", "web_search", 5, 1);
        assertRow(rows, "u1", CapabilityDaily.KIND_MCP, "exa", "web_fetch", 1, 1);
    }

    @Test
    void partsFallbackOnlyForSessionsWithoutMcpEvents() {
        when(eventRepository.aggregateMcpToolCallRowsInWindowAndTargetTypeIn(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"u1", 11L, "mcp__exa__web_search", 1L}));
        when(messageRepository.loadMcpContentPartsInWindowGlobal(any(), any(), any())).thenReturn(List.<Object[]>of(
                // 会话 11 已有 MCP 事件 → parts 兜底让位，不重复计数
                new Object[]{"u1", 11L, "[{\"type\":\"tool_call\",\"tool_name\":\"mcp__exa__web_search\"}]"},
                // 会话 31 无 MCP 事件（hermes 类 provider）→ 走兜底
                new Object[]{"u3", 31L,
                        "[{\"type\":\"tool_call\",\"tool_name\":\"Mcp__Notion__search\"},"
                        + "{\"type\":\"text\",\"text\":\"x\"},"
                        + "{\"type\":\"tool_call\",\"tool_name\":\"Read\"}]"},
                // 坏 JSON 静默跳过
                new Object[]{"u3", 32L, "not json"}));

        List<CapabilityDaily> rows = runAndCapture();

        assertEquals(4, rows.size());
        assertRow(rows, "u1", CapabilityDaily.KIND_MCP, "exa", "", 1, 1);
        assertRow(rows, "u1", CapabilityDaily.KIND_MCP, "exa", "web_search", 1, 1);
        assertRow(rows, "u3", CapabilityDaily.KIND_MCP, "notion", "", 1, 1);
        assertRow(rows, "u3", CapabilityDaily.KIND_MCP, "notion", "search", 1, 1);
    }

    @Test
    void emptyDayStillClearsExistingRows() {
        assertEquals(0, aggregator.aggregate(day));
        // 整日重算语义：即便当日无任何能力信号，也要清掉旧行（消息可能被判 invalid 后回撤）
        verify(capabilityRepository).deleteByWorkDate(eq(day));
        verify(capabilityRepository, never()).saveAll(any());
    }

    @SuppressWarnings("unchecked")
    private List<CapabilityDaily> runAndCapture() {
        aggregator.aggregate(day);
        verify(capabilityRepository).deleteByWorkDate(eq(day));
        ArgumentCaptor<List<CapabilityDaily>> captor = ArgumentCaptor.forClass(List.class);
        verify(capabilityRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private void assertRow(List<CapabilityDaily> rows, String user, String kind,
                           String item, String subItem, int invokes, int sessions) {
        CapabilityDaily row = rows.stream()
                .filter(r -> user.equals(r.getUserCode()) && kind.equals(r.getKind())
                        && item.equals(r.getItem()) && subItem.equals(r.getSubItem()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing row: " + user + "/" + kind + "/" + item + "/" + subItem + " in " + describe(rows)));
        assertEquals(invokes, row.getInvokeCount(), "invoke_count of " + item + "/" + subItem);
        assertEquals(sessions, row.getSessionCount(), "session_count of " + item + "/" + subItem);
    }

    private static String describe(List<CapabilityDaily> rows) {
        StringBuilder sb = new StringBuilder();
        for (CapabilityDaily r : rows) {
            sb.append(r.getUserCode()).append('/').append(r.getKind()).append('/')
                    .append(r.getItem()).append('/').append(r.getSubItem()).append(' ');
        }
        return sb.toString();
    }
}
