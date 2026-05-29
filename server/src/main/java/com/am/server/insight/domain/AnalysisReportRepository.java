package com.am.server.insight.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 窗口级分析报告 Repository
 * gz
 */
public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, Long> {

    Optional<AnalysisReport> findByWindowFromAndWindowTo(LocalDate from, LocalDate to);

    /** 历史报告列表（创建时间倒序），用于"已生成过的窗口"侧栏。 */
    List<AnalysisReport> findTop50ByOrderByCreatedTimeDesc();

    /** 启动时收口：进程重启后仍为 pending/running 的报告无法继续执行，需标记失败 */
    List<AnalysisReport> findByStatusIn(Collection<String> statuses);
}
