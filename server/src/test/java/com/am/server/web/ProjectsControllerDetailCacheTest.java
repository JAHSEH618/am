package com.am.server.web;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.PeopleDetailDto;
import com.am.server.web.dto.ProjectDetailDto;
import com.am.server.web.support.ProjectsWindowSnapshotCache;
import com.am.server.web.support.SlashCommandStatSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目详情：贡献者 / 时间线 / Top 模型 / Slash Top 按 (project, 窗口, activeTypes) 共享 45s，
 * 每次请求的 summary 仍是快照副本，topModel 回填不污染缓存。
 */
class ProjectsControllerDetailCacheTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO = LocalDate.of(2026, 9, 27);

    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final AiSessionRepository aiSessionRepository = mock(AiSessionRepository.class);
    private final GitCommitRepository gitCommitRepository = mock(GitCommitRepository.class);
    private final ActiveTargetTypesProvider activeTargetTypesProvider = mock(ActiveTargetTypesProvider.class);
    private final SlashCommandStatSupport slashCommandStatSupport = mock(SlashCommandStatSupport.class);

    private final ProjectsController controller = new ProjectsController(
            eventRepository, aiSessionRepository, gitCommitRepository, mock(EmployeeDisplayService.class),
            activeTargetTypesProvider, slashCommandStatSupport, new ObjectMapper(),
            new ProjectsWindowSnapshotCache(eventRepository, aiSessionRepository, gitCommitRepository));

    @BeforeEach
    void seed() {
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor", "claude"));
        LocalDateTime last = LocalDateTime.of(2026, 9, 22, 10, 0);
        when(eventRepository.aggregateProjectModelUserInWindowAndTargetTypeIn(any(), any(), anyCollection()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"p1", "sonnet", "alice", 50L, 2L, 1L, last, null},
                        new Object[]{"p1", "opus", "bob", 100L, 3L, 1L, last, null}));
        when(eventRepository.aggregateContributorsByProjectAndTargetTypeIn(eq("p1"), any(), any(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"bob", 100L, 3L, 1L}, new Object[]{"alice", 50L, 2L, 1L}));
        when(eventRepository.aggregateProjectDailyTimelineAndTargetTypeIn(eq("p1"), any(), any(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{Date.valueOf("2026-09-22"), 150L, 5L, 2L, 2L}));
        when(eventRepository.aggregateModelsByProjectAndTargetTypeIn(eq("p1"), any(), any(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"opus", 100L, 1L}, new Object[]{"sonnet", 50L, 1L}));
        when(slashCommandStatSupport.topCommandTokensForProject(eq("p1"), any(), any(), anyCollection(), anyInt()))
                .thenReturn(List.of(new PeopleDetailDto.NameValuePair("/fix", 2L)));
    }

    @Test
    void detailPartsAreSharedWithinTheWindow() {
        ProjectDetailDto first = controller.detail("p1", FROM, TO).getData();
        ProjectDetailDto second = controller.detail("p1", FROM, TO).getData();

        verify(eventRepository, times(1)).aggregateContributorsByProjectAndTargetTypeIn(any(), any(), any(), anyCollection());
        verify(eventRepository, times(1)).aggregateProjectDailyTimelineAndTargetTypeIn(any(), any(), any(), anyCollection());
        verify(eventRepository, times(1)).aggregateModelsByProjectAndTargetTypeIn(any(), any(), any(), anyCollection());
        verify(slashCommandStatSupport, times(1)).topCommandTokensForProject(any(), any(), any(), anyCollection(), anyInt());
        verify(aiSessionRepository, times(1))
                .aggregateContributorRollupsByProjectAndTargetTypeIn(any(), any(), any(), anyCollection());

        for (ProjectDetailDto d : List.of(first, second)) {
            assertThat(d.getSummary().getTopModel()).isEqualTo("opus");
            assertThat(d.getSummary().getTotalTokens()).isEqualTo(150L);
            assertThat(d.getContributorMatrix()).extracting(ProjectDetailDto.Contributor::getUserCode)
                    .containsExactly("bob", "alice");
            assertThat(d.getDailyTimeline()).extracting(ProjectDetailDto.DailyPoint::getDate)
                    .containsExactly("2026-09-22");
            assertThat(d.getTopModels()).extracting(ProjectDetailDto.NameValuePair::getName)
                    .containsExactly("opus", "sonnet");
            assertThat(d.getTopSlashCommands()).extracting(ProjectDetailDto.NameValuePair::getName)
                    .containsExactly("/fix");
        }
        assertThat(first.getSummary()).as("summary 每次请求各自一份快照副本").isNotSameAs(second.getSummary());
    }

    @Test
    void otherWindowOrTypesRecomputeAndUnknownProjectSkipsDetailQueries() {
        controller.detail("p1", FROM, TO);
        controller.detail("p1", FROM.plusDays(1), TO);
        verify(eventRepository, times(2)).aggregateContributorsByProjectAndTargetTypeIn(any(), any(), any(), anyCollection());

        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor"));
        controller.detail("p1", FROM, TO);
        verify(eventRepository, times(3)).aggregateContributorsByProjectAndTargetTypeIn(any(), any(), any(), anyCollection());

        ProjectDetailDto missing = controller.detail("nope", FROM, TO).getData();
        assertThat(missing.getContributorMatrix()).isEmpty();
        verify(eventRepository, never()).aggregateContributorsByProjectAndTargetTypeIn(eq("nope"), any(), any(), anyCollection());
        verify(slashCommandStatSupport, never()).topCommandTokensForProject(eq("nope"), any(), any(), anyCollection(), anyInt());
        verify(aiSessionRepository, never())
                .aggregateContributorRollupsByProjectAndTargetTypeIn(eq("nope"), any(), any(), anyCollection());
    }
}
