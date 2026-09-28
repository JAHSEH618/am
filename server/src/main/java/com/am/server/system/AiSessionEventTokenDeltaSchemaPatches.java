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
 * 存量 MySQL：{@code ai_session_event} 补齐 input/output token 增量列，并把历史
 * {@code tokens_delta} 按会话 in/out 比例回填（baseline 行与会话累计一致时精确对齐）。
 *
 * <p>现行 ingest 写事件时已带 in/out 增量，回填只针对历史行：按 event 主键分批、完成后写 marker
 * （{@value #MARKER_KEY}）。此前每次启动整表 {@code UPDATE ai_session_event JOIN ai_session}，
 * 执行期间对 ai_session 加共享锁，挡住 ingest 更新会话。
 */
@Configuration
public class AiSessionEventTokenDeltaSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(AiSessionEventTokenDeltaSchemaPatches.class);

    static final String MARKER_KEY = "patch.token_delta_backfill_v1";

    @Bean
    ApplicationRunner ensureAiSessionEventTokenDeltaColumns(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        String[][] altersIf = {
                {"ai_session_event", "input_tokens_delta",
                        "BIGINT NOT NULL DEFAULT 0 COMMENT 'TOKEN_DELTA 时 input 增量'"},
                {"ai_session_event", "output_tokens_delta",
                        "BIGINT NOT NULL DEFAULT 0 COMMENT 'TOKEN_DELTA 时 output 增量'"},
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

        try {
            if (OneShotBackfillSupport.markerExists(dataSource, MARKER_KEY)) {
                return;
            }
            // 1) 与会话累计完全一致的 baseline TOKEN_DELTA：直接对齐 session 上的 in/out。
            long exact = OneShotBackfillSupport.updateByIdRange(dataSource, "ai_session_event", """
                    UPDATE ai_session_event e
                    INNER JOIN ai_session s ON s.id = e.ai_session_id
                    SET e.input_tokens_delta = COALESCE(s.input_tokens, 0),
                        e.output_tokens_delta = COALESCE(s.output_tokens, 0)
                    WHERE e.id >= ? AND e.id < ?
                      AND e.event_type = 'TOKEN_DELTA'
                      AND e.tokens_delta > 0
                      AND COALESCE(e.input_tokens_delta, 0) = 0
                      AND COALESCE(e.output_tokens_delta, 0) = 0
                      AND e.tokens_delta = COALESCE(s.input_tokens, 0) + COALESCE(s.output_tokens, 0)
                    """, OneShotBackfillSupport.CHILD_ID_STEP);
            // 2) 其余增量行：按会话当前 in/out 比例拆分（无法还原逐条真实值，但优于全记 output）。
            long proportional = OneShotBackfillSupport.updateByIdRange(dataSource, "ai_session_event", """
                    UPDATE ai_session_event e
                    INNER JOIN ai_session s ON s.id = e.ai_session_id
                    SET e.input_tokens_delta = CASE
                            WHEN COALESCE(s.input_tokens, 0) + COALESCE(s.output_tokens, 0) > 0
                            THEN FLOOR(e.tokens_delta * s.input_tokens
                                / (COALESCE(s.input_tokens, 0) + COALESCE(s.output_tokens, 0)))
                            ELSE 0 END,
                        e.output_tokens_delta = e.tokens_delta - CASE
                            WHEN COALESCE(s.input_tokens, 0) + COALESCE(s.output_tokens, 0) > 0
                            THEN FLOOR(e.tokens_delta * s.input_tokens
                                / (COALESCE(s.input_tokens, 0) + COALESCE(s.output_tokens, 0)))
                            ELSE 0 END
                    WHERE e.id >= ? AND e.id < ?
                      AND e.event_type = 'TOKEN_DELTA'
                      AND e.tokens_delta > 0
                      AND COALESCE(e.input_tokens_delta, 0) = 0
                      AND COALESCE(e.output_tokens_delta, 0) = 0
                    """, OneShotBackfillSupport.CHILD_ID_STEP);
            OneShotBackfillSupport.writeMarker(dataSource, MARKER_KEY, "存量 TOKEN_DELTA 事件 in/out 拆分回填完成标记");
            log.info("ai_session_event token delta backfill: exact={}, proportional={}", exact, proportional);
        } catch (SQLException e) {
            log.warn("ai_session_event token delta backfill skipped: {}", e.getMessage());
        }
    }
}
