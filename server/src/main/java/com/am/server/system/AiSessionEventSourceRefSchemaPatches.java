package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 存量 MySQL：{@code ai_session_event.source_ref}（P3-3a 物化）幂等补列 + 补索引 +
 * 尽力而为分块回填(extra_json → 列)。回填失败不致命——去重正确性由 ingest 的 COALESCE 兜底。
 * gz
 */
@Configuration
public class AiSessionEventSourceRefSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(AiSessionEventSourceRefSchemaPatches.class);
    private static final int BACKFILL_CHUNK = 10_000;

    @Bean
    ApplicationRunner ensureAiSessionEventSourceRef(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        String table = "ai_session_event";
        String col = "source_ref";
        String def = "VARCHAR(191) DEFAULT NULL COMMENT '去重锚点(P3-3a 物化)'";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + col + " " + def);
        } catch (SQLException e) {
            AnalysisReportSchemaPatches.tryFallbackAddColumn(dataSource, table, col, def, e);
        }
        ensureIndex(dataSource, table, "idx_session_sourceref",
                "CREATE INDEX idx_session_sourceref ON ai_session_event (ai_session_id, source_ref)");
        backfill(dataSource);
        log.debug("schema patch checked: {}.{}", table, col);
    }

    private static void ensureIndex(DataSource dataSource, String table, String indexName, String ddl) {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()"
                             + " AND table_name = '" + table + "' AND index_name = '" + indexName + "'")) {
            if (rs.next()) {
                return;
            }
        } catch (SQLException e) {
            log.warn("index existence check failed for {}.{}: {}", table, indexName, e.getMessage());
            return;
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
            log.info("Created index {} on {}", indexName, table);
        } catch (SQLException e) {
            log.warn("Index {} on {} ensure failed: {}", indexName, table, e.getMessage());
        }
    }

    /** 尽力而为:分块把 extra_json 里的 source_ref 回填到列。失败/中断不致命,COALESCE 兜底。 */
    private static void backfill(DataSource dataSource) {
        try (Connection c = dataSource.getConnection(); Statement probe = c.createStatement()) {
            try (ResultSet rs = probe.executeQuery(
                    "SELECT 1 FROM ai_session_event WHERE source_ref IS NULL AND extra_json IS NOT NULL LIMIT 1")) {
                if (!rs.next()) {
                    return;
                }
            }
            long maxId;
            try (ResultSet rs = probe.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ai_session_event")) {
                rs.next();
                maxId = rs.getLong(1);
            }
            long total = 0;
            for (long lo = 0; lo <= maxId; lo += BACKFILL_CHUNK) {
                try (Statement st = c.createStatement()) {
                    total += st.executeUpdate(
                            "UPDATE ai_session_event"
                                    + " SET source_ref = JSON_UNQUOTE(JSON_EXTRACT(extra_json, '$.source_ref'))"
                                    + " WHERE id >= " + lo + " AND id < " + (lo + BACKFILL_CHUNK)
                                    + " AND source_ref IS NULL AND extra_json IS NOT NULL");
                }
            }
            log.info("ai_session_event.source_ref backfilled: {} rows", total);
        } catch (SQLException e) {
            log.warn("source_ref backfill best-effort failed (non-fatal, COALESCE dedup 兜底): {}", e.getMessage());
        }
    }
}
