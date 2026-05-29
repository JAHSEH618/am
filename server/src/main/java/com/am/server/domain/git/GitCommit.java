package com.am.server.domain.git;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Git 提交流水实体（v2.2 Phase 3）
 * 由 aiwatchd 的 gitlog Provider 上报
 * gz
 */
@Entity
@Table(name = "git_commit")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class GitCommit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "host_hash", nullable = false, length = 128)
    private String hostHash;

    @Column(name = "repo_url", nullable = false, length = 512)
    private String repoUrl;

    @Column(name = "commit_hash", nullable = false, length = 40)
    private String commitHash;

    @Column(name = "commit_time", nullable = false)
    private LocalDateTime commitTime;

    @Column(name = "author_name", length = 128)
    private String authorName;

    @Column(name = "author_email", length = 128)
    private String authorEmail;

    @Column(name = "message_subject", length = 512)
    private String messageSubject;

    @Column(name = "files_changed", nullable = false)
    private Integer filesChanged = 0;

    @Column(name = "lines_added", nullable = false)
    private Integer linesAdded = 0;

    @Column(name = "lines_deleted", nullable = false)
    private Integer linesDeleted = 0;

    /** JSON 数组：[{path,lines_added,lines_deleted}]，来自 agent git numstat */
    @Column(name = "path_stats_json", columnDefinition = "JSON")
    private String pathStatsJson;

    @Column(name = "message_body", columnDefinition = "TEXT")
    private String messageBody;

    @Column(name = "parent_hashes_json", columnDefinition = "JSON")
    private String parentHashesJson;

    @Column(name = "is_merge", nullable = false, columnDefinition = "TINYINT")
    private Integer isMerge = 0;

    /** none / partial / full / skipped */
    @Column(name = "detail_status", length = 16)
    private String detailStatus;

    @Column(name = "detail_collected_at")
    private LocalDateTime detailCollectedAt;

    @Column(name = "detail_skip_reason", length = 64)
    private String detailSkipReason;

    @Column(name = "branch_name", length = 128)
    private String branchName;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @LastModifiedDate
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
