package com.am.server.domain.agent;

import com.am.server.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * deleteExpiredBatch 是 native SQL（JPQL 的 DELETE 不支持 LIMIT），参数化 LIMIT 只有真跑一次才知道绑得上绑不上；
 * 同时确认范围删除走 idx_event_time（不需要新 DDL）。事务在每个用例后回滚。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class AgentAlertRepositoryTest {

    private static final String AGENT = "a-alert-retention-test";

    @Autowired
    private AgentAlertRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deleteExpiredBatch_honoursCutoffAndLimit() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 5; i++) {
            insert("old-" + i, now.minusDays(45));
        }
        insert("fresh", now.minusDays(1));

        int first = repository.deleteExpiredBatch(now.minusDays(30), 2);
        assertThat(first).as("LIMIT 必须真正生效").isEqualTo(2);
        int rest = repository.deleteExpiredBatch(now.minusDays(30), 100);
        assertThat(rest).isEqualTo(3);
        assertThat(repository.deleteExpiredBatch(now.minusDays(30), 100)).isZero();

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_alert WHERE agent_id = ?", Integer.class, AGENT);
        assertThat(remaining).as("窗口内的告警不能被删").isEqualTo(1);
    }

    @Test
    void rangeDeleteUsesTheEventTimeIndex() {
        // 不需要为保留期清理新增 DDL：schema.sql 自 v1.2 起就有 idx_event_time。
        List<Map<String, Object>> idx = jdbcTemplate.queryForList("SHOW INDEX FROM agent_alert");
        assertThat(idx).anySatisfy(row -> {
            assertThat(row.get("Key_name")).isEqualTo("idx_event_time");
            assertThat(row.get("Column_name")).isEqualTo("event_time");
        });
    }

    private void insert(String message, LocalDateTime eventTime) {
        jdbcTemplate.update(
                "INSERT INTO agent_alert (agent_id, alert_type, alert_level, message, event_time, created_time)"
                        + " VALUES (?, 'SIGNATURE_INVALID', 'WARN', ?, ?, ?)",
                AGENT, message, Timestamp.valueOf(eventTime), Timestamp.valueOf(eventTime));
    }
}
