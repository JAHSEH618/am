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
 * 存量 MySQL：ai_session_message 全量输入采集列 + blob 表（幂等）。
 * gz
 */
@Configuration
public class AiSessionMessageContentCaptureSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(AiSessionMessageContentCaptureSchemaPatches.class);

  private static final String[][] MESSAGE_ALTERS = {
            {"ai_session_message", "content_parts_json",
                    "JSON DEFAULT NULL COMMENT '结构化内容段'"},
            {"ai_session_message", "content_kind",
                    "VARCHAR(16) NOT NULL DEFAULT 'text_only'"},
            {"ai_session_message", "has_binary",
                    "TINYINT NOT NULL DEFAULT 0"},
            {"ai_session_message", "parts_count",
                    "INT NOT NULL DEFAULT 0"},
            {"ai_session_message", "ingest_version",
                    "TINYINT NOT NULL DEFAULT 0"},
    };

    private static final String CREATE_BLOB_TABLE = """
            CREATE TABLE IF NOT EXISTS ai_session_message_blob (
                id BIGINT NOT NULL AUTO_INCREMENT,
                content_sha256 VARCHAR(64) NOT NULL,
                mime_type VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
                byte_size INT NOT NULL,
                gzip_blob MEDIUMBLOB NOT NULL,
                width INT DEFAULT NULL,
                height INT DEFAULT NULL,
                created_time DATETIME NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_sha256 (content_sha256),
                KEY idx_created (created_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_LINK_TABLE = """
            CREATE TABLE IF NOT EXISTS ai_session_message_blob_link (
                id BIGINT NOT NULL AUTO_INCREMENT,
                message_id BIGINT NOT NULL,
                part_index INT NOT NULL,
                blob_id BIGINT NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_msg_part (message_id, part_index),
                KEY idx_blob (blob_id),
                KEY idx_message (message_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    @Bean
    ApplicationRunner ensureAiSessionMessageContentCaptureSchema(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        for (String[] tri : MESSAGE_ALTERS) {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE " + tri[0] + " ADD COLUMN IF NOT EXISTS "
                        + tri[1] + " " + tri[2]);
            } catch (SQLException e) {
                AnalysisReportSchemaPatches.tryFallbackAddColumn(dataSource, tri[0], tri[1], tri[2], e);
            }
        }
        execQuiet(dataSource, CREATE_BLOB_TABLE);
        execQuiet(dataSource, CREATE_LINK_TABLE);
        log.debug("Ensured ai_session_message content capture columns and blob tables");
    }

    private static void execQuiet(DataSource dataSource, String sql) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("already exists") || msg.contains("Duplicate")) {
                return;
            }
            log.warn("schema patch skipped ({}): {}", sql.substring(0, Math.min(60, sql.length())), msg);
        }
    }
}
