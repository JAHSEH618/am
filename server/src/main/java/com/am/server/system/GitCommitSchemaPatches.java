package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * 存量 MySQL：git_commit 补列、git_commit_file 建表（幂等）。
 * gz
 */
@Configuration
public class GitCommitSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(GitCommitSchemaPatches.class);

    /** 存量库：下线 git_commit AI 协助推断列（与 schema.sql 末尾迁移一致）。先删索引，再删列。 */
    private static final List<SchemaPatchSupport.IndexRef> GIT_COMMIT_AI_ASSIST_DROP_INDEXES = List.of(
            new SchemaPatchSupport.IndexRef("git_commit", "idx_ai_assisted"));

    private static final List<SchemaPatchSupport.ColumnRef> GIT_COMMIT_AI_ASSIST_DROP_COLUMNS = List.of(
            new SchemaPatchSupport.ColumnRef("git_commit", "ai_session_ids"),
            new SchemaPatchSupport.ColumnRef("git_commit", "ai_assist_confidence"),
            new SchemaPatchSupport.ColumnRef("git_commit", "ai_assisted"));

    private static final List<SchemaPatchSupport.ColumnSpec> GIT_COMMIT_COLUMNS = List.of(
            new SchemaPatchSupport.ColumnSpec("git_commit", "path_stats_json",
                    "JSON DEFAULT NULL COMMENT 'git numstat 逐文件'"),
            new SchemaPatchSupport.ColumnSpec("git_commit", "message_body", "TEXT DEFAULT NULL"),
            new SchemaPatchSupport.ColumnSpec("git_commit", "parent_hashes_json", "JSON DEFAULT NULL"),
            new SchemaPatchSupport.ColumnSpec("git_commit", "is_merge", "TINYINT NOT NULL DEFAULT 0"),
            new SchemaPatchSupport.ColumnSpec("git_commit", "detail_status", "VARCHAR(16) DEFAULT NULL"),
            new SchemaPatchSupport.ColumnSpec("git_commit", "detail_collected_at", "DATETIME DEFAULT NULL"),
            new SchemaPatchSupport.ColumnSpec("git_commit", "detail_skip_reason", "VARCHAR(64) DEFAULT NULL"));

    private static final String CREATE_FILE_TABLE = """
            CREATE TABLE IF NOT EXISTS git_commit_file (
                id BIGINT NOT NULL AUTO_INCREMENT,
                commit_id BIGINT NOT NULL,
                path VARCHAR(1024) NOT NULL,
                old_path VARCHAR(1024) DEFAULT NULL,
                change_type CHAR(1) NOT NULL DEFAULT 'M',
                lines_added INT NOT NULL DEFAULT 0,
                lines_deleted INT NOT NULL DEFAULT 0,
                is_binary TINYINT NOT NULL DEFAULT 0,
                has_patch TINYINT NOT NULL DEFAULT 0,
                patch_gzip MEDIUMBLOB DEFAULT NULL,
                patch_bytes INT DEFAULT NULL,
                patch_truncated TINYINT NOT NULL DEFAULT 0,
                truncate_reason VARCHAR(64) DEFAULT NULL,
                sort_order INT NOT NULL DEFAULT 0,
                PRIMARY KEY (id),
                KEY idx_commit (commit_id),
                KEY idx_commit_path (commit_id, path(255))
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            COMMENT='Git 提交逐文件明细与 gzip patch'
            """;

    @Bean
    ApplicationRunner ensureGitCommitDetailSchema(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        SchemaPatchSupport.dropIndexesIfPresent(dataSource, GIT_COMMIT_AI_ASSIST_DROP_INDEXES);
        SchemaPatchSupport.dropColumnsIfPresent(dataSource, GIT_COMMIT_AI_ASSIST_DROP_COLUMNS);
        SchemaPatchSupport.ensureColumns(dataSource, GIT_COMMIT_COLUMNS);
        SchemaPatchSupport.ensureTable(dataSource, "git_commit_file", CREATE_FILE_TABLE);
        log.debug("Ensured git_commit detail columns and git_commit_file table");
    }
}
