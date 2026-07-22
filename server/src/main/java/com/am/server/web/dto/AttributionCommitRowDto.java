package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * /attribution commit 明细抽屉行：subject、行数、档位、归属会话（可跳 /sessions/:id 人工核查）。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttributionCommitRowDto {
    private Long commitId;
    private String commitHash;
    private String subject;
    private String userCode;
    private String projectName;
    private String repoUrl;
    private LocalDateTime commitTime;
    private int linesAdded;
    private int linesDeleted;
    private String tier;
    private String trailerKind;
    private Long sessionId;
    private String targetType;
    private String model;
    private Integer overlapSeconds;
    private boolean backfilled;
}
