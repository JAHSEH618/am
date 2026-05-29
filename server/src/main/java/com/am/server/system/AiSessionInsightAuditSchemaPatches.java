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
 * 存量 MySQL：{@code ai_session} 洞察审计列（无 IF NOT EXISTS 时的 fallback）。
 * gz
 */
@Configuration
public class AiSessionInsightAuditSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(AiSessionInsightAuditSchemaPatches.class);

    @Bean
    ApplicationRunner ensureAiSessionInsightAuditColumns(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        String[][] altersIf = {
                {"ai_session", "invalid_reason",
                        "VARCHAR(64) DEFAULT NULL COMMENT 'v2.11 无效会话'"},
                {"ai_session", "insight_audit_status",
                        "VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT '后台洞察审计状态'"},
                {"ai_session", "insight_audit_rubric_version",
                        "VARCHAR(16) DEFAULT NULL COMMENT '最近一次成功审计 audit_version'"},
                {"ai_session", "insight_reaudit_required",
                        "TINYINT NOT NULL DEFAULT 0 COMMENT '管理员要求重审'"},
                {"ai_session", "insight_audit_lease_until",
                        "DATETIME(3) DEFAULT NULL COMMENT 'RUNNING 租约'"},
        };
        for (String[] tri : altersIf) {
            String table = tri[0];
            String col = tri[1];
            String def = tri[2];
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS "
                        + col + " " + def);
            } catch (SQLException e) {
                AnalysisReportSchemaPatches.tryFallbackAddColumn(dataSource, table, col, def, e);
            }
        }
        backfillStatus(dataSource);
    }

    private static void backfillStatus(DataSource dataSource) {
        String sql = """
                UPDATE ai_session s
                INNER JOIN ai_session_audit a ON a.ai_session_id = s.id
                SET s.insight_audit_status = 'DONE',
                    s.insight_audit_rubric_version = a.audit_version
                WHERE s.insight_audit_status = 'NONE'
                """;
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            int n = st.executeUpdate(sql);
            if (n > 0) {
                log.info("ai_session insight_audit backfill: {} rows -> DONE from existing ai_session_audit", n);
            }
        } catch (SQLException e) {
            log.warn("ai_session insight_audit backfill skipped: {}", e.getMessage());
        }
    }
}
