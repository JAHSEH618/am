package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 存量 MySQL：{@code ai_session.reported_snapshot_messages} 幂等补齐。
 * gz
 */
@Configuration
public class AiSessionReportedSnapshotSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(AiSessionReportedSnapshotSchemaPatches.class);

    @Bean
    ApplicationRunner ensureAiSessionReportedSnapshotColumn(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        String table = "ai_session";
        String col = "reported_snapshot_messages";
        String def = "INT NOT NULL DEFAULT 0 COMMENT 'Agent 最近一次快照 recent_messages 条数'";
        SchemaPatchSupport.ensureColumn(dataSource, table, col, def);
        log.debug("schema patch checked: {}.{}", table, col);
    }
}
