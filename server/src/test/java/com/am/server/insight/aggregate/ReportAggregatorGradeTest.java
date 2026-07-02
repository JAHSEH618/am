package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.summary.DailySummaryRepository;
import com.am.server.insight.domain.AiSessionAudit;
import com.am.server.insight.domain.AnalysisReport;
import com.am.server.insight.domain.AnalysisReportUser;
import com.am.server.insight.domain.AnalysisReportUserRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class ReportAggregatorGradeTest {

    private final DailySummaryRepository dailySummaryRepository = mock(DailySummaryRepository.class);
    private final GitCommitRepository commitRepository = mock(GitCommitRepository.class);
    private final AiSessionMessageRepository messageRepository = mock(AiSessionMessageRepository.class);
    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final ActiveTargetTypesProvider activeTargetTypesProvider = mock(ActiveTargetTypesProvider.class);
    private final HighlightSessionPicker highlightPicker = new HighlightSessionPicker();
    private final WatchlistEvaluator watchlistEvaluator = mock(WatchlistEvaluator.class);
    private final AnalysisReportUserRepository userRepository = mock(AnalysisReportUserRepository.class);

    private final ReportAggregator aggregator = new ReportAggregator(
            dailySummaryRepository, commitRepository, messageRepository, eventRepository,
            activeTargetTypesProvider, highlightPicker, watchlistEvaluator, userRepository);

    private static AiSession session(long id, String user, LocalDateTime t) {
        AiSession s = new AiSession();
        s.setId(id);
        s.setUserCode(user);
        s.setTargetType("claude");
        s.setStartedAt(t);
        s.setLastActivity(t.plusMinutes(30));
        s.setInputTokens(1000L);
        s.setOutputTokens(500L);
        s.setTotalMessages(20);
        return s;
    }

    private static AiSessionAudit audit(long sessionId, int difficulty, String outcome, String mode, int cap) {
        AiSessionAudit a = new AiSessionAudit();
        a.setAiSessionId(sessionId);
        a.setDifficulty(difficulty);
        a.setOutcome(outcome);
        a.setMode(mode);
        a.setCapProblemDecomposition(cap);
        a.setCapContextManagement(cap);
        a.setCapDebuggingSkill(cap);
        a.setCapToolOrchestration(cap);
        a.setCapSelfCorrection(cap);
        a.setJudgeDisagreement(0);
        return a;
    }

    @Test
    void gradesAndBreakdownArePersisted() {
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("claude"));
        when(watchlistEvaluator.evaluate(any(), any())).thenReturn(List.of());
        // 注意：List.of(new Object[]{...}) 会被 varargs 展开，必须显式指定元素类型
        when(dailySummaryRepository.sumUnionSecondsGroupedByUserInWorkDateRange(any(), any(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"u1", 36000L}));
        when(dailySummaryRepository.sumRetryAndToolCallsGroupedByUserInWorkDateRange(any(), any(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"u1", 4L, 120L}));
        when(commitRepository.findByUserCodeInAndCommitTimeBetween(any(), any(), any())).thenReturn(List.of());
        when(messageRepository.sumStoredSlashCountsByUserForSessions(any())).thenReturn(List.of());
        when(messageRepository.loadStoredSlashHitsJsonForSessions(any())).thenReturn(List.of());
        when(eventRepository.aggregateModelsForUserInWindowAndTargetTypeIn(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(eventRepository.aggregateProjectsForUserInWindowAndTargetTypeIn(any(), any(), any(), any()))
                .thenReturn(List.of());

        AnalysisReport report = new AnalysisReport();
        report.setId(1L);
        report.setWindowFrom(LocalDate.of(2026, 6, 1));
        report.setWindowTo(LocalDate.of(2026, 6, 30));

        // 25 个已审计会话：难度4、completed、leverage、五维=4 → 有等级、normal 置信度
        List<AiSession> sessions = new ArrayList<>();
        Map<Long, AiSessionAudit> audits = new HashMap<>();
        for (long i = 1; i <= 25; i++) {
            sessions.add(session(i, "u1", LocalDateTime.of(2026, 6, 2, 9, 0).plusHours(i)));
            audits.put(i, audit(i, 4, "completed", "leverage", 4));
        }

        ReportAggregator.AggregateOutcome outcome = aggregator.aggregate(report, sessions, audits);

        UserMetrics m = outcome.userMetrics().get("u1");
        assertNotNull(m.getCompositeScore());
        assertNotNull(m.getCompositeGrade());
        assertEquals("normal", m.getCompositeConfidence());
        assertEquals(1.0, m.getHighDifficultyCompletedRatio(), 0.001);
        assertEquals(4, m.getRetryCount());
        assertEquals(120, m.getToolCallCount());
        assertTrue(m.getRetryPerActiveHour() > 0);
        assertNotNull(m.getCompositeBreakdownJson());
        assertTrue(m.getCompositeBreakdownJson().contains("\"formula_version\":\"v2\""));

        ArgumentCaptor<List<AnalysisReportUser>> captor = ArgumentCaptor.forClass(List.class);
        verify(userRepository).saveAll(captor.capture());
        AnalysisReportUser row = captor.getValue().get(0);
        assertEquals(m.getCompositeGrade(), row.getCompositeGrade());
        assertEquals("normal", row.getCompositeConfidence());
        assertNotNull(row.getCompositeBreakdownJson());
        assertEquals(4, row.getRetryCount());
        assertEquals(120, row.getToolCallCount());

        assertNotNull(outcome.teamPayload().teamGradeDistJson);
        assertTrue(outcome.teamPayload().teamGradeDistJson.contains(m.getCompositeGrade()));
    }

    @Test
    void insufficientUserGetsNoGrade() {
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("claude"));
        when(watchlistEvaluator.evaluate(any(), any())).thenReturn(List.of());
        when(dailySummaryRepository.sumUnionSecondsGroupedByUserInWorkDateRange(any(), any(), anyCollection()))
                .thenReturn(List.of());
        when(dailySummaryRepository.sumRetryAndToolCallsGroupedByUserInWorkDateRange(any(), any(), anyCollection()))
                .thenReturn(List.of());
        when(commitRepository.findByUserCodeInAndCommitTimeBetween(any(), any(), any())).thenReturn(List.of());
        when(messageRepository.sumStoredSlashCountsByUserForSessions(any())).thenReturn(List.of());
        when(messageRepository.loadStoredSlashHitsJsonForSessions(any())).thenReturn(List.of());

        AnalysisReport report = new AnalysisReport();
        report.setId(2L);
        report.setWindowFrom(LocalDate.of(2026, 6, 1));
        report.setWindowTo(LocalDate.of(2026, 6, 30));

        List<AiSession> sessions = new ArrayList<>();
        Map<Long, AiSessionAudit> audits = new HashMap<>();
        for (long i = 1; i <= 5; i++) {   // 5 < 10 → insufficient
            sessions.add(session(i, "u2", LocalDateTime.of(2026, 6, 2, 9, 0).plusHours(i)));
            audits.put(i, audit(i, 3, "completed", "leverage", 3));
        }

        ReportAggregator.AggregateOutcome outcome = aggregator.aggregate(report, sessions, audits);
        UserMetrics m = outcome.userMetrics().get("u2");
        assertTrue(m.isInsufficientData());
        assertNull(m.getCompositeGrade());
        assertNull(m.getCompositeBreakdownJson());
    }
}
