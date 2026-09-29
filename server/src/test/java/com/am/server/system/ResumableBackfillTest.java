package com.am.server.system;

import com.am.server.system.ResumableBackfill.FailedState;
import com.am.server.system.ResumableBackfill.PassResult;
import com.am.server.system.ResumableBackfill.PassStatus;
import com.am.server.system.ResumableBackfill.RetryResult;
import com.am.server.system.ResumableBackfill.UnitResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 大回填骨架：进度游标 / 失败清单 / 完成标记 / 重试上限。全部用内存 Store，不碰库。
 * 覆盖“一天失败就永远写不上标记、每次重启从头重算”的回归。
 */
class ResumableBackfillTest {

    private static final String MARKER = "unit.backfill_v1";
    private static final String PROGRESS = MARKER + ".progress";
    private static final String FAILED = MARKER + ".failed";

    private final InMemoryBackfillStore store = new InMemoryBackfillStore();
    private final ResumableBackfill bf = new ResumableBackfill(store, MARKER, "desc", 0);

    private static final LocalDate D1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate D8 = LocalDate.of(2026, 7, 8);

    /** 记录每个标签被调用了几次；{@code failing} 里的标签永远抛。 */
    private static final class Calls {
        final Map<String, Integer> count = new HashMap<>();
        final Set<String> failing;

        Calls(String... failing) {
            this.failing = Set.of(failing);
        }

        void run(String label) {
            count.merge(label, 1, Integer::sum);
            if (failing.contains(label)) {
                throw new IllegalStateException("boom " + label);
            }
        }
    }

    // ------------------------------------------------------------------ 主轮

    @Test
    void happyPathWritesMarkerAndCleansProgress() throws Exception {
        Calls calls = new Calls();

        PassResult r = bf.runDayPass(D1, D8, calls::run);

        assertThat(r.status()).isEqualTo(PassStatus.COMPLETED);
        assertThat(r.ok()).isEqualTo(8);
        assertThat(r.failed()).isZero();
        assertThat(store.markers).containsExactly(MARKER);
        assertThat(store.values).doesNotContainKeys(PROGRESS, FAILED);
        assertThat(calls.count.values()).allMatch(n -> n == 1);
    }

    @Test
    void oneFailingDayIsRecordedSkippedAndDoesNotBlockTheMarker() throws Exception {
        Calls calls = new Calls("2026-07-03");

        PassResult r = bf.runDayPass(D1, D8, calls::run);

        assertThat(r.status()).isEqualTo(PassStatus.COMPLETED);
        assertThat(r.ok()).isEqualTo(7);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(store.markers).as("有失败日也要写完成标记——否则每次重启重算全部历史").containsExactly(MARKER);
        FailedState st = FailedState.parse(store.values.get(FAILED));
        assertThat(st.items()).containsExactly("2026-07-03");
        assertThat(st.attempts()).isZero();
        assertThat(calls.count.get("2026-07-03")).as("原地重试后放弃").isEqualTo(ResumableBackfill.UNIT_ATTEMPTS);
        assertThat(calls.count.get("2026-07-04")).as("失败日之后照常继续").isEqualTo(1);
    }

    @Test
    void transientFailureIsAbsorbedByTheInlineRetry() throws Exception {
        int[] n = {0};

        PassResult r = bf.runDayPass(D1, D1.plusDays(2), label -> {
            if (label.equals("2026-07-02") && n[0]++ == 0) {
                throw new IllegalStateException("deadlock, first try only");
            }
        });

        assertThat(r.failed()).isZero();
        assertThat(store.values).doesNotContainKey(FAILED);
    }

    @Test
    void resumesFromTheSavedCursorInsteadOfTheBeginning() throws Exception {
        store.values.put(PROGRESS, "2026-07-05");
        Calls calls = new Calls();

        bf.runDayPass(D1, D8, calls::run);

        assertThat(calls.count.keySet()).containsExactlyInAnyOrder(
                "2026-07-05", "2026-07-06", "2026-07-07", "2026-07-08");
        assertThat(store.markers).containsExactly(MARKER);
    }

    @Test
    void cursorAdvancesPerDaySoAnInterruptedRunLosesAtMostOneDay() throws Exception {
        Calls calls = new Calls();

        assertThatThrownBy(() -> bf.runDayPass(D1, D8, label -> {
            calls.run(label);
            if (label.equals("2026-07-04")) {
                throw new InterruptedException("shutdown");
            }
        })).isInstanceOf(InterruptedException.class);

        assertThat(store.markers).isEmpty();
        assertThat(store.values.get(PROGRESS)).isEqualTo("2026-07-04"); // 3 号做完，4 号被打断未记
        // 下次启动：从 4 号续，不重做 1~3
        Calls second = new Calls();
        new ResumableBackfill(store, MARKER, "desc", 0).runDayPass(D1, D8, second::run);
        assertThat(second.count).doesNotContainKeys("2026-07-01", "2026-07-02", "2026-07-03");
        assertThat(second.count).containsKey("2026-07-04");
    }

    @Test
    void failedDaysFromAnAbortedRunSurviveIntoTheResumedRun() throws Exception {
        // 第一轮：3 号失败、5 号被打断
        assertThatThrownBy(() -> bf.runDayPass(D1, D8, label -> {
            if (label.equals("2026-07-03")) {
                throw new IllegalStateException("bad data");
            }
            if (label.equals("2026-07-05")) {
                throw new InterruptedException();
            }
        })).isInstanceOf(InterruptedException.class);
        assertThat(FailedState.parse(store.values.get(FAILED)).items()).containsExactly("2026-07-03");

        // 第二轮：从 5 号续，清单里的 3 号仍在
        new ResumableBackfill(store, MARKER, "desc", 0).runDayPass(D1, D8, new Calls()::run);

        assertThat(store.markers).containsExactly(MARKER);
        assertThat(FailedState.parse(store.values.get(FAILED)).items()).containsExactly("2026-07-03");
    }

    @Test
    void failureListIsBoundedAndAFullListAbortsTheRunWithoutMarker() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = from.plusDays(ResumableBackfill.MAX_FAILED_ITEMS + 49);
        Calls calls = new Calls(); // 不用它的 failing，这里全部失败
        PassResult r = bf.runDayPass(from, to, label -> {
            calls.run(label);
            throw new IllegalStateException("db down");
        });

        assertThat(r.status()).isEqualTo(PassStatus.ABORTED);
        assertThat(store.markers).isEmpty();
        assertThat(FailedState.parse(store.values.get(FAILED)).items()).hasSize(ResumableBackfill.MAX_FAILED_ITEMS);
        // 游标停在“第一个放不进清单的日子”，不前移过它
        assertThat(store.values.get(PROGRESS))
                .isEqualTo(from.plusDays(ResumableBackfill.MAX_FAILED_ITEMS).toString());
    }

    @Test
    void progressWriteFailureDegradesButNeverBreaksTheRun() throws Exception {
        store.failWrites = true;

        PassResult r = bf.runDayPass(D1, D1.plusDays(2), new Calls()::run);

        assertThat(r.status()).isEqualTo(PassStatus.COMPLETED);
        assertThat(store.markers).as("标记写失败：不抛，下次启动补写").isEmpty();
    }

    // ------------------------------------------------------------------ 重试轮

    @Test
    void retryPassOnlyTouchesFailedItemsAndDropsTheOnesThatRecover() throws Exception {
        store.markers.add(MARKER);
        store.values.put(FAILED, new FailedState(0, List.of("2026-07-03", "2026-07-06")).toJson());
        Calls calls = new Calls("2026-07-06");

        RetryResult r = bf.retryFailed(calls::run);

        assertThat(calls.count.keySet()).containsExactlyInAnyOrder("2026-07-03", "2026-07-06");
        assertThat(r.recovered()).isEqualTo(1);
        assertThat(r.remaining()).isEqualTo(1);
        assertThat(r.exhausted()).isFalse();
        FailedState st = FailedState.parse(store.values.get(FAILED));
        assertThat(st.items()).containsExactly("2026-07-06");
        assertThat(st.attempts()).isEqualTo(1);
    }

    @Test
    void retryPassDeletesTheFailedKeyOnceEverythingRecovered() throws Exception {
        store.markers.add(MARKER);
        store.values.put(FAILED, new FailedState(1, List.of("2026-07-03")).toJson());

        RetryResult r = bf.retryFailed(new Calls()::run);

        assertThat(r.remaining()).isZero();
        assertThat(store.values).doesNotContainKey(FAILED);
        assertThat(new ResumableBackfill(store, MARKER, "d", 0).hasPendingWork()).isFalse();
    }

    @Test
    void retriesAreCappedThenGiveUpForGoodAndStopCallingTheWork() throws Exception {
        store.markers.add(MARKER);
        store.values.put(FAILED, new FailedState(0, List.of("2026-07-03")).toJson());
        Calls calls = new Calls("2026-07-03");

        RetryResult last = null;
        for (int boot = 1; boot <= ResumableBackfill.MAX_RETRY_ATTEMPTS; boot++) {
            ResumableBackfill fresh = new ResumableBackfill(store, MARKER, "d", 0);
            assertThat(fresh.hasPendingWork()).as("boot " + boot).isTrue();
            last = fresh.retryFailed(calls::run);
        }
        assertThat(last.exhausted()).isTrue();
        assertThat(FailedState.parse(store.values.get(FAILED)).attempts()).isEqualTo(ResumableBackfill.MAX_RETRY_ATTEMPTS);

        // 之后再启动：不再起线程、不再调用 work
        int before = calls.count.get("2026-07-03");
        ResumableBackfill after = new ResumableBackfill(store, MARKER, "d", 0);
        assertThat(after.hasPendingWork()).isFalse();
        RetryResult r = after.retryFailed(calls::run);
        assertThat(r.retried()).isZero();
        assertThat(r.exhausted()).isTrue();
        assertThat(calls.count.get("2026-07-03")).isEqualTo(before);
        assertThat(FailedState.parse(store.values.get(FAILED)).items())
                .as("放弃后清单保留供人工排查").containsExactly("2026-07-03");
    }

    @Test
    void attemptCounterIsPersistedBeforeTheRetryRunSoACrashStillCounts() throws Exception {
        store.markers.add(MARKER);
        store.values.put(FAILED, new FailedState(0, List.of("2026-07-03")).toJson());

        assertThatThrownBy(() -> bf.retryFailed(label -> {
            throw new InterruptedException("killed mid-retry");
        })).isInstanceOf(InterruptedException.class);

        assertThat(FailedState.parse(store.values.get(FAILED)).attempts()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ hasPendingWork

    @Test
    void hasPendingWorkTruthTable() throws Exception {
        assertThat(bf.hasPendingWork()).as("标记未写").isTrue();

        store.markers.add(MARKER);
        assertThat(bf.hasPendingWork()).as("标记已写、无失败项").isFalse();

        store.values.put(FAILED, new FailedState(2, List.of("x")).toJson());
        assertThat(bf.hasPendingWork()).as("有失败项且次数未用尽").isTrue();

        store.values.put(FAILED, new FailedState(ResumableBackfill.MAX_RETRY_ATTEMPTS, List.of("x")).toJson());
        assertThat(bf.hasPendingWork()).as("次数用尽").isFalse();
    }

    // ------------------------------------------------------------------ 单元 / 纯函数

    @Test
    void runUnitReportsFailedAndOverflowAndInterruptIsNotAFailure() throws Exception {
        assertThat(bf.runUnit("a", () -> { })).isEqualTo(UnitResult.OK);
        assertThat(bf.runUnit("b", () -> {
            throw new IllegalStateException();
        })).isEqualTo(UnitResult.FAILED);
        assertThat(bf.failedItems()).containsExactly("b");

        assertThatThrownBy(() -> bf.runUnit("c", () -> {
            throw new InterruptedException();
        })).isInstanceOf(InterruptedException.class);
        assertThat(bf.failedItems()).as("被打断不算失败").containsExactly("b");
    }

    @Test
    void failedStateRoundTripsAndToleratesGarbage() {
        FailedState st = new FailedState(2, List.of("2026-07-03", "42"));
        assertThat(FailedState.parse(st.toJson())).isEqualTo(st);
        assertThat(FailedState.parse(null)).isEqualTo(FailedState.EMPTY);
        assertThat(FailedState.parse("")).isEqualTo(FailedState.EMPTY);
        assertThat(FailedState.parse("{not json")).isEqualTo(FailedState.EMPTY);
    }

    @Test
    void resumeHelpersFallBackToTheFloorOnMissingOrBadCursor() {
        LocalDate earliest = LocalDate.of(2026, 3, 1);
        assertThat(ResumableBackfill.resumeDay(null, earliest)).isEqualTo(earliest);
        assertThat(ResumableBackfill.resumeDay("garbage", earliest)).isEqualTo(earliest);
        assertThat(ResumableBackfill.resumeDay("2026-01-01", earliest)).as("早于最早数据日").isEqualTo(earliest);
        assertThat(ResumableBackfill.resumeDay("2026-05-09", earliest)).isEqualTo(LocalDate.of(2026, 5, 9));

        assertThat(ResumableBackfill.resumeId(null, 7)).isEqualTo(7);
        assertThat(ResumableBackfill.resumeId("x", 7)).isEqualTo(7);
        assertThat(ResumableBackfill.resumeId("3", 7)).isEqualTo(7);
        assertThat(ResumableBackfill.resumeId("12000", 7)).isEqualTo(12000);
    }

    @Test
    void keysAreNamespacedUnderTheMarkerSoBumpingTheVersionRestartsClean() {
        assertThat(bf.progressKey()).isEqualTo(MARKER + ".progress");
        assertThat(bf.failedKey()).isEqualTo(MARKER + ".failed");
        List<String> all = new ArrayList<>(List.of(bf.progressKey(), bf.failedKey()));
        assertThat(all).allMatch(k -> k.startsWith(MARKER));
    }
}
