package com.am.server.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 项目透视列表项（v2.1 Phase 2）
 * 一行 = 一个 project_name 在窗口期内的聚合视图
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ProjectSummaryDto {
    private String projectName;
    private String repoUrl;

    private int sessionCount;
    private int messageCount;
    /**
     * 发送/接收：与大盘 Top 项目同口径——last_activity 落在窗内的会话求 SUM(userMessages)/SUM(assistantMessages)。
     */
    private int userMessageCount;
    private int assistantMessageCount;
    /** Token in/out：同上窗口对会话表 inputTokens / outputTokens 求和（与会话列表累计字段同源）。 */
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private int userCount;

    /** 窗口内 git_commit 行数（按 repo_url 与 AI 会话元数据对齐）；无仓库 URL 或未上报时为 0 */
    private int gitCommitCount;

    private LocalDateTime lastActivity;

    /** Top 模型（按 token 加权） */
    private String topModel;
}
