package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * 热查询复合索引幂等补齐（存量库启动时执行；新库见 schema.sql CREATE TABLE 内联索引）。
 */
@Configuration
public class PerformanceIndexSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(PerformanceIndexSchemaPatches.class);

    @Bean
    ApplicationRunner ensurePerformanceIndexes(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        int created = SchemaPatchSupport.ensureIndexes(dataSource, List.of(
                new SchemaPatchSupport.IndexSpec("ai_session_event", "idx_target_event_time",
                        "target_type, event_time"),
                new SchemaPatchSupport.IndexSpec("ai_session_message", "idx_target_message_time",
                        "target_type, message_time"),
                new SchemaPatchSupport.IndexSpec("ai_session", "idx_target_last_invalid",
                        "target_type, last_activity, invalid_reason"),
                new SchemaPatchSupport.IndexSpec("ai_session", "idx_target_status",
                        "target_type, status"),
                new SchemaPatchSupport.IndexSpec("ai_session", "idx_project_last",
                        "project_name, last_activity"),
                // dashboard /overview 每次请求都按 updated_time 聚合今日 work_session；缺索引 = 每次全表扫。
                new SchemaPatchSupport.IndexSpec("work_session", "idx_updated_time", "updated_time"),
                // patch 保留期清理按 commit_time 切窗口。
                new SchemaPatchSupport.IndexSpec("git_commit", "idx_commit_time", "commit_time"),
                // 以下两条都在每一次 /agent/report（含 60s 一次的设备心跳）里执行（WorkSessionService.advance）。
                // work_session 原先没有任何 agent_id 索引，只能靠 idx_status 扫全部 OPEN 行再排序；
                // ai_session 只有单列 idx_agent_id，重度用户上千个会话每次都要回表 + filesort 取最新一条。
                new SchemaPatchSupport.IndexSpec("work_session", "idx_agent_status_start",
                        "agent_id, status, start_time"),
                new SchemaPatchSupport.IndexSpec("ai_session", "idx_agent_last", "agent_id, last_activity")));
        log.debug("performance indexes checked, {} created", created);
    }
}
