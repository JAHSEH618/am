package com.am.server.agent.security;

import com.am.server.domain.agent.AgentAlert;
import com.am.server.domain.agent.AgentAlertRepository;
import com.am.server.domain.agent.AlertType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 告警服务，提供基础写入入口供过滤器与业务层调用
 * gz
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AgentAlertRepository alertRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String agentId, String userCode, String hostHash,
                       String alertType, String alertLevel, String message) {
        AgentAlert alert = new AgentAlert();
        alert.setAgentId(agentId);
        alert.setUserCode(userCode);
        alert.setHostHash(hostHash);
        alert.setAlertType(alertType);
        alert.setAlertLevel(alertLevel);
        alert.setMessage(message != null && message.length() > 510 ? message.substring(0, 510) : message);
        alert.setEventTime(LocalDateTime.now());
        try {
            alertRepository.save(alert);
        } catch (Exception e) {
            log.warn("failed to persist alert {} {}: {}", alertType, agentId, e.getMessage());
        }
    }

    public void warn(String agentId, String userCode, String hostHash, String type, String message) {
        record(agentId, userCode, hostHash, type, AlertType.LEVEL_WARN, message);
    }

    public void error(String agentId, String userCode, String hostHash, String type, String message) {
        record(agentId, userCode, hostHash, type, AlertType.LEVEL_ERROR, message);
    }
}
