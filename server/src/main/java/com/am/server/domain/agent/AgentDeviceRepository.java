package com.am.server.domain.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Agent 设备 Repository
 * gz
 */
public interface AgentDeviceRepository extends JpaRepository<AgentDevice, Long> {

    Optional<AgentDevice> findByAgentId(String agentId);

    Optional<AgentDevice> findByUserCodeAndHostHash(String userCode, String hostHash);

    List<AgentDevice> findByStatusAndLastSeenTimeAfter(String status, LocalDateTime since);

    long countByStatusAndLastSeenTimeAfter(String status, LocalDateTime since);

    long countByStatus(String status);

    /**
     * ACTIVE 设备且心跳早于在线窗口上沿（含从未上报 last_seen=null）——与 {@link #findByStatusAndLastSeenTimeAfter}
     * 互斥覆盖全量 ACTIVE 台账。
     */
    @Query("""
            SELECT d FROM AgentDevice d
            WHERE d.status = :status
              AND (d.lastSeenTime IS NULL OR d.lastSeenTime <= :cutoff)
            ORDER BY d.lastSeenTime ASC NULLS FIRST""")
    List<AgentDevice> findByStatusAndOfflineBefore(
            @Param("status") String status,
            @Param("cutoff") LocalDateTime cutoff);
}
