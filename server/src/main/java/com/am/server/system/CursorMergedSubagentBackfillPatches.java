package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 存量 Cursor 子 composer 误建独立 ai_session 的收敛：
 * 1) 父会话消息里已出现子 composer 前缀；
 * 2) 仅有 Task/subagent 消息、无真实 user 消息的孤儿会话。
 *
 * <p>现行 agent 通过 {@code suppressed_session_ids}、server 通过父会话消息前缀实时收敛，这里只清历史：
 * 分别按 message / session 主键分批，完成后写 marker（{@value #MARKER_KEY}）。此前每次启动整表执行，
 * {@code UPDATE ai_session … JOIN ai_session_message} 期间 ai_session 的 INSERT 全部排队。
 * gz
 */
@Configuration
public class CursorMergedSubagentBackfillPatches {

    private static final Logger log = LoggerFactory.getLogger(CursorMergedSubagentBackfillPatches.class);

    static final String MARKER_KEY = "patch.cursor_merged_subagent_v1";

    private static final String MERGE_REFERENCED_CHILDREN = """
            UPDATE ai_session child
            INNER JOIN (
                SELECT DISTINCT parent.agent_id AS agent_id,
                       SUBSTRING_INDEX(m.external_message_id, ':', 1) AS child_ext
                FROM ai_session_message m
                JOIN ai_session parent ON parent.id = m.ai_session_id
                WHERE m.id >= ? AND m.id < ?
                  AND parent.target_type = 'cursor'
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
            WHERE s.id >= ? AND s.id < ?
              AND s.target_type = 'cursor'
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
            try {
                if (OneShotBackfillSupport.markerExists(dataSource, MARKER_KEY)) {
                    return;
                }
                long referenced = OneShotBackfillSupport.updateByIdRange(dataSource, "ai_session_message",
                        MERGE_REFERENCED_CHILDREN, OneShotBackfillSupport.CHILD_ID_STEP);
                long orphans = OneShotBackfillSupport.updateByIdRange(dataSource, "ai_session",
                        MERGE_TASK_ONLY_ORPHANS, OneShotBackfillSupport.SESSION_ID_STEP);
                OneShotBackfillSupport.writeMarker(dataSource, MARKER_KEY, "存量 Cursor 子 composer 会话收敛完成标记");
                log.info("cursor merged_subagent backfill: referenced_children={} task_only_orphans={}",
                        referenced, orphans);
            } catch (SQLException e) {
                log.warn("cursor merged_subagent backfill skipped: {}", e.getMessage());
            }
        };
    }
}
