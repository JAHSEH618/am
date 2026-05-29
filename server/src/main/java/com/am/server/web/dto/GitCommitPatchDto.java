package com.am.server.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单文件 unified diff（已解压为 UTF-8 文本）。
 * gz
 */
@Data
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class GitCommitPatchDto {

    private String path;
    private String patch;
    private boolean patchTruncated;
    private String truncateReason;
    private boolean binary;
}
