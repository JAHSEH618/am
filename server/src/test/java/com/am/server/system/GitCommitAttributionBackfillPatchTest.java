package com.am.server.system;

import com.am.server.aggregator.GitCommitAttributionEngine;
import com.am.server.system.ResumableBackfill.FailedState;
import com.am.server.system.ResumableBackfill.PassResult;
import com.am.server.system.ResumableBackfill.PassStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** commit 归因回溯：按天、backfilled=true；单日失败跳过并记录；续跑；只重试失败日。 */
class GitCommitAttributionBackfillPatchTest {

    private static final String MARKER = GitCommitAttributionBackfillPatch.MARKER_KEY;

    private final InMemoryBackfillStore store = new InMemoryBackfillStore();
    private final ResumableBackfill state = new ResumableBackfill(store, MARKER, "desc", 0);
    private final GitCommitAttributionEngine engine = mock(GitCommitAttributionEngine.class);

    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 7, 4);

    @Test
    void attributesEachDayAsBackfilledAndWritesTheMarker() {
        when(engine.attributeWindow(any(), any(), eq(true))).thenReturn(2);

        PassResult r = run(FROM, TO);

        assertThat(r.status()).isEqualTo(PassStatus.COMPLETED);
        assertThat(r.ok()).isEqualTo(4);
        verify(engine).attributeWindow(FROM.atStartOfDay(), FROM.plusDays(1).atStartOfDay(), true);
        verify(engine).attributeWindow(TO.atStartOfDay(), TO.plusDays(1).atStartOfDay(), true);
        assertThat(store.markers).containsExactly(MARKER);
    }

    @Test
    void oneFailingDayDoesNotStopTheRestNorTheMarker() {
        LocalDate bad = LocalDate.of(2026, 7, 2);
        when(engine.attributeWindow(any(), any(), eq(true))).thenReturn(1);
        when(engine.attributeWindow(eq(bad.atStartOfDay()), any(), eq(true)))
                .thenThrow(new IllegalStateException("Deadlock found when trying to get lock"));

        PassResult r = run(FROM, TO);

        assertThat(r.failed()).isEqualTo(1);
        assertThat(store.markers).containsExactly(MARKER);
        assertThat(FailedState.parse(store.values.get(MARKER + ".failed")).items()).containsExactly("2026-07-02");
        verify(engine).attributeWindow(LocalDate.of(2026, 7, 3).atStartOfDay(),
                LocalDate.of(2026, 7, 4).atStartOfDay(), true);
    }

    @Test
    void restartResumesFromCursorAndDoesNotRedoFinishedDays() {
        store.values.put(MARKER + ".progress", "2026-07-03");
        when(engine.attributeWindow(any(), any(), eq(true))).thenReturn(1);

        run(FROM, TO);

        verify(engine, never()).attributeWindow(eq(FROM.atStartOfDay()), any(), eq(true));
        verify(engine, times(2)).attributeWindow(any(), any(), eq(true));
    }

    @Test
    void afterCompletionOnlyFailedDaysAreRetried() throws Exception {
        store.markers.add(MARKER);
        store.values.put(MARKER + ".failed", new FailedState(1, List.of("2026-07-02")).toJson());
        when(engine.attributeWindow(any(), any(), eq(true))).thenReturn(3);

        state.retryFailed(label -> GitCommitAttributionBackfillPatch.attributeDay(engine, label));

        verify(engine, times(1)).attributeWindow(any(), any(), eq(true));
        assertThat(store.values).doesNotContainKey(MARKER + ".failed");
    }

    private PassResult run(LocalDate from, LocalDate to) {
        try {
            return GitCommitAttributionBackfillPatch.backfill(state, from, to, engine);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
