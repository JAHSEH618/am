package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 存量 MySQL：git_commit 补列、git_commit_file 建表（幂等）。
 * gz
 */
@Configuration
public class GitCommitSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(GitCommitSchemaPatches.class);

    /** 存量库：下线 git_commit AI 协助推断列（与 schema.sql 末尾迁移一致）。 */
    private static final String[] GIT_COMMIT_AI_ASSIST_DROPS = {
            "ALTER TABLE git_commit DROP INDEX idx_ai_assisted",
            "ALTER TABLE git_commit DROP COLUMN ai_session_ids",
            "ALTER TABLE git_commit DROP COLUMN ai_assist_confidence",
            "ALTER TABLE git_commit DROP COLUMN ai_assisted",
    };

    private static final String[] GIT_COMMIT_ALTERS = {
            "ALTER TABLE git_commit ADD COLUMN IF NOT EXISTS path_stats_json JSON DEFAULT NULL COMMENT 'git numstat 逐文件'",
            "ALTER TABLE git_commit ADD COLUMN IF NOT EXISTS message_body TEXT DEFAULT NULL",
            "ALTER TABLE git_commit ADD COLUMN IF NOT EXISTS parent_hashes_json JSON DEFAULT NULL",
            "ALTER TABLE git_commit ADD COLUMN IF NOT EXISTS is_merge TINYINT NOT NULL DEFAULT 0",
            "ALTER TABLE git_commit ADD COLUMN IF NOT EXISTS detail_status VARCHAR(16) DEFAULT NULL",
            "ALTER TABLE git_commit ADD COLUMN IF NOT EXISTS detail_collected_at DATETIME DEFAULT NULL",
            "ALTER TABLE git_commit ADD COLUMN IF NOT EXISTS detail_skip_reason VARCHAR(64) DEFAULT NULL",
    };

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
        for (String sql : GIT_COMMIT_AI_ASSIST_DROPS) {
            execQuiet(dataSource, sql, true);
        }
        for (String sql : GIT_COMMIT_ALTERS) {
            execQuiet(dataSource, sql, false);
        }
        execQuiet(dataSource, CREATE_FILE_TABLE, false);
        log.debug("Ensured git_commit detail columns and git_commit_file table");
    }

    private static void execQuiet(DataSource dataSource, String sql, boolean dropOp) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (duplicateColumn(msg) || duplicateTable(msg) || missingColumnOrIndex(msg, dropOp)) {
                return;
            }
            if (looksLikeIfNotExistsUnsupported(msg)) {
                execPlainFallback(dataSource, sql);
                return;
            }
            log.warn("git commit schema patch failed: {} err={}", sql.substring(0, Math.min(60, sql.length())), msg);
        }
    }

    private static boolean missingColumnOrIndex(String msg, boolean dropOp) {
        if (!dropOp) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("check that column") || lower.contains("check that it exists")
                || lower.contains("can't drop") || msg.contains("1091") || msg.contains("1072");
    }

    private static void execPlainFallback(DataSource dataSource, String sqlIfNotExists) {
        String plain = sqlIfNotExists.replace(" IF NOT EXISTS", "");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(plain);
        } catch (SQLException e2) {
            String m2 = e2.getMessage() != null ? e2.getMessage() : "";
            if (duplicateColumn(m2) || duplicateTable(m2)) {
                return;
            }
            log.warn("git commit schema fallback failed: {}", m2);
        }
    }

    private static boolean duplicateColumn(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("duplicate column") || msg.contains("1060");
    }

    private static boolean duplicateTable(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("already exists") && lower.contains("table");
    }

    private static boolean looksLikeIfNotExistsUnsupported(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("syntax") || lower.contains("42000") || lower.contains("near 'if'");
    }
}
