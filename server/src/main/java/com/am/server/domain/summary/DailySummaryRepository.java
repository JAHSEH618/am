package com.am.server.domain.summary;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 员工日汇总 Repository
 * gz
 */
public interface DailySummaryRepository extends JpaRepository<DailySummary, Long> {

    /** Aggregator 的 upsert 锚点：(user_code, work_date) 唯一 */
    Optional<DailySummary> findByUserCodeAndWorkDate(String userCode, LocalDate workDate);

    List<DailySummary> findByWorkDate(LocalDate workDate);

    List<DailySummary> findByUserCodeAndWorkDateBetweenOrderByWorkDateDesc(
            String userCode, LocalDate from, LocalDate to);

    /** Phase 2 列表用：拉窗口期内所有用户的所有日汇总，由 service 内存分组 */
    List<DailySummary> findByWorkDateBetweenOrderByWorkDateDesc(LocalDate from, LocalDate to);

    /** 报告聚合：窗口内按 user_code 汇总 ai_active_seconds_union。返回 [userCode, sum]。 */
    @Query("""
        SELECT s.userCode, COALESCE(SUM(s.aiActiveSecondsUnion), 0)
        FROM DailySummary s
        WHERE s.workDate BETWEEN :from AND :to
          AND s.userCode IN :userCodes
        GROUP BY s.userCode
        """)
    List<Object[]> sumUnionSecondsGroupedByUserInWorkDateRange(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("userCodes") Collection<String> userCodes);

    /**
     * v2.11：某日曾有过「非零 AI 指标」的员工工号。
     * <p>{@link com.am.server.aggregator.DailySummaryAggregator#aggregate} 在收窄
     * event 口径（排除 invalid_reason 会话）后，原先只靠「当日有 event」拉用户会漏掉
     * 「只剩无效会话事件」的人——他们的 daily_summary 仍持旧值。把这类用户并入重算集，
     * persist 会用新口径覆盖为 0。
     */
    @Query("""
        SELECT DISTINCT s.userCode FROM DailySummary s
        WHERE s.workDate = :workDate
          AND (
              s.aiActiveSeconds > 0 OR s.aiActiveSecondsUnion > 0 OR s.aiSessionCount > 0
              OR s.aiMessageCount > 0 OR s.totalInputTokens > 0 OR s.totalOutputTokens > 0
              OR s.toolCallCount > 0 OR s.aiThinkingSeconds > 0 OR s.aiRetryCount > 0
              OR s.aiFirstResponseAvgMs > 0 OR s.aiCommitCount > 0
          )
        """)
    List<String> findUserCodesWithNonZeroAiStatsOnDate(@Param("workDate") LocalDate workDate);

    /**
     * 员工数据访问触发的 view-time 过期检测用：返回 [from, to] 区间内每个 work_date 上
     * 全员 daily_summary.updated_time 的最大值。
     * <p>用法：跟同窗口的 ai_session_event.MAX(event_time) per day 比对——
     * 若某天事件最大时间 &gt; 该天 daily_summary 最大 updated_time，说明 ingest 之后
     * aggregator 还没追上，触发 ensureFresh 同步重聚（带 60s TTL 节流）。
     * <p>返回 {@code [work_date, max_updated_time]}；JPA 会以 [java.sql.Date, java.sql.Timestamp] 形式给出。
     */
    @Query("""
        SELECT s.workDate, MAX(s.updatedTime)
        FROM DailySummary s
        WHERE s.workDate BETWEEN :from AND :to
        GROUP BY s.workDate
        """)
    List<Object[]> findMaxUpdatedTimePerDay(LocalDate from, LocalDate to);

    /**
     * Token 走势：窗口内全员按 work_date 汇总 input/output token。
     * 返回 {@code [work_date, sum_input, sum_output]}，date 升序；走 idx_work_date。
     */
    @Query("""
        SELECT s.workDate, COALESCE(SUM(s.totalInputTokens), 0), COALESCE(SUM(s.totalOutputTokens), 0)
        FROM DailySummary s
        WHERE s.workDate BETWEEN :from AND :to
        GROUP BY s.workDate
        ORDER BY s.workDate
        """)
    List<Object[]> sumTokensGroupedByWorkDate(
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
