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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * deleteExpiredBatch 是 native SQL（JPQL 的 DELETE 不支持 LIMIT），参数化 LIMIT 只有真跑一次
 * 才知道绑得上绑不上——所以这里打真 MySQL。事务在每个用例后回滚。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class AgentNonceRepositoryTest {

    private static final String AGENT = "a-nonce-batchtest";

    @Autowired
    private AgentNonceRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deleteExpiredBatch_honoursCutoffAndLimit() {
        LocalDateTime now = LocalDateTime.now();
        // created_time 是 @CreatedDate + updatable=false，save() 拿不到过去的时间，只能直插。
        for (int i = 0; i < 5; i++) {
            insertNonce("old-" + i, now.minusHours(48));
        }
        insertNonce("fresh-0", now.minusMinutes(1));

        int firstBatch = repository.deleteExpiredBatch(now.minusHours(24), 2);
        assertThat(firstBatch).as("LIMIT 必须真正生效，否则积压场景会一把删成巨型事务").isEqualTo(2);

        int rest = repository.deleteExpiredBatch(now.minusHours(24), 100);
        assertThat(rest).as("剩下的 3 行过期行").isEqualTo(3);

        int noneLeft = repository.deleteExpiredBatch(now.minusHours(24), 100);
        assertThat(noneLeft).as("删空后返回 0，服务层据此跳出循环").isZero();

        assertThat(countRemaining()).as("窗口内的新 nonce 不能被删掉").isEqualTo(1);
    }

    private void insertNonce(String nonce, LocalDateTime createdTime) {
        jdbcTemplate.update(
                "INSERT INTO agent_nonce (agent_id, nonce, timestamp_value, created_time) VALUES (?, ?, ?, ?)",
                AGENT, nonce, "0", Timestamp.valueOf(createdTime));
    }

    private Integer countRemaining() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_nonce WHERE agent_id = ?", Integer.class, AGENT);
    }
}
