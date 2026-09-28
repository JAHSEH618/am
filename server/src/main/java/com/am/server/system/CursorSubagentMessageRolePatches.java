package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 存量 Cursor 消息：Task 子 composer 的 prompt 曾被标为 role=user，修正为 subagent。
 *
 * <p>现行 agent 已直接上报 role=subagent，这里只修历史行：按 message 主键分批、完成后写 marker
 * （{@value #MARKER_KEY}），不再每次启动整表 {@code UPDATE…JOIN ai_session}（会锁住 ai_session 挡 ingest）。
 * gz
 */
@Configuration
public class CursorSubagentMessageRolePatches {

    private static final Logger log = LoggerFactory.getLogger(CursorSubagentMessageRolePatches.class);

    static final String MARKER_KEY = "patch.cursor_subagent_role_v1";

    private static final String BACKFILL = """
            UPDATE ai_session_message m
            INNER JOIN ai_session s ON s.id = m.ai_session_id
            SET m.role = 'subagent'
            WHERE m.id >= ? AND m.id < ?
              AND s.target_type = 'cursor'
              AND LOWER(m.role) = 'user'
              AND m.external_message_id IS NOT NULL
              AND LOCATE(':', m.external_message_id) > 0
              AND SUBSTRING_INDEX(m.external_message_id, ':', 1) <> s.external_session_id
            """;

    @Bean
    ApplicationRunner backfillCursorSubagentMessageRoles(DataSource dataSource) {
        return args -> {
            try {
                if (OneShotBackfillSupport.markerExists(dataSource, MARKER_KEY)) {
                    return;
                }
                long n = OneShotBackfillSupport.updateByIdRange(
                        dataSource, "ai_session_message", BACKFILL, OneShotBackfillSupport.CHILD_ID_STEP);
                OneShotBackfillSupport.writeMarker(dataSource, MARKER_KEY, "存量 Cursor 子 composer 消息 role 修正完成标记");
                log.info("cursor subagent message role backfill: updated {} rows", n);
            } catch (SQLException e) {
                log.warn("cursor subagent message role backfill skipped: {}", e.getMessage());
            }
        };
    }
}
