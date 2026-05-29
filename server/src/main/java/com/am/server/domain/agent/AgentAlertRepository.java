package com.am.server.domain.agent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
