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
    /** 活跃时段快速上报间隔（毫秒）；客户端 active=true 时切到此节奏，空闲回落 reportIntervalMs。 */
    private long activeReportIntervalMs;
    private long timestampWindowMs;

    /** 与 {@link MonitorAgentPolicyDto} 对齐；服务端始终下发便于新装首开即有策略。 */
    private MonitorAgentPolicyDto monitorPolicy;
}
