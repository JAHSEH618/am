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
 * 存量 MySQL：{@code ai_session.version}（乐观锁,P3-2）幂等补齐。
 * gz
 */
@Configuration
public class AiSessionVersionSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(AiSessionVersionSchemaPatches.class);

    @Bean
    ApplicationRunner ensureAiSessionVersionColumn(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        String table = "ai_session";
        String col = "version";
        String def = "BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本(P3-2)'";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + col + " " + def);
        } catch (SQLException e) {
            AnalysisReportSchemaPatches.tryFallbackAddColumn(dataSource, table, col, def, e);
        }
        log.debug("schema patch checked: {}.{}", table, col);
    }
}
