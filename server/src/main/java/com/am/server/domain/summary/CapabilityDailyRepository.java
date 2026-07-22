package com.am.server.domain.summary;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 能力使用日聚合 Repository
 * gz
 */
public interface CapabilityDailyRepository extends JpaRepository<CapabilityDaily, Long> {

    /** CapabilityDailyAggregator 整日重算的清场步：bulk delete，须在事务内调用。 */
    @Modifying
    @Query("DELETE FROM CapabilityDaily c WHERE c.workDate = :workDate")
    int deleteByWorkDate(@Param("workDate") LocalDate workDate);

    List<CapabilityDaily> findByWorkDate(LocalDate workDate);

    // ---- /capability 窗口聚合（全部只打本表；session_count 为跨日求和的近似值，见实体注释）----

    /**
     * 排行（汇总粒度 sub_item=''）：按 (item, kind) 聚合。返回
     * {@code [item, kind, SUM(invoke), COUNT(DISTINCT user), SUM(session)]}。
     * Skill tab 传 kinds=(skill, nl_skill) 由服务层并成显式/NL 两列。
     */
    @Query("""
        SELECT c.item, c.kind, SUM(c.invokeCount), COUNT(DISTINCT c.userCode), SUM(c.sessionCount)
        FROM CapabilityDaily c
        WHERE c.workDate BETWEEN :from AND :to
          AND c.kind IN :kinds AND c.subItem = ''
        GROUP BY c.item, c.kind
        """)
    List<Object[]> rankRollupByItemKind(
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("kinds") Collection<String> kinds);

    /**
     * 排行的跨 kind 精确使用人数：按 item 去重 user（显式 + NL 的并集，避免两 kind 相加重复计人）。
     * 返回 {@code [item, COUNT(DISTINCT user)]}。
     */
    @Query("""
        SELECT c.item, COUNT(DISTINCT c.userCode)
        FROM CapabilityDaily c
        WHERE c.workDate BETWEEN :from AND :to
          AND c.kind IN :kinds AND c.subItem = ''
        GROUP BY c.item
        """)
    List<Object[]> rankUserCountByItem(
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("kinds") Collection<String> kinds);

    /**
     * 排行二级明细（sub_item &lt;&gt; ''）：mcp=server×tool、plugin_ns=namespace×技能。
     * 返回 {@code [item, subItem, SUM(invoke), SUM(session)]}。
     */
    @Query("""
        SELECT c.item, c.subItem, SUM(c.invokeCount), SUM(c.sessionCount)
        FROM CapabilityDaily c
        WHERE c.workDate BETWEEN :from AND :to
          AND c.kind = :kind AND c.subItem <> ''
        GROUP BY c.item, c.subItem
        """)
    List<Object[]> rankDetailByItemSubItem(
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("kind") String kind);

    /** 趋势：按 (work_date, kind) 聚合调用次数。返回 {@code [workDate, kind, SUM(invoke)]}，date 升序。 */
    @Query("""
        SELECT c.workDate, c.kind, SUM(c.invokeCount)
        FROM CapabilityDaily c
        WHERE c.workDate BETWEEN :from AND :to
          AND c.kind IN :kinds AND c.subItem = ''
        GROUP BY c.workDate, c.kind
        ORDER BY c.workDate
        """)
    List<Object[]> trendByDateKind(
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("kinds") Collection<String> kinds);

    /** 人×一级维度覆盖矩阵：返回 {@code [userCode, item, SUM(invoke)]}。 */
    @Query("""
        SELECT c.userCode, c.item, SUM(c.invokeCount)
        FROM CapabilityDaily c
        WHERE c.workDate BETWEEN :from AND :to
          AND c.kind IN :kinds AND c.subItem = ''
        GROUP BY c.userCode, c.item
        """)
    List<Object[]> matrixByUserItem(
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("kinds") Collection<String> kinds);

    /**
     * 按人下钻：单用户窗口内全部能力行（含二级明细行）。
     * 返回 {@code [kind, item, subItem, SUM(invoke), SUM(session)]}，按调用次数降序。
     */
    @Query("""
        SELECT c.kind, c.item, c.subItem, SUM(c.invokeCount), SUM(c.sessionCount)
        FROM CapabilityDaily c
        WHERE c.workDate BETWEEN :from AND :to
          AND c.userCode = :userCode
        GROUP BY c.kind, c.item, c.subItem
        ORDER BY SUM(c.invokeCount) DESC
        """)
    List<Object[]> userDrilldown(
            @Param("userCode") String userCode,
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
