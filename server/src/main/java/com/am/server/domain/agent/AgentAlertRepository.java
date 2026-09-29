package com.am.server.domain.agent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Agent 告警 Repository
 * gz
 */
public interface AgentAlertRepository extends JpaRepository<AgentAlert, Long> {

    Page<AgentAlert> findByEventTimeBetweenOrderByEventTimeDesc(
            LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<AgentAlert> findByAlertTypeAndEventTimeBetweenOrderByEventTimeDesc(
            String alertType, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /**
     * 分批删除过期告警；每次调用独立事务，避免积压场景下一次性删几十万行产生巨型事务 / 长锁 / 大 binlog。
     * 走 {@code idx_event_time}（schema.sql 自 v1.2 起就有）做范围扫描。JPQL 的 DELETE 不支持 LIMIT，故用 native。
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM agent_alert WHERE event_time < :before LIMIT :limit", nativeQuery = true)
    int deleteExpiredBatch(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
