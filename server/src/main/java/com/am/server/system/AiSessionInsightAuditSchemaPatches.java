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

    static final String MARKER_KEY = "patch.insight_audit_status_v1";

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

    /**
     * 已有审计行却仍是 NONE 的历史会话对齐为 DONE。报告路径审完现在会自己置 DONE
     * （{@code SessionAuditService#markStatusDone}），这里只清历史：按会话主键分批、完成后写 marker
     * （{@value #MARKER_KEY}）。此前每次启动整表 {@code UPDATE ai_session … JOIN … WHERE status='NONE'}：
     * 沿 idx_insight_audit 的 NONE 区间加 next-key 锁，而新会话恰好都以 NONE 插入，
     * 语句执行期间 ingest 的 INSERT ai_session 全部排队。
     */
    private static void backfillStatus(DataSource dataSource) {
        String sql = """
                UPDATE ai_session s
                INNER JOIN ai_session_audit a ON a.ai_session_id = s.id
                SET s.insight_audit_status = 'DONE',
                    s.insight_audit_rubric_version = a.audit_version
                WHERE s.id >= ? AND s.id < ?
                  AND s.insight_audit_status = 'NONE'
                """;
        try {
            if (OneShotBackfillSupport.markerExists(dataSource, MARKER_KEY)) {
                return;
            }
            long n = OneShotBackfillSupport.updateByIdRange(
                    dataSource, "ai_session", sql, OneShotBackfillSupport.SESSION_ID_STEP);
            OneShotBackfillSupport.writeMarker(dataSource, MARKER_KEY, "存量会话 insight_audit_status 对齐完成标记");
            log.info("ai_session insight_audit backfill: {} rows -> DONE from existing ai_session_audit", n);
        } catch (SQLException e) {
            log.warn("ai_session insight_audit backfill skipped: {}", e.getMessage());
        }
    }
}
