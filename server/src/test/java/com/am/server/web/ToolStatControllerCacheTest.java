package com.am.server.web;

import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.ToolStatDto;
import com.am.server.web.support.SlashCommandStatSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Slash Commands 排行：同一 (窗口, activeTypes, limit) 多次请求只算一遍。 */
class ToolStatControllerCacheTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO = LocalDate.of(2026, 9, 27);

    private final SlashCommandStatSupport support = mock(SlashCommandStatSupport.class);
    private final ActiveTargetTypesProvider activeTargetTypesProvider = mock(ActiveTargetTypesProvider.class);
    private final ToolStatController controller = new ToolStatController(support, activeTargetTypesProvider);

    @Test
    void rankingIsSharedPerWindowTypesAndLimit() {
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor", "codex"));
        when(support.topCommandTokensGlobal(any(), any(), anyCollection(), anyInt()))
                .thenReturn(List.of(new ToolStatDto("/fix", 3, 2, 2)));

        assertThat(controller.tools(FROM, TO, 20).getData()).extracting(ToolStatDto::getToolName)
                .containsExactly("/fix");
        controller.tools(FROM, TO, 20);
        verify(support, times(1)).topCommandTokensGlobal(any(), any(), anyCollection(), anyInt());

        controller.tools(FROM, TO, 10);
        controller.tools(FROM.plusDays(1), TO, 20);
        verify(support, times(3)).topCommandTokensGlobal(any(), any(), anyCollection(), anyInt());

        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("codex", "cursor"));
        controller.tools(FROM, TO, 20);
        verify(support, times(3)).topCommandTokensGlobal(any(), any(), anyCollection(), anyInt());

        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor"));
        controller.tools(FROM, TO, 20);
        verify(support, times(4)).topCommandTokensGlobal(any(), any(), anyCollection(), anyInt());
    }
}
