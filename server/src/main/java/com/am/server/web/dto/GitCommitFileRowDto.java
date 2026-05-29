package com.am.server.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Git 提交单文件明细（列表，不含 patch 正文）。
 * gz
 */
@Data
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class GitCommitFileRowDto {

    private String path;
    private String oldPath;
    private String changeType;
    private int linesAdded;
    private int linesDeleted;
    private boolean binary;
    private boolean hasPatch;
    private boolean patchTruncated;
    private String truncateReason;
}
