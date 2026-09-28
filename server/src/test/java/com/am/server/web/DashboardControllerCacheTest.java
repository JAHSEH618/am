package com.am.server.web;

import com.am.server.aggregator.DailySummaryAggregator;
import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.session.WorkSessionRepository;
import com.am.server.domain.summary.DailySummaryRepository;
import com.am.server.insight.config.InsightProperties;
import com.am.server.service.AiPenetrationService;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.service.InstallManifestService;
import com.am.server.service.PenetrationWindow;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.TopItemDto;
import com.am.server.web.sse.SseHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仪表盘轮询接口共享短 TTL 缓存：同一 key 多个标签页只算一遍，影响结果的入参（limit / activeTypes /
 * 渗透率窗口）不同则各算各的；overview 内嵌的 30 天渗透率与 /ai-penetration?window=30d 共用一份。
 */
class DashboardControllerCacheTest {

    private final AgentDeviceRepository deviceRepository = mock(AgentDeviceRepository.class);
    private final WorkSessionRepository workSessionRepository = mock(WorkSessionRepository.class);
    private final AiSessionRepository aiSessionRepository = mock(AiSessionRepository.class);
    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final EmployeeDisplayService employeeDisplayService = mock(EmployeeDisplayService.class);
    private final ActiveTargetTypesProvider activeTargetTypesProvider = mock(ActiveTargetTypesProvider.class);
    private final AiPenetrationService aiPenetrationService = mock(AiPenetrationService.class);

    private final DashboardController controller = new DashboardController(
            deviceRepository, workSessionRepository, aiSessionRepository, eventRepository,
            mock(GitCommitRepository.class), employeeDisplayService, mock(InstallManifestService.class),
            mock(SseHub.class), activeTargetTypesProvider, mock(InsightProperties.class),
            new AgentProperties(), aiPenetrationService,
            mock(DailySummaryRepository.class), mock(DailySummaryAggregator.class));

    @BeforeEach
    void activeTypes() {
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor", "claude"));
    }

    @Test
    void overviewIsComputedOnceAcrossTabsAndSharesPenetrationWithTheWindowEndpoint() {
        when(aiPenetrationService.compute(PenetrationWindow.D30)).thenReturn(42);

        controller.overview();
        controller.overview();
        int viaEndpoint = controller.aiPenetration("30d").getData().getPercent();

        verify(eventRepository, times(1)).aggregateOverviewWindow(any(), any(), anyCollection());
        verify(aiSessionRepository, times(1)).countNonIdleByTargetTypeIn(anyCollection());
        verify(aiPenetrationService, times(1)).compute(PenetrationWindow.D30);
        assertThat(viaEndpoint).isEqualTo(42);
        assertThat(controller.overview().getData().getAiPenetrationPercent()).isEqualTo(42);
    }

    @Test
    void penetrationIsCachedPerWindow() {
        when(aiPenetrationService.compute(PenetrationWindow.D7)).thenReturn(7);
        when(aiPenetrationService.compute(PenetrationWindow.TODAY)).thenReturn(1);

        assertThat(controller.aiPenetration("7d").getData().getPercent()).isEqualTo(7);
        assertThat(controller.aiPenetration("7d").getData().getPercent()).isEqualTo(7);
        assertThat(controller.aiPenetration("today").getData().getPercent()).isEqualTo(1);

        verify(aiPenetrationService, times(1)).compute(PenetrationWindow.D7);
        verify(aiPenetrationService, times(1)).compute(PenetrationWindow.TODAY);
    }

    @Test
    void onlineIsComputedOnceAndRecomputedWhenActiveTypesChange() {
        controller.online();
        controller.online();
        verify(aiSessionRepository, times(1)).findNonIdleSessionSummariesByTargetTypeIn(anyCollection());

        // 白名单顺序不同 = 同一 key；内容不同 = 新 key
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("claude", "cursor"));
        controller.online();
        verify(aiSessionRepository, times(1)).findNonIdleSessionSummariesByTargetTypeIn(anyCollection());

        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor"));
        controller.online();
        verify(aiSessionRepository, times(2)).findNonIdleSessionSummariesByTargetTypeIn(anyCollection());
    }

    @Test
    void topListsAreKeyedByLimit() {
        when(eventRepository.aggregateByProjectInWindowAndTargetTypeIn(any(), any(), anyCollection()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"p1", 300L, 3L, 1L, 1L},
                        new Object[]{"p2", 200L, 2L, 1L, 1L},
                        new Object[]{"p3", 100L, 1L, 1L, 1L}));

        List<TopItemDto> top2 = controller.topProjects(2).getData();
        controller.topProjects(2);
        List<TopItemDto> top3 = controller.topProjects(3).getData();

        verify(eventRepository, times(2)).aggregateByProjectInWindowAndTargetTypeIn(any(), any(), anyCollection());
        assertThat(top2).extracting(TopItemDto::getKey).containsExactly("p1", "p2");
        assertThat(top3).extracting(TopItemDto::getKey).containsExactly("p1", "p2", "p3");

        controller.topEmployees(8);
        controller.topEmployees(8);
        verify(eventRepository, times(1)).aggregateByUserInWindowAndTargetTypeIn(any(), any(), anyCollection());
    }
}
