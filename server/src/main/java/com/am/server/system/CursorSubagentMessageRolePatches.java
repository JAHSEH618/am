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
 * 存量 Cursor 消息：Task 子 composer 的 prompt 曾被标为 role=user，修正为 subagent。
 * gz
 */
@Configuration
public class CursorSubagentMessageRolePatches {

    private static final Logger log = LoggerFactory.getLogger(CursorSubagentMessageRolePatches.class);

    private static final String BACKFILL = """
            UPDATE ai_session_message m
            INNER JOIN ai_session s ON s.id = m.ai_session_id
            SET m.role = 'subagent'
            WHERE s.target_type = 'cursor'
              AND LOWER(m.role) = 'user'
              AND m.external_message_id IS NOT NULL
              AND LOCATE(':', m.external_message_id) > 0
              AND SUBSTRING_INDEX(m.external_message_id, ':', 1) <> s.external_session_id
            """;

    @Bean
    ApplicationRunner backfillCursorSubagentMessageRoles(DataSource dataSource) {
        return args -> {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                int n = st.executeUpdate(BACKFILL);
                if (n > 0) {
                    log.info("cursor subagent message role backfill: updated {} rows", n);
                }
            } catch (SQLException e) {
                log.warn("cursor subagent message role backfill skipped: {}", e.getMessage());
            }
        };
    }
}
