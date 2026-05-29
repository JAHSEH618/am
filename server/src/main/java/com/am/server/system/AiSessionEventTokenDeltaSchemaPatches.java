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
 */
@Configuration
public class AiSessionEventTokenDeltaSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(AiSessionEventTokenDeltaSchemaPatches.class);

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

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            // 1) 与会话累计完全一致的 baseline TOKEN_DELTA：直接对齐 session 上的 in/out。
            int exact = st.executeUpdate("""
                    UPDATE ai_session_event e
                    INNER JOIN ai_session s ON s.id = e.ai_session_id
                    SET e.input_tokens_delta = COALESCE(s.input_tokens, 0),
                        e.output_tokens_delta = COALESCE(s.output_tokens, 0)
                    WHERE e.event_type = 'TOKEN_DELTA'
                      AND e.tokens_delta > 0
                      AND COALESCE(e.input_tokens_delta, 0) = 0
                      AND COALESCE(e.output_tokens_delta, 0) = 0
                      AND e.tokens_delta = COALESCE(s.input_tokens, 0) + COALESCE(s.output_tokens, 0)
                    """);
            // 2) 其余增量行：按会话当前 in/out 比例拆分（无法还原逐条真实值，但优于全记 output）。
            int proportional = st.executeUpdate("""
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
                    WHERE e.event_type = 'TOKEN_DELTA'
                      AND e.tokens_delta > 0
                      AND COALESCE(e.input_tokens_delta, 0) = 0
                      AND COALESCE(e.output_tokens_delta, 0) = 0
                    """);
            if (exact > 0 || proportional > 0) {
                log.info("ai_session_event token delta backfill: exact={}, proportional={}", exact, proportional);
            }
        } catch (SQLException e) {
            log.warn("ai_session_event token delta backfill skipped: {}", e.getMessage());
        }
    }
}
