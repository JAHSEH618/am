package com.am.server.insight.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** People 页等级徽章：取最近一份 completed 报告（窗口截止日最新）。 */
    Optional<AnalysisReport> findFirstByStatusOrderByWindowToDescIdDesc(String status);

    /** 等级徽章只需报告 id 与窗口；不拉 team_* 大 JSON / error_text。 */
    interface ReportWindow {
        Long getId();

        LocalDate getWindowFrom();

        LocalDate getWindowTo();
    }

    /**
     * People 页等级徽章：同 {@link #findFirstByStatusOrderByWindowToDescIdDesc} 的排序，只投影三列；
     * 取最近一份传 {@code PageRequest.of(0, 1)}。
     */
    @Query("""
        SELECT r.id AS id, r.windowFrom AS windowFrom, r.windowTo AS windowTo
        FROM AnalysisReport r
        WHERE r.status = :status
        ORDER BY r.windowTo DESC, r.id DESC
        """)
    List<ReportWindow> findReportWindowsByStatus(@Param("status") String status, Pageable pageable);
}
