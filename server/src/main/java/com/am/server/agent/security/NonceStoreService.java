package com.am.server.agent.security;

import com.am.server.domain.agent.AgentNonceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Agent 防重放 nonce 服务
 * 通过 (agent_id, nonce) 唯一索引 + INSERT 报错来识别重放
 *
 * 注意：使用 JdbcTemplate 直插以避开 JPA 事务粘连——
 * 在 @Transactional 内捕获 DataIntegrityViolationException 仍会触发
 * UnexpectedRollbackException（事务被标记 rollback-only）。
 *
 * gz
 */
@Service
@RequiredArgsConstructor
public class NonceStoreService {

    private static final Logger log = LoggerFactory.getLogger(NonceStoreService.class);

    private static final String INSERT_SQL =
            "INSERT INTO agent_nonce (agent_id, nonce, timestamp_value, created_time) VALUES (?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final AgentNonceRepository nonceRepository;

    /**
     * 尝试占用一个 nonce。占用成功返回 true；已存在返回 false（说明重放）
     */
    public boolean tryClaim(String agentId, String nonce, String timestamp) {
        try {
            jdbcTemplate.update(INSERT_SQL,
                    agentId, nonce, timestamp, Timestamp.valueOf(LocalDateTime.now()));
            return true;
        } catch (DuplicateKeyException e) {
            log.warn("nonce replay detected: agentId={} nonce={}", agentId, nonce);
            return false;
        }
    }

    @Transactional
    public int deleteExpired(LocalDateTime before) {
        int deleted = nonceRepository.deleteExpired(before);
        if (deleted > 0) {
            log.info("nonce expired cleanup: deleted={}", deleted);
        }
        return deleted;
    }
}
