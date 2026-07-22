package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 存量 MySQL：git_commit_attribution 建表（幂等，与 schema.sql 定义一致）。
 * gz
 */
@Configuration
public class GitCommitAttributionSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(GitCommitAttributionSchemaPatches.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS git_commit_attribution (
                id               BIGINT       NOT NULL,
                commit_id        BIGINT       NOT NULL COMMENT '-> git_commit.id',
                repo_url         VARCHAR(512) NOT NULL COMMENT '冗余自 git_commit，避免透视 JOIN',
                user_code        VARCHAR(64)  NOT NULL,
                project_name     VARCHAR(128) DEFAULT NULL COMMENT '归属会话的 project_name（NONE 行为 NULL）',
                commit_time      DATETIME     NOT NULL,
                lines_added      INT          NOT NULL DEFAULT 0,
                lines_deleted    INT          NOT NULL DEFAULT 0,
                tier             VARCHAR(8)   NOT NULL COMMENT 'B 确定 / A 疑似 / NONE 未命中',
                trailer_kind     VARCHAR(32)  DEFAULT NULL COMMENT 'B 档命中的 trailer 规则名',
                session_id       BIGINT       DEFAULT NULL COMMENT '归属会话（B 档窗口内找不到该工具会话时可空）',
                target_type      VARCHAR(32)  DEFAULT NULL COMMENT '归属工具',
                model            VARCHAR(128) DEFAULT NULL COMMENT '归属模型（B 档无会话时为 unknown）',
                overlap_seconds  INT          DEFAULT NULL COMMENT '归属会话活动区间与 commit ±30min 窗口的重叠秒数',
                backfilled       TINYINT      NOT NULL DEFAULT 0 COMMENT '1=上线前历史回溯所得',
                computed_time    DATETIME     NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_commit (commit_id),
                KEY idx_commit_time (commit_time),
                KEY idx_user_time (user_code, commit_time),
                KEY idx_tier_time (tier, commit_time),
                KEY idx_target_time (target_type, commit_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            COMMENT='commit→AI 产出归因（每非 merge commit 一行，GitCommitAttributionEngine 写入）'
            """;

    /**
     * 先于 GitCommitAttributionBackfillPatch（@Order(910)}）确保表存在。
     * <p>id_sequences 种子在此自补（而非只靠 IdSequenceSeedSchemaPatches）：后者以最高优先级先跑，
     * 存量库彼时新表还不存在、其 SELECT 会失败跳过；建表后立刻补种即无顺序依赖。
     */
    @Bean
    @Order(130)
    ApplicationRunner ensureGitCommitAttributionSchema(DataSource dataSource) {
        return args -> {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate(CREATE_TABLE);
                st.executeUpdate("INSERT IGNORE INTO id_sequences (seq_name, next_val)"
                        + " SELECT 'git_commit_attribution', COALESCE(MAX(id), 0) + 1000 FROM git_commit_attribution");
                log.debug("Ensured git_commit_attribution table + id_sequences seed");
            } catch (SQLException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.toLowerCase().contains("already exists")) {
                    return;
                }
                log.warn("git_commit_attribution schema patch failed: {}", msg);
            }
        };
    }
}
