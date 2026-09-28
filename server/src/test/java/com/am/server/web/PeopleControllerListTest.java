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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 员工数据列表：窗内消息统计（问答比 / Slash 合计）是整窗 message 扫描，同一 (窗口, activeTypes) 共享一份。
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
