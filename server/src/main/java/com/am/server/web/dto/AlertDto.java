package com.am.server.web.dto;

import com.am.server.domain.agent.AgentAlert;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警视图
 * gz
 */
@Data
public class AlertDto {

    private Long id;
    private String agentId;
    private String userCode;
    /** "姓名|工号"展示串；前端 Alerts 表直接显示这个 */
    private String userDisplay;
    private String hostHash;
    private String alertType;
    private String alertLevel;
    private String message;
    private LocalDateTime eventTime;

    public static AlertDto of(AgentAlert a) {
        AlertDto d = new AlertDto();
        d.id = a.getId();
        d.agentId = a.getAgentId();
        d.userCode = a.getUserCode();
        d.hostHash = a.getHostHash();
        d.alertType = a.getAlertType();
        d.alertLevel = a.getAlertLevel();
        d.message = a.getMessage();
        d.eventTime = a.getEventTime();
        return d;
    }
}
