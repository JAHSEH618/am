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
 * 热查询复合索引幂等补齐（存量库启动时执行；新库见 schema.sql CREATE TABLE 内联索引）。
 */
@Configuration
public class PerformanceIndexSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(PerformanceIndexSchemaPatches.class);

    @Bean
    ApplicationRunner ensurePerformanceIndexes(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        ensureIndex(dataSource, "ai_session_event", "idx_target_event_time",
                "CREATE INDEX idx_target_event_time ON ai_session_event (target_type, event_time)");
        ensureIndex(dataSource, "ai_session_message", "idx_target_message_time",
                "CREATE INDEX idx_target_message_time ON ai_session_message (target_type, message_time)");
        ensureIndex(dataSource, "ai_session", "idx_target_last_invalid",
                "CREATE INDEX idx_target_last_invalid ON ai_session (target_type, last_activity, invalid_reason)");
    }

    private static void ensureIndex(DataSource dataSource, String table, String indexName, String ddl) {
        if (indexExists(dataSource, table, indexName)) {
            return;
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
            log.info("Created index {} on {}", indexName, table);
        } catch (SQLException e) {
            log.warn("Index {} on {} ensure failed: {}", indexName, table, e.getMessage());
        }
    }

    private static boolean indexExists(DataSource dataSource, String table, String indexName) {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT 1 FROM information_schema.statistics
                     WHERE table_schema = DATABASE()
                       AND table_name = '%s'
                       AND index_name = '%s'
                     LIMIT 1
                     """.formatted(table, indexName))) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }
}
