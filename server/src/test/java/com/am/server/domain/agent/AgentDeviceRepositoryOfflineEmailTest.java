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
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * markOfflineEmailSent 是 @Modifying 的定向 UPDATE：只动 last_offline_email_time，
 * 不许把离线告警任务筛选时刻读到的旧 last_seen 等列写回（覆盖发信期间设备刚上报的心跳）。
 * 真 MySQL；事务在用例后回滚。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class AgentDeviceRepositoryOfflineEmailTest {

    private static final String AGENT = "a-offline-email-test";

    @Autowired
    private AgentDeviceRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void updatesOnlyTheAlertColumnAndLeavesAFresherLastSeenAlone() {
        LocalDateTime stale = LocalDateTime.now().minusHours(5).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime heartbeat = LocalDateTime.now().minusMinutes(1).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime sentAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        AgentDevice d = new AgentDevice();
        d.setAgentId(AGENT);
        d.setUserCode("u-offline-email");
        d.setHostHash("h-offline-email");
        d.setAgentSecret("secret");
        d.setLastSeenTime(stale);
        repository.saveAndFlush(d); // 告警任务筛选设备时读到的就是这份（last_seen 已过期）

        // 发信期间设备恢复上报：库里的 last_seen 变新（走 JDBC 绕开持久化上下文，模拟另一个请求线程）
        jdbc.update("UPDATE agent_device SET last_seen_time = ? WHERE agent_id = ?", Timestamp.valueOf(heartbeat), AGENT);

        int rows = repository.markOfflineEmailSent(AGENT, sentAt);

        assertThat(rows).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_seen_time FROM agent_device WHERE agent_id = ?",
                Timestamp.class, AGENT).toLocalDateTime())
                .as("last_seen 不能被回写成筛选时刻的旧值").isEqualTo(heartbeat);
        assertThat(jdbc.queryForObject("SELECT last_offline_email_time FROM agent_device WHERE agent_id = ?",
                Timestamp.class, AGENT).toLocalDateTime())
                .as("告警列被更新").isEqualTo(sentAt);
    }

    @Test
    void unknownAgentUpdatesNothing() {
        assertThat(repository.markOfflineEmailSent("no-such-agent", LocalDateTime.now())).isZero();
    }
}
