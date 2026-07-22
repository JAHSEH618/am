package com.am.server.domain.git;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * commit → AI 产出归因 Repository
 * gz
 */
public interface GitCommitAttributionRepository extends JpaRepository<GitCommitAttribution, Long> {

    /** 引擎重算的清场步：按 commit 批量删除旧归因行，须在事务内调用。 */
    @Modifying
    @Query("DELETE FROM GitCommitAttribution a WHERE a.commitId IN :commitIds")
    int deleteByCommitIdIn(@Param("commitIds") Collection<Long> commitIds);

    /**
     * 渗透率（北极星）迁移后口径：预计算 A∪B 行数占比，单表聚合替代原全窗口 JOIN。
     * 返回单行 [assisted_lines, total_lines]（merge commit 建表期即被排除，分母天然干净）。
     */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN a.tier <> 'NONE' THEN a.linesAdded ELSE 0 END), 0),
               COALESCE(SUM(a.linesAdded), 0)
        FROM GitCommitAttribution a
        WHERE a.commitTime >= :from
        """)
    List<Object[]> penetrationLines(@Param("from") LocalDateTime from);

    /** /attribution 趋势：按 (日期, tier) 聚合。返回 [date, tier, commits, linesAdded, MAX(backfilled)]。 */
    @Query("""
        SELECT FUNCTION('DATE', a.commitTime), a.tier, COUNT(a),
               COALESCE(SUM(a.linesAdded), 0), MAX(a.backfilled)
        FROM GitCommitAttribution a
        WHERE a.commitTime >= :from AND a.commitTime < :to
        GROUP BY FUNCTION('DATE', a.commitTime), a.tier
        ORDER BY FUNCTION('DATE', a.commitTime)
        """)
    List<Object[]> trendByDayTier(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * /attribution 交叉透视底料：按全部四维 × tier 预折叠（组合数远小于 commit 数），
     * 行/列维度选择与组合筛选由服务层在内存完成——避免按维度组合动态拼 SQL。
     * 返回 [userCode, projectName, targetType, model, tier, commits, linesAdded, linesDeleted]。
     */
    @Query("""
        SELECT a.userCode, a.projectName, a.targetType, a.model, a.tier,
               COUNT(a), COALESCE(SUM(a.linesAdded), 0), COALESCE(SUM(a.linesDeleted), 0)
        FROM GitCommitAttribution a
        WHERE a.commitTime >= :from AND a.commitTime < :to
        GROUP BY a.userCode, a.projectName, a.targetType, a.model, a.tier
        """)
    List<Object[]> aggregateForPivot(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * commit 明细抽屉：联 git_commit 取 subject / hash。过滤参数传 null 表示不限。
     * 返回 [GitCommitAttribution, messageSubject, commitHash]，commit_time 降序。
     */
    @Query(value = """
        SELECT a, c.messageSubject, c.commitHash
        FROM GitCommitAttribution a, GitCommit c
        WHERE c.id = a.commitId
          AND a.commitTime >= :from AND a.commitTime < :to
          AND (:userCode IS NULL OR a.userCode = :userCode)
          AND (:projectName IS NULL OR a.projectName = :projectName)
          AND (:targetType IS NULL OR a.targetType = :targetType)
          AND (:model IS NULL OR a.model = :model)
          AND (:tier IS NULL OR a.tier = :tier)
        ORDER BY a.commitTime DESC
        """,
        countQuery = """
        SELECT COUNT(a)
        FROM GitCommitAttribution a
        WHERE a.commitTime >= :from AND a.commitTime < :to
          AND (:userCode IS NULL OR a.userCode = :userCode)
          AND (:projectName IS NULL OR a.projectName = :projectName)
          AND (:targetType IS NULL OR a.targetType = :targetType)
          AND (:model IS NULL OR a.model = :model)
          AND (:tier IS NULL OR a.tier = :tier)
        """)
    Page<Object[]> findCommitDetails(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("userCode") String userCode,
            @Param("projectName") String projectName,
            @Param("targetType") String targetType,
            @Param("model") String model,
            @Param("tier") String tier,
            Pageable pageable);
}
