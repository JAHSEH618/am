package com.am.server.aggregator;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.summary.DailySummaryRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 页面路径的 ensureFreshAsync：不在调用线程算、同日 single-flight；防抖有最长等待。 */
class DailySummaryAggregatorAsyncFreshTest {

    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final DailySummaryRepository summaryRepository = mock(DailySummaryRepository.class);
    private final ActiveTargetTypesProvider activeTargetTypesProvider = mock(ActiveTargetTypesProvider.class);

    private DailySummaryAggregator aggregator;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        aggregator = new DailySummaryAggregator(mock(AiSessionRepository.class), mock(AiSessionMessageRepository.class),
                eventRepository, summaryRepository, mock(GitCommitRepository.class), activeTargetTypesProvider,
                mock(DynamicScheduledTaskManager.class));
        Field self = DailySummaryAggregator.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(aggregator, aggregator);
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor"));
        // 全量聚合的第一步：发现当日用户。卡在这里模拟"整日重算很慢"。
        when(eventRepository.findActiveUsersInWindowAndTargetTypeIn(any(), any(), any())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        aggregator.shutdown();
    }

    @Test
    void asyncReturnsImmediatelyAndCoalescesSameDate() {
        LocalDate d = LocalDate.of(2026, 9, 28);
        long start = System.nanoTime();
        assertThat(aggregator.ensureFreshAsync(d, Duration.ofSeconds(60))).isTrue();
        assertThat(aggregator.ensureFreshAsync(d, Duration.ofSeconds(60)))
                .as("同日已有后台重算在排队/执行，不重复提交").isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));

        release.countDown();
        verify(eventRepository, timeout(3000).times(1)).findActiveUsersInWindowAndTargetTypeIn(any(), any(), any());
        // 算完进入 TTL：再次请求走快速路径，不再提交
        verify(summaryRepository, timeout(3000)).findUserCodesWithNonZeroAiStatsOnDate(d);
    }

    @Test
    void debounceDelayIsCappedByMaxWait() {
        long since = 1_000_000L;
        assertThat(DailySummaryAggregator.debounceDelayMs(since, since)).isEqualTo(15_000L);
        assertThat(DailySummaryAggregator.debounceDelayMs(since, since + 50_000L))
                .as("离最长等待只剩 10s → 最多再等 10s").isEqualTo(10_000L);
        assertThat(DailySummaryAggregator.debounceDelayMs(since, since + 90_000L))
                .as("已超最长等待 → 立即执行").isZero();
    }
}
