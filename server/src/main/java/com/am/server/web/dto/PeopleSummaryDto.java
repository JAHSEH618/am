package com.am.server.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 员工数据列表项（v2.1 Phase 2）
 * 一行 = 一位员工在窗口期内的聚合视图
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PeopleSummaryDto {

    private String userCode;
    /** "姓名|工号"展示串；姓名为空 / 员工未注册时回退到 userCode。前端表格直接显示这个字段 */
    private String userDisplay;

    /** 窗口内已有 daily_summary 的天数（≤ window_elapsed_days） */
    private int daysWithData;
    /** 窗口内已过的自然日数（含首尾）：min(to, today) − from + 1；日均协作时长分母 */
    private int windowElapsedDays;

    private long aiActiveSecondsTotal;
    /** 日均 AI 协作时长（秒） */
    private long aiActiveSecondsAvg;
    /**
     * 窗口期日均 AI 协作时长（区间并集口径，秒）：SUM(union) / window_elapsed_days。
     * 未到的未来日期不计入分母；无汇总行的已过日期视为 0 秒。
     */
    private long aiActiveSecondsUnionAvg;

    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalTokens;

    private int aiMessageCountTotal;
    /**
     * 选定窗口内 {@code role=user} 的消息条数；员工数据展示为「提问次数」。
     * <p>依赖各 Provider 上报的 message 样本（部分客户端仅上报最近 N 条）。
     */
    private long userMessageCount;
    /**
     * 选定窗口内 {@code role=assistant} 的消息条数。
     */
    private long assistantMessageCount;
    /**
     * 问答比 = assistant / user；窗口内无用户消息时为 null。
     */
    private Double qaRatio;
    private int aiSessionCountTotal;
    /** 窗口内用户主动 Slash Commands 次数（{@code slash_command_count} 之和，非 TOOL_CALL 事件） */
    private int toolCallCountTotal;
    private int retryCountTotal;
    private int aiCommitCountTotal;
    /**
     * 时间窗内 {@code git_commit} 表按 {@code user_code} 统计的提交条数（与项目透视 Git 列同源）；
     * 不同于 {@link #aiCommitCountTotal}（来自 daily_summary 的 AI 协作提交聚合）。
     */
    private long gitCommitWindowCount;
    private int firstResponseAvgMs;

    /** Top 模型（按 token 加权） */
    private String topModel;
}
