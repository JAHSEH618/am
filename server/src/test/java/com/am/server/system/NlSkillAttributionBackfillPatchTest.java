package com.am.server.system;

import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.system.ResumableBackfill.FailedState;
import com.am.server.system.ResumableBackfill.PassStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NlSkillAttributionBackfillPatchTest {

    private static final String MARKER = NlSkillAttributionBackfillPatch.MARKER_KEY;

    private final AiSessionMessageRepository repo = mock(AiSessionMessageRepository.class);
    private final InMemoryBackfillStore store = new InMemoryBackfillStore();
    private final ResumableBackfill state = new ResumableBackfill(store, MARKER, "desc", 0);

    @Test
    void scansByIdRangeAndReconcilesEachSessionOnce() throws Exception {
        // [1, 12000] 按 5000 切三段；会话 7 的 SKILL.md 读跨了前两段，只整段重算一次
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(1, 5_001)).thenReturn(List.of(7L));
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(5_001, 10_001)).thenReturn(List.of(7L, 8L));
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(10_001, 15_001)).thenReturn(List.of());
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(7L)).thenReturn(List.of());
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(8L)).thenReturn(List.of());

        NlSkillAttributionBackfillPatch.Result r =
                NlSkillAttributionBackfillPatch.backfill(state, repo, new long[]{1, 12_000}, 0);

        assertThat(r.status()).isEqualTo(PassStatus.COMPLETED);
        assertThat(r.scannedSessions()).isEqualTo(2);
        assertThat(r.failedSessions()).isZero();
        verify(repo, times(1)).findByAiSessionIdOrderBySequenceNoAsc(7L);
        verify(repo, times(1)).findByAiSessionIdOrderBySequenceNoAsc(8L);
        verify(repo, times(3)).findSessionIdsWithSkillMdToolReadsInIdRange(anyLong(), anyLong());
        assertThat(store.markers).containsExactly(MARKER);
    }

    @Test
    void aFailingSessionIsRecordedAndSkippedTheMarkerIsStillWrittenAndOthersStillRun() throws Exception {
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(1, 5_001)).thenReturn(List.of(7L, 8L));
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(7L)).thenThrow(new RuntimeException("lock wait timeout"));
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(8L)).thenReturn(List.of());

        NlSkillAttributionBackfillPatch.Result r =
                NlSkillAttributionBackfillPatch.backfill(state, repo, new long[]{1, 100}, 0);

        assertThat(r.failedSessions()).isEqualTo(1);
        verify(repo).findByAiSessionIdOrderBySequenceNoAsc(8L);   // 单会话失败不中断其余会话
        assertThat(store.markers).as("有失败会话也要写完成标记——否则每次重启整表重扫").containsExactly(MARKER);
        assertThat(FailedState.parse(store.values.get(MARKER + ".failed")).items()).containsExactly("7");
    }

    @Test
    void resumesAfterTheSavedRangeCursorWithoutRescanningFinishedRanges() throws Exception {
        // 游标 10001 = 前两段（[1,5001) [5001,10001)）已扫完
        store.values.put(MARKER + ".progress", "10001");
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(10_001, 15_001)).thenReturn(List.of(9L));
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(9L)).thenReturn(List.of());

        NlSkillAttributionBackfillPatch.backfill(state, repo, new long[]{1, 12_000}, 0);

        verify(repo, never()).findSessionIdsWithSkillMdToolReadsInIdRange(1, 5_001);
        verify(repo, never()).findSessionIdsWithSkillMdToolReadsInIdRange(5_001, 10_001);
        verify(repo).findSessionIdsWithSkillMdToolReadsInIdRange(10_001, 15_001);
        assertThat(store.markers).containsExactly(MARKER);
    }

    @Test
    void aRangeLevelQueryFailureAbortsTheRunKeepingTheCursorForNextBoot() {
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(1, 5_001)).thenReturn(List.of());
        when(repo.findSessionIdsWithSkillMdToolReadsInIdRange(5_001, 10_001))
                .thenThrow(new RuntimeException("connection refused"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> NlSkillAttributionBackfillPatch.backfill(state, repo, new long[]{1, 12_000}, 0))
                .isInstanceOf(RuntimeException.class);

        assertThat(store.markers).isEmpty();
        assertThat(store.values.get(MARKER + ".progress")).isEqualTo("5001"); // 第一段已扫完
    }

    @Test
    void afterCompletionOnlyTheFailedSessionsAreRetried() throws Exception {
        store.markers.add(MARKER);
        store.values.put(MARKER + ".failed", new FailedState(0, List.of("7")).toJson());
        when(repo.findByAiSessionIdOrderBySequenceNoAsc(7L)).thenReturn(List.of());

        ResumableBackfill.RetryResult r = state.retryFailed(label ->
                com.am.server.insight.aggregate.NlSkillAttributionSupport.reconcileSession(repo, Long.parseLong(label)));

        assertThat(r.recovered()).isEqualTo(1);
        verify(repo, times(1)).findByAiSessionIdOrderBySequenceNoAsc(7L);
        verify(repo, never()).findSessionIdsWithSkillMdToolReadsInIdRange(anyLong(), anyLong());
        assertThat(store.values).doesNotContainKey(MARKER + ".failed");
    }
}
