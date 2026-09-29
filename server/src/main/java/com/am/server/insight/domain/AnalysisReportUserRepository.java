package com.am.server.insight.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
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

    List<AnalysisReportUser> findByReportIdAndUserCodeIn(Long reportId, Collection<String> userCodes);

    /** 等级徽章四列：People 列表一次几百人，整行要带十来个 JSON / 画像列，只为读等级。 */
    interface GradeBadge {
        String getUserCode();

        String getCompositeGrade();

        BigDecimal getCompositeScore();

        String getCompositeConfidence();
    }

    /** People 页等级徽章：同 {@link #findByReportIdAndUserCodeIn}，只投影徽章用到的列（走 uk_report_user）。 */
    @Query("""
        SELECT u.userCode AS userCode, u.compositeGrade AS compositeGrade,
               u.compositeScore AS compositeScore, u.compositeConfidence AS compositeConfidence
        FROM AnalysisReportUser u
        WHERE u.reportId = :reportId AND u.userCode IN :userCodes
        """)
    List<GradeBadge> findGradeBadgesByReportIdAndUserCodeIn(
            @Param("reportId") Long reportId, @Param("userCodes") Collection<String> userCodes);

    /** 重新生成报告时先清空旧用户画像。 */
    @Transactional
    void deleteByReportId(Long reportId);
}
