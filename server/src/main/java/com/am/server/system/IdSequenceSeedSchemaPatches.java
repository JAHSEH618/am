package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 存量 MySQL：{@code id_sequences}(P3-3b 表生成器后备表)幂等建表 + 5 张高写表种子。
 * ⚠️ 兜底而已:本 patch 在 app 已接受连接后(ApplicationRunner)才跑,存在启动窗口竞态;
 * 升级存量 prod 库时应在 app 启动前先跑种子(见计划部署节)。种子 = MAX(id)+1000 缓冲,INSERT IGNORE 幂等。
 * gz
 */
@Configuration
public class IdSequenceSeedSchemaPatches {

    private static final Logger log = LoggerFactory.getLogger(IdSequenceSeedSchemaPatches.class);

    private static final String[] TABLES = {
            "ai_session_event", "ai_session_message", "ai_session_audit", "git_commit", "git_commit_file"
    };

    // 必须先于其它启动期 ApplicationRunner(如 GitCommitPathStatsBackfill)跑:后者会向
    // @TableGenerator 表(git_commit_file 等)JPA 落库,若先跑会触发 Hibernate 惰性建 seq 行
    // (initialValue=0)并从 1 分配 id,与既有行撞主键;之后本 patch 的 INSERT IGNORE 见行已存在而跳过,
    // next_val 永久偏低,持续撞车(非自愈)。
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ApplicationRunner ensureIdSequences(DataSource dataSource) {
        return args -> migrate(dataSource);
    }

    private static void migrate(DataSource dataSource) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS id_sequences ("
                    + "seq_name VARCHAR(64) NOT NULL, next_val BIGINT NOT NULL, PRIMARY KEY (seq_name)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci"
                    + " COMMENT 'Hibernate 表生成器 id 预分配'");
            for (String t : TABLES) {
                st.executeUpdate("INSERT IGNORE INTO id_sequences (seq_name, next_val)"
                        + " SELECT '" + t + "', COALESCE(MAX(id), 0) + 1000 FROM " + t);
            }
            log.info("id_sequences ensured + seeded (buffer +1000) for {} tables", TABLES.length);
        } catch (SQLException e) {
            log.warn("id_sequences seed best-effort failed: {}", e.getMessage());
        }
    }
}
