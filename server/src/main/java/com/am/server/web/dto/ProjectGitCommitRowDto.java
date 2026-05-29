package com.am.server.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目透视 — 时间窗内某仓库的 Git 提交明细（弹框列表行）
 * gz
 */
@Data
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ProjectGitCommitRowDto {

    /** 仓库远程 URL；员工跨仓时会变化，项目透视单列仓时可填同源值 */
    private String repoUrl;

    private String commitHash;
    private LocalDateTime commitTime;

    private String userCode;
    /** 与员工展示一致：姓名|工号 */
    private String userDisplay;

    private String authorName;
    private String authorEmail;

    private String messageSubject;
    private String branchName;

    private int filesChanged;
    private int linesAdded;
    private int linesDeleted;

    /** git numstat 逐路径；旧数据或未上报时为空列表 */
    private List<PathStatEntry> pathStats;

    /** none / partial / full / skipped */
    private String detailStatus;

    @Data
    @NoArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class PathStatEntry {
        private String path;
        private int linesAdded;
        private int linesDeleted;
    }
}
