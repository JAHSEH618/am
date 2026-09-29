package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 存量 MySQL：{@code ai_session.version}（乐观锁,P3-2）幂等补齐。
 * gz
 */
@Configuration
public class AiSessionVersionSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(AiSessionVersionSchemaPatches.class);

    @Bean
    ApplicationRunner ensureAiSessionVersionColumn(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        String table = "ai_session";
        String col = "version";
        String def = "BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本(P3-2)'";
        SchemaPatchSupport.ensureColumn(dataSource, table, col, def);
        log.debug("schema patch checked: {}.{}", table, col);
    }
}
