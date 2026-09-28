package com.am.server.config;

import com.am.server.common.QueryBudget;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 控制台读请求的 SELECT 超时：线程上设了 {@link QueryBudget} 时，给 SELECT 注入
 * {@code /*+ MAX_EXECUTION_TIME(n) *}{@code /} 优化器提示，由 MySQL 服务端到点中止。
 *
 * <p>为什么需要：前端 axios 15s 超时后只是"不再理会"响应，服务端 SQL 照跑到底；
 * 切几次时间窗就在同一批大表上叠出一堆整窗扫描，占满 Hikari 连接和 IO，连带 ingest 事务变长、
 * 锁等待超时。服务端设一个略大于前端超时的上限，放弃的查询会被及时回收。
 *
 * <p>MAX_EXECUTION_TIME 只作用于只读 SELECT（MySQL 5.7.8+），INSERT/UPDATE/DELETE 与后台线程均不受影响。
 * gz
 */
@Configuration
public class QueryBudgetConfig {

    @Bean
    HibernatePropertiesCustomizer queryBudgetStatementInspector() {
        return props -> props.put(AvailableSettings.STATEMENT_INSPECTOR, new MaxExecutionTimeInspector());
    }

    static final class MaxExecutionTimeInspector implements StatementInspector {
        @Override
        public String inspect(String sql) {
            Long ms = QueryBudget.current();
            return ms == null ? sql : withMaxExecutionTime(sql, ms);
        }
    }

    /** 在最外层 SELECT 关键字后插入提示；非 SELECT 或已带该提示则原样返回。 */
    static String withMaxExecutionTime(String sql, long ms) {
        if (sql == null || ms <= 0) {
            return sql;
        }
        int i = 0;
        int n = sql.length();
        while (i < n && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        if (!sql.regionMatches(true, i, "select", 0, 6)) {
            return sql;
        }
        int after = i + 6;
        if (after < n && !Character.isWhitespace(sql.charAt(after))) {
            return sql;
        }
        if (sql.regionMatches(true, after, " /*+ MAX_EXECUTION_TIME", 0, 23)) {
            return sql;
        }
        return sql.substring(0, after) + " /*+ MAX_EXECUTION_TIME(" + ms + ") */" + sql.substring(after);
    }
}
