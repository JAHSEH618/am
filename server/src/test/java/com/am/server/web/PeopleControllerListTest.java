package com.am.server.web;

import com.am.server.aggregator.DailySummaryAggregator;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.employee.Employee;
import com.am.server.domain.employee.EmployeeRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.summary.DailySummaryRepository;
import com.am.server.insight.domain.AnalysisReportRepository;
import com.am.server.insight.domain.AnalysisReportUserRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.ActiveTargetTypesProvider;
import com.am.server.web.dto.PeopleSummaryDto;
import com.am.server.web.support.SlashCommandStatSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 员工数据列表：窗内消息统计（问答比 / Slash 合计）是整窗 message 扫描，同一 (窗口, activeTypes) 共享一份；
 * 等级徽章只走投影查询，不再拉整行报告 / 员工画像实体。
 */
class PeopleControllerListTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO = LocalDate.of(2026, 9, 27);

    private final DailySummaryRepository summaryRepository = mock(DailySummaryRepository.class);
    private final AiSessionMessageRepository messageRepository = mock(AiSessionMessageRepository.class);
    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final ActiveTargetTypesProvider activeTargetTypesProvider = mock(ActiveTargetTypesProvider.class);
    private final AnalysisReportRepository analysisReportRepository = mock(AnalysisReportRepository.class);
    private final AnalysisReportUserRepository analysisReportUserRepository =
            mock(AnalysisReportUserRepository.class);

    private final PeopleController controller = new PeopleController(
            summaryRepository, messageRepository, mock(AiSessionEventRepository.class), employeeRepository,
            mock(EmployeeDisplayService.class), mock(DailySummaryAggregator.class), activeTargetTypesProvider,
            mock(GitCommitRepository.class), new ObjectMapper(), mock(SlashCommandStatSupport.class),
            analysisReportRepository, analysisReportUserRepository);

    @BeforeEach
    void seed() {
        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor", "claude"));
        when(employeeRepository.findByStatusOrderByUserCodeAsc(Employee.STATUS_ACTIVE))
                .thenReturn(List.of(employee("u1"), employee("u2")));
        when(messageRepository.aggregatePeopleMessageStatsByUserInWindow(any(), any(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"u1", 4L, 8L, 3L}));
    }

    @Test
    void windowMessageStatsAreSharedAcrossRequestsForTheSameWindow() {
        List<PeopleSummaryDto> first = controller.list(FROM, TO).getData();
        List<PeopleSummaryDto> second = controller.list(FROM, TO).getData();

        verify(messageRepository, times(1))
                .aggregatePeopleMessageStatsByUserInWindow(any(), any(), anyCollection());
        for (List<PeopleSummaryDto> out : List.of(first, second)) {
            PeopleSummaryDto u1 = byCode(out, "u1");
            assertThat(u1.getUserMessageCount()).isEqualTo(4);
            assertThat(u1.getAssistantMessageCount()).isEqualTo(8);
            assertThat(u1.getToolCallCountTotal()).isEqualTo(3);
            assertThat(byCode(out, "u2").getUserMessageCount()).as("无消息行的员工走零值").isZero();
        }
    }

    @Test
    void differentWindowOrActiveTypesRecompute() {
        controller.list(FROM, TO);
        controller.list(FROM, TO.minusDays(1));
        verify(messageRepository, times(2))
                .aggregatePeopleMessageStatsByUserInWindow(any(), any(), anyCollection());

        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("claude", "cursor"));
        controller.list(FROM, TO);
        verify(messageRepository, times(2))
                .aggregatePeopleMessageStatsByUserInWindow(any(), any(), anyCollection());

        when(activeTargetTypesProvider.getActiveTypes()).thenReturn(List.of("cursor"));
        controller.list(FROM, TO);
        verify(messageRepository, times(3))
                .aggregatePeopleMessageStatsByUserInWindow(any(), any(), anyCollection());
    }

    @Test
    void gradesComeFromProjectionsOfTheLatestCompletedReport() {
        when(analysisReportRepository.findReportWindowsByStatus(eq("completed"), any(Pageable.class)))
                .thenReturn(List.of(new Window(9L, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20))));
        when(analysisReportUserRepository.findGradeBadgesByReportIdAndUserCodeIn(eq(9L), anyCollection()))
                .thenReturn(List.of(
                        new Badge("u1", "A", new BigDecimal("88.00"), "high"),
                        new Badge("u2", null, null, null)));

        List<PeopleSummaryDto> out = controller.list(FROM, TO).getData();

        PeopleSummaryDto u1 = byCode(out, "u1");
        assertThat(u1.getCompositeGrade()).isEqualTo("A");
        assertThat(u1.getCompositeScore()).isEqualByComparingTo("88.00");
        assertThat(u1.getCompositeConfidence()).isEqualTo("high");
        assertThat(u1.getGradeWindow()).isEqualTo("2026-09-14 ~ 2026-09-20");
        assertThat(byCode(out, "u2").getCompositeGrade()).as("无等级不回填").isNull();
        assertThat(byCode(out, "u2").getGradeWindow()).isNull();
        verify(analysisReportRepository, never()).findFirstByStatusOrderByWindowToDescIdDesc(anyString());
        verify(analysisReportUserRepository, never()).findByReportIdAndUserCodeIn(any(), anyCollection());
    }

    private record Window(Long getId, LocalDate getWindowFrom, LocalDate getWindowTo)
            implements AnalysisReportRepository.ReportWindow {
    }

    private record Badge(String getUserCode, String getCompositeGrade, BigDecimal getCompositeScore,
                         String getCompositeConfidence) implements AnalysisReportUserRepository.GradeBadge {
    }

    private static PeopleSummaryDto byCode(List<PeopleSummaryDto> out, String code) {
        return out.stream().filter(d -> code.equals(d.getUserCode())).findFirst().orElseThrow();
    }

    private static Employee employee(String code) {
        Employee e = new Employee();
        e.setUserCode(code);
        e.setUserName(code);
        e.setStatus(Employee.STATUS_ACTIVE);
        return e;
    }
}
