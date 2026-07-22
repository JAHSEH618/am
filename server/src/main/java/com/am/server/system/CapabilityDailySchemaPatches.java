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
 * 存量 MySQL：capability_daily 建表（幂等，与 schema.sql 定义一致）。
 * gz
 */
@Configuration
public class CapabilityDailySchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(CapabilityDailySchemaPatches.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS capability_daily (
                id            BIGINT       NOT NULL AUTO_INCREMENT,
                work_date     DATE         NOT NULL,
                user_code     VARCHAR(64)  NOT NULL,
                kind          VARCHAR(16)  NOT NULL COMMENT 'skill / nl_skill / mcp / plugin_ns',
                item          VARCHAR(128) NOT NULL COMMENT '一级维度：skill 名 / MCP server / 插件 namespace（统一小写）',
                sub_item      VARCHAR(256) NOT NULL DEFAULT '' COMMENT '二级维度：mcp=tool 名、plugin_ns=技能名；汇总行与 skill/nl_skill 恒为空串',
                invoke_count  INT          NOT NULL DEFAULT 0 COMMENT '当日调用次数',
                session_count INT          NOT NULL DEFAULT 0 COMMENT '当日去重会话数（本行粒度内）',
                created_time  DATETIME     NOT NULL,
                updated_time  DATETIME     NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_day_user_kind_item (work_date, user_code, kind, item, sub_item),
                KEY idx_kind_item_date (kind, item, work_date),
                KEY idx_user_date (user_code, work_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            COMMENT='能力使用日聚合（skill / MCP / 插件），CapabilityDailyAggregator 写入'
            """;

    /** 先于 CapabilityDailyBackfillPatch（@Order(900)）确保表存在。 */
    @Bean
    @Order(120)
    ApplicationRunner ensureCapabilityDailySchema(DataSource dataSource) {
        return args -> {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate(CREATE_TABLE);
                log.debug("Ensured capability_daily table");
            } catch (SQLException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.toLowerCase().contains("already exists")) {
                    return;
                }
                log.warn("capability_daily schema patch failed: {}", msg);
            }
        };
    }
}
