package com.am.server.insight.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 窗口×用户画像 Repository
 * gz
 */
public interface AnalysisReportUserRepository extends JpaRepository<AnalysisReportUser, Long> {

    /** 按报告取所有员工数据，前端列表用。综合分位高的排前面。 */
    List<AnalysisReportUser> findByReportIdOrderByCompositePercentileDesc(Long reportId);

    Optional<AnalysisReportUser> findByReportIdAndUserCode(Long reportId, String userCode);

    /** 重新生成报告时先清空旧用户画像。 */
    @Transactional
    void deleteByReportId(Long reportId);
}
