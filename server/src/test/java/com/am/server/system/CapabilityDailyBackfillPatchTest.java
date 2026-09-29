package com.am.server.system;

import com.am.server.aggregator.CapabilityDailyAggregator;
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

/**
 * capability_daily 回溯：走 per-date 锁；拿不到锁 / 单日失败不阻塞完成标记；续跑；只重试失败日。
 */
class CapabilityDailyBackfillPatchTest {

    private static final String MARKER = CapabilityDailyBackfillPatch.MARKER_KEY;

    private final InMemoryBackfillStore store = new InMemoryBackfillStore();
    private final ResumableBackfill state = new ResumableBackfill(store, MARKER, "desc", 0);
    private final CapabilityDailyAggregator aggregator = mock(CapabilityDailyAggregator.class);

    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 7, 5);

    @Test
    void everyDayGoesThroughTheDateLockNeverTheUnlockedAggregate() throws Exception {
        when(aggregator.aggregateUnderDateLock(any(), any())).thenReturn(3);

        PassResult r = CapabilityDailyBackfillPatch.backfill(state, FROM, TO, aggregator);

        assertThat(r.status()).isEqualTo(PassStatus.COMPLETED);
        assertThat(r.ok()).isEqualTo(5);
        verify(aggregator, times(5)).aggregateUnderDateLock(any(), eq(CapabilityDailyBackfillPatch.DATE_LOCK_WAIT));
        verify(aggregator, never()).aggregate(any());
        assertThat(store.markers).containsExactly(MARKER);
    }

    @Test
    void aDayWhoseLockIsBusyIsRecordedRetriedInlineThenSkippedAndMarkerStillWritten() throws Exception {
        LocalDate busy = LocalDate.of(2026, 7, 3);
        when(aggregator.aggregateUnderDateLock(any(), any())).thenReturn(1);
        when(aggregator.aggregateUnderDateLock(eq(busy), any())).thenReturn(CapabilityDailyAggregator.LOCK_NOT_ACQUIRED);

        PassResult r = CapabilityDailyBackfillPatch.backfill(state, FROM, TO, aggregator);

        assertThat(r.status()).isEqualTo(PassStatus.COMPLETED);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(store.markers).as("一天拿不到锁不能挡住完成标记").containsExactly(MARKER);
        assertThat(FailedState.parse(store.values.get(MARKER + ".failed")).items()).containsExactly("2026-07-03");
        verify(aggregator, times(ResumableBackfill.UNIT_ATTEMPTS)).aggregateUnderDateLock(eq(busy), any());
        verify(aggregator).aggregateUnderDateLock(eq(LocalDate.of(2026, 7, 4)), any()); // 后面的日子照常
    }

    @Test
    void uniqueKeyConflictOnFirstTryIsToleratedByTheInlineRetry() throws Exception {
        LocalDate d = LocalDate.of(2026, 7, 2);
        when(aggregator.aggregateUnderDateLock(any(), any())).thenReturn(1);
        when(aggregator.aggregateUnderDateLock(eq(d), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate entry uk_day_user_kind_item"))
                .thenReturn(2);

        PassResult r = CapabilityDailyBackfillPatch.backfill(state, FROM, TO, aggregator);

        assertThat(r.failed()).isZero();
        assertThat(store.values).doesNotContainKey(MARKER + ".failed");
    }

    @Test
    void restartResumesFromTheSavedCursor() throws Exception {
        store.values.put(MARKER + ".progress", "2026-07-04");
        when(aggregator.aggregateUnderDateLock(any(), any())).thenReturn(1);

        CapabilityDailyBackfillPatch.backfill(state, FROM, TO, aggregator);

        verify(aggregator, never()).aggregateUnderDateLock(eq(LocalDate.of(2026, 7, 3)), any());
        verify(aggregator).aggregateUnderDateLock(eq(LocalDate.of(2026, 7, 4)), any());
        verify(aggregator).aggregateUnderDateLock(eq(LocalDate.of(2026, 7, 5)), any());
        assertThat(store.markers).containsExactly(MARKER);
    }

    @Test
    void afterCompletionOnlyTheFailedDaysAreRetried() throws Exception {
        store.markers.add(MARKER);
        store.values.put(MARKER + ".failed", new FailedState(0, List.of("2026-07-03")).toJson());
        when(aggregator.aggregateUnderDateLock(any(), any())).thenReturn(4);

        ResumableBackfill.RetryResult r =
                state.retryFailed(label -> CapabilityDailyBackfillPatch.aggregateDay(aggregator, label));

        assertThat(r.recovered()).isEqualTo(1);
        verify(aggregator, times(1)).aggregateUnderDateLock(any(), any());
        verify(aggregator).aggregateUnderDateLock(eq(LocalDate.of(2026, 7, 3)), any());
        assertThat(store.values).doesNotContainKey(MARKER + ".failed");
    }
}
