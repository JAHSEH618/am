package com.am.server.web.dto;

import lombok.Data;

/**
 * 大盘总览指标
 * v2.0 起去除成本视角，新增 aiPenetration（AI 渗透率）占位字段——具体数据由 Phase 3 git_commit
 * 模块接入后填充，当前阶段后端固定回 -1 表示"暂无数据"，前端按 -1 显示占位文案。
 * gz
 */
@Data
public class DashboardOverviewDto {

    private int onlineAgents;
    /**
     * ACTIVE 设备中，心跳已超出在线窗口的数量（与 onlineAgents 同一 status 粒度，online + offline = 全量 ACTIVE 台数）。
     */
    private int offlineAgents;
    private int activeAgents;
    /**
     * 当前真正活跃的 AI 会话数：最近 {@code ACTIVE_WINDOW_SECONDS} 内 last_activity 有更新，
     * 且 status != idle 的会话。和 {@link #activeAgents} 同源同口径，反映"此刻在跑活的"。
     */
    private int activeAiSessions;
    /**
     * 今日累计开过的 AI 会话数（不论现在是否还活跃）：基于 ai_session_event 在
     * [今日 0 点, now) 区间的 distinct ai_session_id。
     * <p>历史曾被错误地塞进 {@link #activeAiSessions}（导致"活跃会话"显示成历史空闲会话），
     * v2.5 起拆出独立字段，前端可单独展示"今日 N 个"作为活跃数的副信息。
     */
    private int todayAiSessions;
    private long todayOnlineSeconds;
    private long todayActiveSeconds;
    private long todayInputTokens;
    private long todayOutputTokens;
    private long todayMessages;
    private long todayToolCalls;
    private int todayProjects;
    private int todayUsers;

    /** AI 渗透率（北极星指标，0~100，整数百分比）；-1 表示尚未接入数据源。 */
    private int aiPenetrationPercent = -1;

    /**
     * {@code aiwatch.install.dir} 下 manifest.json 的 version——运维当前分发出去的客户端最新版本。
     * 未配置安装目录或 manifest 不可读时为 null；大屏可与每台机器的 agentVersion 对照。
     */
    private String latestAgentVersion;
}
