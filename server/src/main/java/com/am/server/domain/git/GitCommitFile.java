package com.am.server.domain.git;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Git 提交逐文件明细（含可选 gzip patch）。
 * gz
 */
@Entity
@Table(name = "git_commit_file")
@Getter
@Setter
@NoArgsConstructor
public class GitCommitFile {

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "gitCommitFileIdGen")
    @TableGenerator(name = "gitCommitFileIdGen", table = "id_sequences",
            pkColumnName = "seq_name", valueColumnName = "next_val",
            pkColumnValue = "git_commit_file", allocationSize = 50)
    private Long id;

    @Column(name = "commit_id", nullable = false)
    private Long commitId;

    @Column(name = "path", nullable = false, length = 1024)
    private String path;

    @Column(name = "old_path", length = 1024)
    private String oldPath;

    /** schema.sql 定义为 CHAR(1)；不显式声明的话 ddl-auto=validate 在全新导入的库上会拒启（期待 varchar(1)）。 */
    @Column(name = "change_type", nullable = false, columnDefinition = "CHAR(1)")
    private String changeType;

    @Column(name = "lines_added", nullable = false)
    private Integer linesAdded = 0;

    @Column(name = "lines_deleted", nullable = false)
    private Integer linesDeleted = 0;

    @Column(name = "is_binary", nullable = false, columnDefinition = "TINYINT")
    private Integer isBinary = 0;

    @Column(name = "has_patch", nullable = false, columnDefinition = "TINYINT")
    private Integer hasPatch = 0;

    @Lob
    @Column(name = "patch_gzip", columnDefinition = "MEDIUMBLOB")
    private byte[] patchGzip;

    @Column(name = "patch_bytes")
    private Integer patchBytes;

    @Column(name = "patch_truncated", nullable = false, columnDefinition = "TINYINT")
    private Integer patchTruncated = 0;

    @Column(name = "truncate_reason", length = 64)
    private String truncateReason;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
