package com.am.server.domain.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Agent 防重放 nonce Repository
 * gz
 */
public interface AgentNonceRepository extends JpaRepository<AgentNonce, Long> {

    boolean existsByAgentIdAndNonce(String agentId, String nonce);

    /**
     * 分批删除过期 nonce；每次调用独立事务，避免积压场景下一次性删百万行产生巨型事务 / 长锁 / 大 binlog。
     * 走 idx_created_time。JPQL 的 DELETE 不支持 LIMIT，故用 native。
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM agent_nonce WHERE created_time < :before LIMIT :limit", nativeQuery = true)
    int deleteExpiredBatch(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
