package com.am.server.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent → Server git_commit 上报载荷（v2.2 Phase 3）
 * 与 agent/internal/reporter/gitlog.go 的 commitReportRequest 对齐
 * gz
 */
@Data
public class GitCommitReportRequest {

    @JsonProperty("agent_id")
    @NotBlank
    private String agentId;

    @JsonProperty("agent_version")
    private String agentVersion;

    @JsonProperty("captured_at")
    @NotNull
    private LocalDateTime capturedAt;

    /** 客户端 AllowedEmails（normalize 后）；与 agent_device.git_user_email 并集，服务端兜底校验 */
    @JsonProperty("reported_identity_emails")
    private List<String> reportedIdentityEmails;

    @NotNull
    private List<Item> commits;

    @Data
    public static class Item {
        @JsonProperty("repo_url")
        @NotBlank
        private String repoUrl;

        @JsonProperty("commit_hash")
        @NotBlank
        private String commitHash;

        @JsonProperty("commit_time")
        @NotNull
        private LocalDateTime commitTime;

        @JsonProperty("author_name")
        private String authorName;

        @JsonProperty("author_email")
        private String authorEmail;

        @JsonProperty("message_subject")
        private String messageSubject;

        @JsonProperty("message_body")
        private String messageBody;

        @JsonProperty("parent_hashes")
        private List<String> parentHashes;

        @JsonProperty("is_merge")
        private Boolean isMerge;

        @JsonProperty("detail_status")
        private String detailStatus;

        @JsonProperty("detail_skip_reason")
        private String detailSkipReason;

        @JsonProperty("branch_name")
        private String branchName;

        @JsonProperty("files_changed")
        private Integer filesChanged;

        @JsonProperty("lines_added")
        private Integer linesAdded;

        @JsonProperty("lines_deleted")
        private Integer linesDeleted;

        /** git numstat 逐文件明细（可选）；与 agent Commit.path_stats 对齐 */
        @JsonProperty("path_stats")
        private List<PathStat> pathStats;

        /** 文件级明细 + 可选 gzip patch（agent enrich） */
        @JsonProperty("files")
        private List<FileDetail> files;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class PathStat {
        private String path;
        private int linesAdded;
        private int linesDeleted;
    }

    @Data
    public static class FileDetail {
        private String path;
        @JsonProperty("old_path")
        private String oldPath;
        @JsonProperty("change_type")
        private String changeType;
        @JsonProperty("lines_added")
        private int linesAdded;
        @JsonProperty("lines_deleted")
        private int linesDeleted;
        @JsonProperty("is_binary")
        private boolean binary;
        @JsonProperty("has_patch")
        private boolean hasPatch;
        @JsonProperty("patch_gzip_base64")
        private String patchGzipBase64;
        @JsonProperty("patch_bytes")
        private int patchBytes;
        @JsonProperty("patch_truncated")
        private boolean patchTruncated;
        @JsonProperty("truncate_reason")
        private String truncateReason;
        @JsonProperty("sort_order")
        private int sortOrder;
    }
}
