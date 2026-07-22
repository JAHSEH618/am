package com.am.server.web.support;

import com.am.server.domain.summary.CapabilityDailyRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.web.dto.CapabilityMatrixDto;
import com.am.server.web.dto.CapabilityRankingRowDto;
import com.am.server.web.dto.CapabilityTrendPointDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CapabilityStatSupport：skill tab 显式/NL 并列与跨 kind 使用人数、mcp children 挂载与排序、
 * 矩阵行列排序与 cells 编码。
 * gz
 */
class CapabilityStatSupportTest {

    private final CapabilityDailyRepository repository = mock(CapabilityDailyRepository.class);
    private final EmployeeDisplayService displayService = mock(EmployeeDisplayService.class);
    private final CapabilityStatSupport support = new CapabilityStatSupport(repository, displayService);

    private final LocalDate from = LocalDate.of(2026, 7, 1);
    private final LocalDate to = LocalDate.of(2026, 7, 21);

    @Test
    void skillRankingMergesExplicitAndNlColumns() {
        when(repository.rankRollupByItemKind(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"pdf-skill", "skill", 7L, 2L, 5L},
                new Object[]{"pdf-skill", "nl_skill", 3L, 2L, 3L},
                new Object[]{"humanizer-zh", "nl_skill", 4L, 1L, 2L}));
        when(repository.rankUserCountByItem(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"pdf-skill", 3L},     // 显式 2 人 + NL 2 人，去重后 3 人
                new Object[]{"humanizer-zh", 1L}));

        List<CapabilityRankingRowDto> rows = support.ranking("skill", from, to, 50);

        assertEquals(2, rows.size());
        CapabilityRankingRowDto top = rows.get(0);
        assertEquals("pdf-skill", top.getItem());
        assertEquals(10L, top.getInvokeCount());
        assertEquals(7L, top.getExplicitCount());
        assertEquals(3L, top.getNlCount());
        assertEquals(3L, top.getUserCount());
        assertEquals(8L, top.getSessionCount());
        assertNull(top.getChildren());
    }

    @Test
    void mcpRankingAttachesSortedChildren() {
        when(repository.rankRollupByItemKind(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"exa", "mcp", 9L, 2L, 4L}));
        when(repository.rankUserCountByItem(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"exa", 2L}));
        when(repository.rankDetailByItemSubItem(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"exa", "web_fetch", 2L, 2L},
                new Object[]{"exa", "web_search", 7L, 4L}));

        List<CapabilityRankingRowDto> rows = support.ranking("mcp", from, to, 50);

        assertEquals(1, rows.size());
        CapabilityRankingRowDto server = rows.get(0);
        assertNull(server.getExplicitCount());
        assertEquals(2, server.getChildren().size());
        assertEquals("web_search", server.getChildren().get(0).getSubItem()); // 按调用量降序
        assertEquals(7L, server.getChildren().get(0).getInvokeCount());
    }

    @Test
    void skillTrendSplitsByKindPerDay() {
        when(repository.trendByDateKind(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{LocalDate.of(2026, 7, 1), "skill", 5L},
                new Object[]{LocalDate.of(2026, 7, 1), "nl_skill", 2L},
                new Object[]{LocalDate.of(2026, 7, 2), "nl_skill", 1L}));

        List<CapabilityTrendPointDto> points = support.trend("skill", from, to);

        assertEquals(2, points.size());
        assertEquals(7L, points.get(0).getInvokeCount());
        assertEquals(5L, points.get(0).getExplicitCount());
        assertEquals(2L, points.get(0).getNlCount());
        assertEquals(1L, points.get(1).getInvokeCount());
        assertEquals(0L, points.get(1).getExplicitCount());
    }

    @Test
    void matrixSortsUsersAndItemsByTotalDesc() {
        when(displayService.displayOf(anyString())).thenAnswer(inv -> inv.getArgument(0) + "-name");
        when(repository.matrixByUserItem(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"u1", "pdf-skill", 1L},
                new Object[]{"u2", "pdf-skill", 4L},
                new Object[]{"u2", "humanizer-zh", 2L}));

        CapabilityMatrixDto matrix = support.matrix("skill", from, to);

        assertEquals(List.of("pdf-skill", "humanizer-zh"), matrix.getItems());
        assertEquals("u2", matrix.getUsers().get(0).getUserCode()); // 总量 6 > 1
        assertEquals("u2-name", matrix.getUsers().get(0).getDisplayName());
        assertEquals(6L, matrix.getUsers().get(0).getTotalCount());
        assertEquals(3, matrix.getCells().size());
        // u2(行0) × pdf-skill(列0) = 4
        boolean found = matrix.getCells().stream()
                .anyMatch(cell -> cell[0] == 0 && cell[1] == 0 && cell[2] == 4);
        assertEquals(true, found);
    }
}
