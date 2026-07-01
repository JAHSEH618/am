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
import java.util.Map;
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

    @Test
    void aggregateChunkIsTransactional() throws Exception {
        java.lang.reflect.Method m = DailySummaryAggregator.class.getDeclaredMethod(
                "aggregateChunk", LocalDate.class, List.class,
                java.time.LocalDateTime.class, java.time.LocalDateTime.class,
                java.util.Collection.class, java.util.Map.class);
        assertThat(m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                .as("每批聚合必须自成事务以缩短锁窗口").isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void incrementalStillSavesAllUsersViaChunks() {
        aggregator.aggregate(LocalDate.of(2026, 7, 1), new java.util.LinkedHashSet<>(List.of("A", "B", "C")));
        ArgumentCaptor<List<DailySummary>> captor = ArgumentCaptor.forClass(List.class);
        verify(summaryRepository).saveAll(captor.capture()); // 3 人 < 一批 50，仍一次 saveAll
        assertThat(captor.getValue()).extracting(DailySummary::getUserCode)
                .containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueueRefreshMapMergesUsersPerDateWithinDebounce() throws Exception {
        LocalDate d = LocalDate.of(2026, 7, 1);
        aggregator.enqueueRefresh(java.util.Map.of(d, Set.of("U1")));
        aggregator.enqueueRefresh(java.util.Map.of(d, Set.of("U2")));

        Field pu = DailySummaryAggregator.class.getDeclaredField("pendingUsers");
        pu.setAccessible(true);
        Map<LocalDate, Set<String>> pending = (Map<LocalDate, Set<String>>) pu.get(aggregator);
        assertThat(pending.get(d)).containsExactlyInAnyOrder("U1", "U2");

        // 清理：取消 15s 延迟任务，避免测试退出后在 mock 上跑增量
        Field pr = DailySummaryAggregator.class.getDeclaredField("pendingRefresh");
        pr.setAccessible(true);
        ((Map<LocalDate, java.util.concurrent.ScheduledFuture<?>>) pr.get(aggregator))
                .values().forEach(f -> f.cancel(false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullRequestSurvivesLaterIncrementalEnqueue() throws Exception {
        LocalDate d = LocalDate.of(2026, 7, 1);
        aggregator.enqueueRefresh(java.util.List.of(d));               // 全量(admin/backfill)
        aggregator.enqueueRefresh(java.util.Map.of(d, Set.of("U1")));  // 随后 ingest 增量,不得降级全量

        Field pf = DailySummaryAggregator.class.getDeclaredField("pendingFull");
        pf.setAccessible(true);
        Set<LocalDate> pendingFull = (Set<LocalDate>) pf.get(aggregator);
        assertThat(pendingFull).as("全量标记必须在后续增量入队后仍存活 → 触发时跑全量而非增量").contains(d);

        // 清理延迟任务,避免 15s 后在 mock 上跑聚合
        Field pr = DailySummaryAggregator.class.getDeclaredField("pendingRefresh");
        pr.setAccessible(true);
        ((Map<LocalDate, java.util.concurrent.ScheduledFuture<?>>) pr.get(aggregator))
                .values().forEach(f -> f.cancel(false));
    }
}
