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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * daily_summary 的 token 汇总护栏：Dashboard「Token 走势」= SUM(daily_summary.total_*_tokens)，
 * 历史坏值（2026-09-21 的 2.07 万亿 input）不能再进汇总。
 * 策略：正常路径仍是一条 SUM；SUM 超过单会话上限才复核逐会话明细，跳过异常会话 + 单日封顶。
 */
class DailySummaryAggregatorTokenGuardTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

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
        Field self = DailySummaryAggregator.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(aggregator, aggregator);
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("codex"));
    }

    private DailySummary aggregateU1() {
        aggregator.aggregate(DAY, new LinkedHashSet<>(List.of("U1")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DailySummary>> captor = ArgumentCaptor.forClass(List.class);
        verify(summaryRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        return captor.getValue().get(0);
    }

    private void stubSum(long in, long out) {
        when(sessionRepository.sumTokensByUserInLastActivityWindowAndTargetTypeIn(eq("U1"), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{in, out}));
    }

    private void stubSlices(Object[]... rows) {
        when(sessionRepository.findTokenSlicesByUserAndLastActivityWindowAndTargetTypeIn(eq("U1"), any(), any(), any()))
                .thenReturn(List.of(rows));
    }

    @Test
    void normalDay_usesSingleSumQuery_andDoesNotTouchSessionDetails() {
        stubSum(400_000_000L, 2_000_000L);

        DailySummary row = aggregateU1();

        assertThat(row.getTotalInputTokens()).isEqualTo(400_000_000L);
        assertThat(row.getTotalOutputTokens()).isEqualTo(2_000_000L);
        verify(sessionRepository, never())
                .findTokenSlicesByUserAndLastActivityWindowAndTargetTypeIn(any(), any(), any(), any());
    }

    @Test
    void sumExactlyAtSessionLimit_staysOnFastPath() {
        stubSum(2_000_000_000L, 2_000_000_000L);

        DailySummary row = aggregateU1();

        assertThat(row.getTotalInputTokens()).isEqualTo(2_000_000_000L);
        verify(sessionRepository, never())
                .findTokenSlicesByUserAndLastActivityWindowAndTargetTypeIn(any(), any(), any(), any());
    }

    @Test
    void poisonedSession_isSkipped_soTheDayKeepsOnlyRealUsage() {
        // 2026-09-21 事故形态：一个会话把当日 SUM 顶到 2.07 万亿
        stubSum(2_070_054_000_000L + 300_000_000L, 10_246_000_000L + 1_500_000L);
        stubSlices(
                new Object[]{11L, 2_070_054_000_000L, 10_246_000_000L},
                new Object[]{12L, 300_000_000L, 1_500_000L});

        DailySummary row = aggregateU1();

        assertThat(row.getTotalInputTokens()).isEqualTo(300_000_000L);
        assertThat(row.getTotalOutputTokens()).isEqualTo(1_500_000L);
    }

    @Test
    void heavyButLegitimateDay_overSessionLimitInSum_isNotSkipped() {
        // 3 个各 10 亿的合法会话：SUM=30 亿 > 20 亿单会话上限 → 走复核，但没有异常会话、也没超日上限 → 原样采信
        stubSum(3_000_000_000L, 30_000_000L);
        stubSlices(
                new Object[]{1L, 1_000_000_000L, 10_000_000L},
                new Object[]{2L, 1_000_000_000L, 10_000_000L},
                new Object[]{3L, 1_000_000_000L, 10_000_000L});

        DailySummary row = aggregateU1();

        assertThat(row.getTotalInputTokens()).isEqualTo(3_000_000_000L);
        assertThat(row.getTotalOutputTokens()).isEqualTo(30_000_000L);
    }

    @Test
    void individuallyPlausibleSessionsSummingPastDailyCap_areClamped() {
        stubSum(7_600_000_000L, 4L);
        stubSlices(
                new Object[]{1L, 1_900_000_000L, 1L},
                new Object[]{2L, 1_900_000_000L, 1L},
                new Object[]{3L, 1_900_000_000L, 1L},
                new Object[]{4L, 1_900_000_000L, 1L});

        DailySummary row = aggregateU1();

        assertThat(row.getTotalInputTokens()).as("封顶 50 亿").isEqualTo(5_000_000_000L);
        assertThat(row.getTotalOutputTokens()).isEqualTo(4L);
    }

    @Test
    void modelTop_ignoresThePoisonedSession() {
        stubSum(2_070_054_000_000L + 100_000_000L, 10_246_000_000L + 1_000_000L);
        stubSlices(
                new Object[]{11L, 2_070_054_000_000L, 10_246_000_000L},
                new Object[]{12L, 100_000_000L, 1_000_000L});
        when(sessionRepository.findModelTokenSlicesByUserAndLastActivityWindowAndTargetTypeIn(
                eq("U1"), any(), any(), any()))
                .thenReturn(List.of(
                        new Object[]{"poisoned-model", 2_070_054_000_000L, 10_246_000_000L},
                        new Object[]{"real-model", 100_000_000L, 1_000_000L}));

        DailySummary row = aggregateU1();

        assertThat(row.getActiveModelTop()).isEqualTo("real-model");
        assertThat(row.getAiModelsTop3()).isEqualTo("[\"real-model\"]");
    }

    @Test
    void negativeSessionValues_areSkippedToo() {
        // SUM 被负值拉成负数 → 同样走复核
        stubSum(-500L, 10L);
        stubSlices(
                new Object[]{1L, -1_000L, 5L},
                new Object[]{2L, 500L, 5L});

        DailySummary row = aggregateU1();

        assertThat(row.getTotalInputTokens()).isEqualTo(500L);
        assertThat(row.getTotalOutputTokens()).isEqualTo(5L);
    }
}
