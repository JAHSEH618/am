package com.am.server.domain.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * Agent 防重放 nonce Repository
 * gz
 */
public interface AgentNonceRepository extends JpaRepository<AgentNonce, Long> {

    boolean existsByAgentIdAndNonce(String agentId, String nonce);

    @Modifying
    @Query("delete from AgentNonce n where n.createdTime < :before")
    int deleteExpired(@Param("before") LocalDateTime before);
}
