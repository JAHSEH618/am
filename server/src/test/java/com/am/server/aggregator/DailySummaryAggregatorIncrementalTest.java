package com.am.server.aggregator;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.summary.DailySummary;
import com.am.server.domain.summary.DailySummaryRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailySummaryAggregatorIncrementalTest {

    private final AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
    private final AiSessionMessageRepository messageRepository = mock(AiSessionMessageRepository.class);
    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final DailySummaryRepository summaryRepository = mock(DailySummaryRepository.class);
    private final GitCommitRepository gitCommitRepository = mock(GitCommitRepository.class);
    private final ActiveTargetTypesProvider activeTargetTypesProvider = mock(ActiveTargetTypesProvider.class);
    private final DynamicScheduledTaskManager scheduledTaskManager = mock(DynamicScheduledTaskManager.class);

    private DailySummaryAggregator aggregator;

    @BeforeEach
    void setUp() throws Exception {
        aggregator = new DailySummaryAggregator(sessionRepository, messageRepository, eventRepository,
                summaryRepository, gitCommitRepository, activeTargetTypesProvider, scheduledTaskManager);
        // 单测无 Spring 代理，把 self 指回自身，让全量→增量核的 self.aggregate(...) 自调用可解析。
        Field self = DailySummaryAggregator.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(aggregator, aggregator);
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void incrementalOnlyProcessesGivenUsersAndSkipsDiscovery() {
        aggregator.aggregate(LocalDate.of(2026, 7, 1), new LinkedHashSet<>(List.of("U1", "U2")));

        ArgumentCaptor<List<DailySummary>> captor = ArgumentCaptor.forClass(List.class);
        verify(summaryRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(DailySummary::getUserCode)
                .containsExactlyInAnyOrder("U1", "U2");
        // 增量核绝不走全量发现查询
        verify(eventRepository, never()).findActiveUsersInWindowAndTargetTypeIn(any(), any(), any());
        verify(summaryRepository, never()).findUserCodesWithNonZeroAiStatsOnDate(any());
        // commit 计数必须走 user-set 过滤版查询，绝不能退化成未过滤的全表版
        verify(gitCommitRepository).countGroupedByUserCodeInCommitWindowAndUserCodeIn(
                any(), any(), eq(new LinkedHashSet<>(List.of("U1", "U2"))));
        verify(gitCommitRepository, never()).countGroupedByUserCodeInCommitWindow(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullDiscoversUsersThenDelegatesToIncremental() {
        when(eventRepository.findActiveUsersInWindowAndTargetTypeIn(any(), any(), any()))
                .thenReturn(List.of("U9"));
        when(summaryRepository.findUserCodesWithNonZeroAiStatsOnDate(any()))
                .thenReturn(List.of());

        aggregator.aggregate(LocalDate.of(2026, 7, 1));

        verify(eventRepository).findActiveUsersInWindowAndTargetTypeIn(any(), any(), any());
        ArgumentCaptor<List<DailySummary>> captor = ArgumentCaptor.forClass(List.class);
        verify(summaryRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(DailySummary::getUserCode).containsExactly("U9");
    }
}
