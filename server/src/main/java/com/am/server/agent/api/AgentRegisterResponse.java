package com.am.server.agent.api;

import com.am.server.agent.api.dto.MonitorAgentPolicyDto;
import lombok.Builder;
import lombok.Data;

/**
 * Agent 注册响应 DTO
 * gz
 */
@Data
@Builder
public class AgentRegisterResponse {

    private String agentId;
    private String agentSecret;
    private long reportIntervalMs;
    private long timestampWindowMs;

    /** 与 {@link MonitorAgentPolicyDto} 对齐；服务端始终下发便于新装首开即有策略。 */
    private MonitorAgentPolicyDto monitorPolicy;
}
