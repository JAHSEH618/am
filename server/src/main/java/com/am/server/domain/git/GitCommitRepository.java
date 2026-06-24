package com.am.server.domain.git;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Git 提交流水 Repository
 * gz
 */
public interface GitCommitRepository extends JpaRepository<GitCommit, Long> {

    Optional<GitCommit> findByRepoUrlAndCommitHash(String repoUrl, String commitHash);

    /** AI 渗透率 / 日报用：某个时间窗内某员工的全部提交 */
    List<GitCommit> findByUserCodeAndCommitTimeBetween(
            String userCode, LocalDateTime from, LocalDateTime to);

    List<GitCommit> findByUserCodeInAndCommitTimeBetween(
            Collection<String> userCodes, LocalDateTime from, LocalDateTime to);

    /** AI 渗透率 / 日报用：某个时间窗内全部提交 */
    List<GitCommit> findByCommitTimeBetween(LocalDateTime from, LocalDateTime to);

    long countByCommitTimeBetween(LocalDateTime from, LocalDateTime to);

    /**
     * 与项目透视窗口一致：<code>[t0, t1)</code> 半开区间（<code>t1</code> 为结束日翌日 0 点）。
     */
    @Query("SELECT COUNT(g) FROM GitCommit g WHERE g.repoUrl = :repoUrl AND g.commitTime >= :t0 AND g.commitTime < :t1")
    long countByRepoUrlAndCommitWindow(
            @Param("repoUrl") String repoUrl, @Param("t0") LocalDateTime t0, @Param("t1") LocalDateTime t1);

    /**
     * 批量：按仓库 URL 聚合窗口内提交条数（与 {@link #countByRepoUrlAndCommitWindow} 同期口）。
     */
    @Query("SELECT g.repoUrl, COUNT(g) FROM GitCommit g WHERE g.commitTime >= :t0 AND g.commitTime < :t1 GROUP BY g.repoUrl")
    List<Object[]> countGroupedByRepoUrlInCommitWindow(@Param("t0") LocalDateTime t0, @Param("t1") LocalDateTime t1);

    /**
     * 项目透视弹框：某仓库在窗口 <code>[t0, t1)</code> 内的提交，按时间倒序。
     */
    @Query("SELECT g FROM GitCommit g WHERE g.repoUrl = :repoUrl AND g.commitTime >= :t0 AND g.commitTime < :t1 ORDER BY g.commitTime DESC")
    List<GitCommit> findByRepoUrlAndCommitWindowOrderByCommitTimeDesc(
            @Param("repoUrl") String repoUrl,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            Pageable pageable);

    @Query("SELECT COUNT(g) FROM GitCommit g WHERE g.userCode = :userCode AND g.commitTime >= :t0 AND g.commitTime < :t1")
    long countByUserCodeAndCommitWindow(
            @Param("userCode") String userCode, @Param("t0") LocalDateTime t0, @Param("t1") LocalDateTime t1);

    /** 员工列表批量：窗口 <code>[t0, t1)</code> 内按 user_code 聚合提交条数 */
    @Query("SELECT g.userCode, COUNT(g) FROM GitCommit g WHERE g.commitTime >= :t0 AND g.commitTime < :t1 GROUP BY g.userCode")
    List<Object[]> countGroupedByUserCodeInCommitWindow(@Param("t0") LocalDateTime t0, @Param("t1") LocalDateTime t1);

    /**
     * 员工数据弹框：某员工在窗口 <code>[t0, t1)</code> 内的提交（可跨多仓库），按时间倒序。
     */
    @Query("SELECT g FROM GitCommit g WHERE g.userCode = :userCode AND g.commitTime >= :t0 AND g.commitTime < :t1 ORDER BY g.commitTime DESC")
    List<GitCommit> findByUserCodeAndCommitWindowOrderByCommitTimeDesc(
            @Param("userCode") String userCode,
            @Param("t0") LocalDateTime t0,
            @Param("t1") LocalDateTime t1,
            Pageable pageable);

    /** 存量回填：有 path_stats_json 但尚无 git_commit_file 行 */
    @Query(value = """
            SELECT c.* FROM git_commit c
            WHERE c.path_stats_json IS NOT NULL
              AND JSON_LENGTH(c.path_stats_json) > 0
              AND NOT EXISTS (SELECT 1 FROM git_commit_file f WHERE f.commit_id = c.id)
            """, nativeQuery = true)
    List<GitCommit> findWithPathStatsButNoFiles();

    /**
     * AI 渗透率（北极星）：窗口 <code>[from, now]</code> 内，按行数口径返回
     * <code>[AI协助行数, 全部非merge行数]</code>。
     *
     * <p>「AI 协助」判定：一条提交存在同 user_code、repo 轻归一化相等、且 commit_time 落在某有效
     * ai_session 活动窗口 <code>[started_at-30min, last_activity+30min]</code> 内的会话。
     * 分子分母都排除 merge 提交（<code>is_merge=0</code>）。
     */
    @Query(value = """
            SELECT
              COALESCE(SUM(CASE WHEN EXISTS (
                  SELECT 1 FROM ai_session s
                  WHERE s.user_code = c.user_code
                    AND s.invalid_reason IS NULL
                    AND s.repo_url IS NOT NULL
                    AND LOWER(TRIM(TRAILING '/' FROM TRIM(TRAILING '.git' FROM s.repo_url)))
                      = LOWER(TRIM(TRAILING '/' FROM TRIM(TRAILING '.git' FROM c.repo_url)))
                    AND c.commit_time BETWEEN (s.started_at - INTERVAL 30 MINUTE)
                                          AND (s.last_activity + INTERVAL 30 MINUTE)
                ) THEN c.lines_added ELSE 0 END), 0) AS assisted_lines,
              COALESCE(SUM(c.lines_added), 0) AS total_lines
            FROM git_commit c
            WHERE c.is_merge = 0
              AND c.repo_url IS NOT NULL
              AND c.commit_time >= :from
            """, nativeQuery = true)
    List<Object[]> aiPenetrationLines(@Param("from") LocalDateTime from);
}
