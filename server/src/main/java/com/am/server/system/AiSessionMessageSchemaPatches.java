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
 * 存量 MySQL：{@code ai_session_message} 斜杠审计列幂等补齐（入库时写入）。
 * gz
 */
@Configuration
public class AiSessionMessageSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(AiSessionMessageSchemaPatches.class);

    @Bean
    ApplicationRunner ensureAiSessionMessageSlashColumns(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        String[][] altersIf = {
                {"ai_session_message", "slash_command_count",
                        "INT NOT NULL DEFAULT 0 COMMENT '本条 user 消息内斜杠命令'"},
                {"ai_session_message", "slash_skill_count",
                        "INT NOT NULL DEFAULT 0 COMMENT '本条 user 消息内斜杠技能'"},
                {"ai_session_message", "slash_hits_json",
                        "JSON DEFAULT NULL COMMENT '[{token,kind}]'"},
                {"ai_session_message", "conversation_order",
                        "INT DEFAULT NULL COMMENT 'Provider header 对话序号，展示排序用'"},
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
    }
}
