package com.am.server.agent.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单个 Provider 的监控快照
 * gz
 */
@Data
public class MonitorSnapshotDto {

    @NotBlank
    private String type;

    private String targetVersion;

    private LocalDateTime capturedAt;

    @Valid
    private List<MonitorSessionDto> sessions;

    /** 被归并到父 chat 的子 composer / 子 thread，服务端应标为 invalid 不再展示。 */
    private List<String> suppressedSessionIds;
}
