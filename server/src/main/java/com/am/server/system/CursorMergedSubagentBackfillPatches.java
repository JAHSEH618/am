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
 * 存量 Cursor 子 composer 误建独立 ai_session 的收敛：
 * 1) 父会话消息里已出现子 composer 前缀；
 * 2) 仅有 Task/subagent 消息、无真实 user 消息的孤儿会话。
 * gz
 */
@Configuration
public class CursorMergedSubagentBackfillPatches {

    private static final Logger log = LoggerFactory.getLogger(CursorMergedSubagentBackfillPatches.class);

    private static final String MERGE_REFERENCED_CHILDREN = """
            UPDATE ai_session child
            INNER JOIN (
                SELECT DISTINCT parent.agent_id AS agent_id,
                       SUBSTRING_INDEX(m.external_message_id, ':', 1) AS child_ext
                FROM ai_session parent
                JOIN ai_session_message m ON m.ai_session_id = parent.id
                WHERE parent.target_type = 'cursor'
                  AND m.external_message_id LIKE '%:%'
                  AND SUBSTRING_INDEX(m.external_message_id, ':', 1) <> parent.external_session_id
            ) ref ON child.target_type = 'cursor'
                 AND child.agent_id = ref.agent_id
                 AND child.external_session_id = ref.child_ext
            SET child.invalid_reason = 'merged_subagent'
            WHERE child.invalid_reason IS NULL
            """;

    private static final String MERGE_TASK_ONLY_ORPHANS = """
            UPDATE ai_session s
            SET s.invalid_reason = 'merged_subagent'
            WHERE s.target_type = 'cursor'
              AND s.invalid_reason IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM ai_session_message m
                  WHERE m.ai_session_id = s.id AND LOWER(m.role) = 'user'
              )
              AND EXISTS (
                  SELECT 1 FROM ai_session_message m
                  WHERE m.ai_session_id = s.id AND LOWER(m.role) = 'subagent'
              )
            """;

    @Bean
    ApplicationRunner backfillCursorMergedSubagentSessions(DataSource dataSource) {
        return args -> {
            int referenced = execUpdate(dataSource, MERGE_REFERENCED_CHILDREN);
            int orphans = execUpdate(dataSource, MERGE_TASK_ONLY_ORPHANS);
            if (referenced > 0 || orphans > 0) {
                log.info("cursor merged_subagent backfill: referenced_children={} task_only_orphans={}",
                        referenced, orphans);
            }
        };
    }

    private static int execUpdate(DataSource dataSource, String sql) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            return st.executeUpdate(sql);
        } catch (SQLException e) {
            log.warn("cursor merged_subagent backfill skipped: {}", e.getMessage());
            return 0;
        }
    }
}
