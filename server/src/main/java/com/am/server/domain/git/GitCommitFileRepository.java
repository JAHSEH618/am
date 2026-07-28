package com.am.server.domain.git;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface GitCommitFileRepository extends JpaRepository<GitCommitFile, Long> {

    /**
     * 文件列表投影：**不含 patch_gzip**。
     *
     * <p>{@code GitCommitFile.patchGzip} 是 {@code @Lob byte[]}，JPA 默认 EAGER，取实体就会把
     * 这个 commit 所有文件的 diff blob 全读进堆；而文件列表只要 path / 增删行数这些元数据。
     * 用闭合投影让 Hibernate 只 SELECT 列出来的这几列。
     */
    interface FileRow {
        String getPath();

        String getOldPath();

        String getChangeType();

        Integer getLinesAdded();

        Integer getLinesDeleted();

        Integer getIsBinary();

        Integer getHasPatch();

        Integer getPatchTruncated();

        String getTruncateReason();
    }

    List<FileRow> findRowsByCommitIdOrderBySortOrderAsc(Long commitId);

    /**
     * 取单个文件（含 blob）。存库前 path / old_path 都过了 {@link GitPathNormalizer#normalize}，
     * 所以这里跟规范化后的入参直接等值比较即可，等价于原先的 pathsEqual 逐行比对。
     */
    @Query("""
        SELECT f FROM GitCommitFile f
        WHERE f.commitId = :commitId AND (f.path = :path OR f.oldPath = :path)
        ORDER BY f.sortOrder ASC
        """)
    List<GitCommitFile> findByCommitIdAndPath(@Param("commitId") Long commitId,
                                              @Param("path") String path);

    long countByCommitId(Long commitId);

    /** 已存文件行里带 patch 的条数——用于判断重报是否带来了更多明细，见 GitCommitIngestService#isRicherThanStored。 */
    long countByCommitIdAndHasPatch(Long commitId, Integer hasPatch);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM GitCommitFile f WHERE f.commitId = :commitId")
    void deleteByCommitId(@Param("commitId") Long commitId);

    /**
     * 保留期清理第一步：挑出「commit 已过期、但文件行上还挂着 patch」的 commit_id。
     *
     * <p>清理会把命中行的 {@code has_patch} 置 0，所以下一轮不会再选中它们——循环靠这个自然收敛。
     */
    @Query(value = """
        SELECT DISTINCT f.commit_id
        FROM git_commit_file f JOIN git_commit c ON c.id = f.commit_id
        WHERE c.commit_time < :cutoff AND f.has_patch = 1
        LIMIT :limit
        """, nativeQuery = true)
    List<Long> findCommitIdsWithExpiredPatches(@Param("cutoff") LocalDateTime cutoff,
                                               @Param("limit") int limit);

    /**
     * 保留期清理第二步：清空 blob，**保留文件行本身**。
     *
     * <p>path / change_type / 增删行数留着，文件列表、统计与归因口径完全不受影响；
     * 只有"点开看 diff"这一个功能对过期 commit 降级。{@code truncate_reason='expired'}
     * 让前端能把"已过保留期"和"从未采集"区分开。
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
        UPDATE GitCommitFile f
        SET f.patchGzip = NULL, f.patchBytes = NULL, f.hasPatch = 0, f.truncateReason = 'expired'
        WHERE f.commitId IN :commitIds AND f.hasPatch = 1
        """)
    int expirePatchesByCommitIds(@Param("commitIds") List<Long> commitIds);
}
