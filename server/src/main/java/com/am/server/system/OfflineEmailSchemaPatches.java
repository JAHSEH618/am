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
 * 存量 MySQL：agent_device 增量列 last_offline_email_time（离线提醒邮件 12h 去重锚点）幂等补齐。
 * gz
 */
@Configuration
public class OfflineEmailSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(OfflineEmailSchemaPatches.class);

    @Bean
    @Order(170)
    ApplicationRunner ensureOfflineEmailColumns(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        String table = "agent_device";
        String col = "last_offline_email_time";
        String def = "DATETIME DEFAULT NULL COMMENT '上次离线提醒邮件发送时间（12h 去重）'";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + col + " " + def);
        } catch (SQLException e) {
            AnalysisReportSchemaPatches.tryFallbackAddColumn(dataSource, table, col, def, e);
        }
    }
}
