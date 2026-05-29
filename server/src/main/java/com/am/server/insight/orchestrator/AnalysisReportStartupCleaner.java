package com.am.server.insight.orchestrator;

import com.am.server.insight.domain.AnalysisReport;
import com.am.server.insight.domain.AnalysisReportRepository;
import com.am.server.insight.domain.ReportStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 进程重启后，{@code @Async} 报告任务不会自动恢复，但库里的 {@code pending}/{@code running}
 * 行仍存在，前端会一直显示「生成中」。启动时将此类孤儿任务收口为 {@code failed}，提示管理员重跑。
 *
 * <p>顺序靠后：等数据源与 JPA 就绪后再执行。
 * gz
 */
@Slf4j
@Component
@Order(5000)
@RequiredArgsConstructor
public class AnalysisReportStartupCleaner implements ApplicationRunner {

    private static final String INTERRUPTED_MSG =
            "服务已重启，此前的报告生成任务已中断。请重新点击「生成报告」或「强制重跑」。";

    private final AnalysisReportRepository reportRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<AnalysisReport> orphans = reportRepository.findByStatusIn(
                List.of(ReportStatus.PENDING, ReportStatus.RUNNING));
        if (orphans.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (AnalysisReport r : orphans) {
            String prev = r.getStatus();
            r.setStatus(ReportStatus.FAILED);
            r.setErrorText(INTERRUPTED_MSG);
            r.setCompletedTime(now);
            reportRepository.save(r);
            log.warn("orphaned analysis report marked failed: id={} window={}..{} previousStatus={}",
                    r.getId(), r.getWindowFrom(), r.getWindowTo(), prev);
        }
        log.info("AnalysisReportStartupCleaner: interrupted {} orphaned report job(s)", orphans.size());
    }
}
