package com.am.server.agent.api.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 会话消息结构化内容段，与 agent monitor.ContentPart 对齐。
 * gz
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ContentPartDto {

    private String type;
    private String text;
    private String mime;
    private String path;
    private String oldPath;
    private String language;
    private Integer startLine;
    private Integer endLine;
    private String toolName;
    private String argumentsJson;
    /** 上报时内联小 blob；入库后剥离，仅存 blob_id */
    private String blobSha256;
    private String blobGzipBase64;
    private Long blobId;
    private Integer width;
    private Integer height;
    private Boolean truncated;
    private String truncateReason;
    private Integer sortOrder;
}
