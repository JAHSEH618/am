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
 * 存量 MySQL：分析报告表增量列（TOOL_CALL 命令/技能分布）幂等补齐。
 * gz
 */
@Configuration
public class AnalysisReportSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(AnalysisReportSchemaPatches.class);

    @Bean
    @Order(100)
    ApplicationRunner ensureAnalysisReportToolColumns(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        String[][] altersIf = {
                {"analysis_report", "team_tool_breakdown_json",
                        "JSON DEFAULT NULL COMMENT '用户主动斜杠团队分布'"},
                {"analysis_report_user", "tool_command_count",
                        "INT NOT NULL DEFAULT 0 COMMENT '用户斜杠命令次数'"},
                {"analysis_report_user", "tool_skill_count",
                        "INT NOT NULL DEFAULT 0 COMMENT '用户斜杠技能次数'"},
                {"analysis_report_user", "tool_breakdown_json",
                        "JSON DEFAULT NULL COMMENT '斜杠首词分布'"},
                {"analysis_report", "team_capability_percentiles_json",
                        "JSON DEFAULT NULL COMMENT '团队五维能力分位基线'"},
                {"analysis_report_user", "highlight_sessions_json",
                        "JSON DEFAULT NULL COMMENT '典型会话卡片'"},
                {"analysis_report_user", "top_models_json",
                        "JSON DEFAULT NULL COMMENT 'Top 模型'"},
                {"analysis_report_user", "top_projects_json",
                        "JSON DEFAULT NULL COMMENT 'Top 项目'"},
                {"analysis_report_user", "agent_dist_json",
                        "JSON DEFAULT NULL COMMENT 'Agent 会话分布'"},
        };
        for (String[] tri : altersIf) {
            String table = tri[0];
            String col = tri[1];
            String def = tri[2];
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS "
                        + col + " " + def);
            } catch (SQLException e) {
                tryFallbackAddColumn(dataSource, table, col, def, e);
            }
        }
    }

    static void tryFallbackAddColumn(
            DataSource dataSource, String table, String col, String def, SQLException first) {
        String msg = first.getMessage() != null ? first.getMessage() : "";
        if (duplicateColumn(msg)) {
            return;
        }
        if (!looksLikeIfNotExistsUnsupported(msg)) {
            log.warn("{} {} ensure failed: {}", table, col, msg);
            return;
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + col + " " + def);
            log.info("Added {}.{} (fallback ALTER)", table, col);
        } catch (SQLException e2) {
            String m2 = e2.getMessage() != null ? e2.getMessage() : "";
            if (duplicateColumn(m2)) {
                return;
            }
            log.warn("{}.{} fallback ALTER failed: {}", table, col, m2);
        }
    }

    private static boolean duplicateColumn(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("duplicate column") || msg.contains("1060");
    }

    private static boolean looksLikeIfNotExistsUnsupported(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("syntax")
                || lower.contains("42000")
                || lower.contains("near 'if'");
    }
}
