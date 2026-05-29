package com.am.server.agent.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 通用上报请求体
 * gz
 */
@Data
public class AgentReportRequest {

    @NotBlank
    private String agentId;

    private String agentVersion;
    private String binaryHash;

    private LocalDateTime capturedAt;

    @Valid
    private DeviceStateDto deviceState;

    @Valid
    private List<MonitorSnapshotDto> monitors;
}
