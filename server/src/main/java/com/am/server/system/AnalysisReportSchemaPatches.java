package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.util.List;

/**
 * 存量 MySQL：分析报告表增量列（TOOL_CALL 命令/技能分布）幂等补齐。
 * 先查 information_schema、缺才 ALTER（INSTANT，MDL 等待 5s 封顶），见 {@link SchemaPatchSupport}。
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

    private static final List<SchemaPatchSupport.ColumnSpec> COLUMNS = List.of(
            col("analysis_report", "team_tool_breakdown_json",
                    "JSON DEFAULT NULL COMMENT '用户主动斜杠团队分布'"),
            col("analysis_report_user", "tool_command_count",
                    "INT NOT NULL DEFAULT 0 COMMENT '用户斜杠命令次数'"),
            col("analysis_report_user", "tool_skill_count",
                    "INT NOT NULL DEFAULT 0 COMMENT '用户斜杠技能次数'"),
            col("analysis_report_user", "tool_breakdown_json",
                    "JSON DEFAULT NULL COMMENT '斜杠首词分布'"),
            col("analysis_report", "team_capability_percentiles_json",
                    "JSON DEFAULT NULL COMMENT '团队五维能力分位基线'"),
            col("analysis_report_user", "highlight_sessions_json",
                    "JSON DEFAULT NULL COMMENT '典型会话卡片'"),
            col("analysis_report_user", "top_models_json",
                    "JSON DEFAULT NULL COMMENT 'Top 模型'"),
            col("analysis_report_user", "top_projects_json",
                    "JSON DEFAULT NULL COMMENT 'Top 项目'"),
            col("analysis_report_user", "agent_dist_json",
                    "JSON DEFAULT NULL COMMENT 'Agent 会话分布'"),
            col("analysis_report", "team_grade_dist_json",
                    "JSON DEFAULT NULL COMMENT '等级分布'"),
            col("analysis_report", "team_narrative_json",
                    "JSON DEFAULT NULL COMMENT 'LLM 团队总评'"),
            col("analysis_report_user", "composite_grade",
                    "VARCHAR(2) DEFAULT NULL COMMENT 'S/A/B/C/D'"),
            col("analysis_report_user", "composite_confidence",
                    "VARCHAR(8) DEFAULT NULL COMMENT 'normal/low'"),
            col("analysis_report_user", "composite_breakdown_json",
                    "JSON DEFAULT NULL COMMENT 'v2 得分构成'"),
            col("analysis_report_user", "narrative_json",
                    "JSON DEFAULT NULL COMMENT 'LLM 个人评语'"),
            col("analysis_report_user", "retry_count",
                    "INT DEFAULT NULL COMMENT '窗口重试次数'"),
            col("analysis_report_user", "retry_per_active_hour",
                    "DECIMAL(8,4) DEFAULT NULL"),
            col("analysis_report_user", "tool_call_count",
                    "INT DEFAULT NULL COMMENT '窗口工具调用次数'"));

    private static SchemaPatchSupport.ColumnSpec col(String table, String column, String ddl) {
        return new SchemaPatchSupport.ColumnSpec(table, column, ddl);
    }

    private static void migrate(DataSource dataSource) {
        int added = SchemaPatchSupport.ensureColumns(dataSource, COLUMNS);
        log.debug("analysis_report schema patch checked: {} columns, {} added", COLUMNS.size(), added);
    }
}
