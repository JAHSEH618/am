package com.am.server.system;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * 存量 MySQL：{@code ai_session_message} 斜杠审计列幂等补齐（入库时写入）。
 * gz
 */
@Configuration
public class AiSessionMessageSchemaPatches {

    @Bean
    ApplicationRunner ensureAiSessionMessageSlashColumns(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        SchemaPatchSupport.ensureColumns(dataSource, List.of(
                new SchemaPatchSupport.ColumnSpec("ai_session_message", "slash_command_count",
                        "INT NOT NULL DEFAULT 0 COMMENT '本条 user 消息内斜杠命令'"),
                new SchemaPatchSupport.ColumnSpec("ai_session_message", "slash_skill_count",
                        "INT NOT NULL DEFAULT 0 COMMENT '本条 user 消息内斜杠技能'"),
                new SchemaPatchSupport.ColumnSpec("ai_session_message", "slash_hits_json",
                        "JSON DEFAULT NULL COMMENT '[{token,kind}]'"),
                new SchemaPatchSupport.ColumnSpec("ai_session_message", "conversation_order",
                        "INT DEFAULT NULL COMMENT 'Provider header 对话序号，展示排序用'")));
    }
}
