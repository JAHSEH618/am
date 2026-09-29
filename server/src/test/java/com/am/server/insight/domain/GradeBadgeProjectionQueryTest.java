package com.am.server.insight.domain;

import com.am.server.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * People 页等级徽章改用投影：报告只取 (id, 窗口)，员工行只取 grade / score / confidence。
 * 钉住两点——选中的报告与原 {@code findFirstByStatusOrderByWindowToDescIdDesc} 同一份，
 * 投影别名逐列映射正确（写错别名只会在运行期静默变 null）。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class GradeBadgeProjectionQueryTest {

    @Autowired
    private AnalysisReportRepository reportRepository;

    @Autowired
    private AnalysisReportUserRepository reportUserRepository;

    @Test
    void latestCompletedReportWindowMatchesTheEntityQuery() {
        // 远未来的窗口：保证压过开发库里任何真实报告
        AnalysisReport older = reportRepository.save(report(LocalDate.of(2999, 1, 1), "completed"));
        AnalysisReport newest = reportRepository.save(report(LocalDate.of(2999, 2, 1), "completed"));
        reportRepository.save(report(LocalDate.of(2999, 3, 1), "running"));
        reportRepository.flush();

        List<AnalysisReportRepository.ReportWindow> windows =
                reportRepository.findReportWindowsByStatus("completed", PageRequest.of(0, 1));

        assertThat(windows).hasSize(1);
        AnalysisReportRepository.ReportWindow w = windows.get(0);
        assertThat(w.getId()).isEqualTo(newest.getId())
                .isEqualTo(reportRepository.findFirstByStatusOrderByWindowToDescIdDesc("completed")
                        .orElseThrow().getId())
                .isNotEqualTo(older.getId());
        assertThat(w.getWindowFrom()).isEqualTo(newest.getWindowFrom());
        assertThat(w.getWindowTo()).isEqualTo(newest.getWindowTo());
    }

    @Test
    void gradeBadgesProjectExactlyTheBadgeColumns() {
        AnalysisReport r = reportRepository.save(report(LocalDate.of(2999, 5, 1), "completed"));
        reportUserRepository.save(user(r, "gb-u1", "A", new BigDecimal("87.50"), "high"));
        reportUserRepository.save(user(r, "gb-u2", null, null, null));
        reportUserRepository.save(user(r, "gb-u3", "C", new BigDecimal("40.00"), "low"));
        reportUserRepository.flush();

        Map<String, AnalysisReportUserRepository.GradeBadge> byCode = reportUserRepository
                .findGradeBadgesByReportIdAndUserCodeIn(r.getId(), List.of("gb-u1", "gb-u2", "gb-nobody"))
                .stream().collect(Collectors.toMap(AnalysisReportUserRepository.GradeBadge::getUserCode, b -> b));

        assertThat(byCode.keySet()).containsExactlyInAnyOrder("gb-u1", "gb-u2");
        assertThat(byCode.get("gb-u1").getCompositeGrade()).isEqualTo("A");
        assertThat(byCode.get("gb-u1").getCompositeScore()).isEqualByComparingTo("87.50");
        assertThat(byCode.get("gb-u1").getCompositeConfidence()).isEqualTo("high");
        assertThat(byCode.get("gb-u2").getCompositeGrade()).isNull();
    }

    private static AnalysisReport report(LocalDate windowTo, String status) {
        AnalysisReport r = new AnalysisReport();
        r.setWindowFrom(windowTo.minusDays(6));
        r.setWindowTo(windowTo);
        r.setReportVersion("test");
        r.setRubricVersion("test");
        r.setStatus(status);
        return r;
    }

    private static AnalysisReportUser user(AnalysisReport r, String code, String grade, BigDecimal score,
                                           String confidence) {
        AnalysisReportUser u = new AnalysisReportUser();
        u.setReportId(r.getId());
        u.setUserCode(code);
        u.setSessionCount(1);
        u.setAiActiveHours(BigDecimal.ONE);
        u.setTotalTokens(1L);
        u.setAiCommitCount(0);
        u.setAiLinesAdded(0L);
        u.setCompositeGrade(grade);
        u.setCompositeScore(score);
        u.setCompositeConfidence(confidence);
        return u;
    }
}
