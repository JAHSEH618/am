package com.am.server.web.dto;

import com.am.server.domain.ai.AiSession;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 会话视图
 * gz
 */
@Data
public class AiSessionDto {

    private Long id;
    private String targetType;
    private String externalSessionId;
    private String agentId;
    private String userCode;
    /** "姓名|工号"展示串；前端 Sessions / SessionDetail 列表直接显示这个 */
    private String userDisplay;
    private String hostHash;
    private String cwd;
    private String gitBranch;
    private String repoUrl;
    private String projectName;
    /** 字段命名为 worktree 而非 isWorktree，避免 Lombok / Jackson 把 boolean isXxx 序列化成 xxx */
    private boolean worktree;
    private String mainRepo;
    private String model;
    private String status;
    private String currentTool;
    private LocalDateTime startedAt;
    private LocalDateTime lastActivity;
    private LocalDateTime endedAt;
    private int userMessages;
    private int assistantMessages;
    private int totalMessages;
    /** DB 已入库消息总行数（详情页回填进度用）。 */
    private Integer storedMessageCount;
    /** DB 已入库 user 消息数（详情页展示与对话 Tab 对齐）。 */
    private Integer storedUserMessages;
    /** DB 已入库 assistant 消息数。 */
    private Integer storedAssistantMessages;
    /** DB 对话视图条数：user + assistant + subagent。 */
    private Integer storedConversationCount;
    /** Agent 最近一次快照 recent_messages 条数。 */
    private Integer reportedSnapshotMessages;
    private long inputTokens;
    private long outputTokens;
    private long cacheCreateTokens;
    private long cacheReadTokens;

    /**
     * 窗内消息数：当 list / detail 接口带了 from/to 时回填。
     * <p>详情页：优先为 message 表「对话」(user+assistant+subagent) 窗内条数，与对话 Tab 一致；
     * 列表页仍走 event 聚合（兼容无 recentMessages 的 provider）。
     */
    private Integer windowMessageCount;
    /** 窗内 token 总量（input + output）。同上，无窗口为 null */
    private Long windowTokens;

    /**
     * 无效会话原因（v2.11）。NULL = 有效会话；非空时前端会在 SessionDetail 顶部展示 banner，
     * 列表页默认不展示这种会话（除非 include_invalid=1）。
     */
    private String invalidReason;

    /**
     * Insight 评判缓存摘要；无 {@code ai_session_audit} 行时为 null，前端展示「未审计」。
     */
    private AiSessionAuditSummaryDto audit;

    /**
     * 列表接口在带时间窗时回填：窗内 user 消息 {@code slash_hits_json} 合并去重后的快捷调用枚举；
     * SSE {@code session_changed} 快照不携带本字段（JSON 中为 null），前端合并行数据时需保留列表原值。
     */
    private List<SlashInvocationDto> slashInvocations;

    public static AiSessionDto of(AiSession s) {
        AiSessionDto d = new AiSessionDto();
        d.id = s.getId();
        d.targetType = s.getTargetType();
        d.externalSessionId = s.getExternalSessionId();
        d.agentId = s.getAgentId();
        d.userCode = s.getUserCode();
        d.hostHash = s.getHostHash();
        d.cwd = s.getCwd();
        d.gitBranch = s.getGitBranch();
        d.repoUrl = s.getRepoUrl();
        d.projectName = s.getProjectName();
        d.worktree = s.getIsWorktree() != null && s.getIsWorktree() == 1;
        d.mainRepo = s.getMainRepo();
        d.model = s.getModel();
        d.status = s.getStatus();
        d.currentTool = s.getCurrentTool();
        d.startedAt = s.getStartedAt();
        d.lastActivity = s.getLastActivity();
        d.endedAt = s.getEndedAt();
        d.userMessages = s.getUserMessages() == null ? 0 : s.getUserMessages();
        d.assistantMessages = s.getAssistantMessages() == null ? 0 : s.getAssistantMessages();
        d.totalMessages = s.getTotalMessages() == null ? 0 : s.getTotalMessages();
        d.reportedSnapshotMessages = s.getReportedSnapshotMessages() == null ? 0 : s.getReportedSnapshotMessages();
        d.inputTokens = s.getInputTokens() == null ? 0 : s.getInputTokens();
        d.outputTokens = s.getOutputTokens() == null ? 0 : s.getOutputTokens();
        d.cacheCreateTokens = s.getCacheCreateTokens() == null ? 0 : s.getCacheCreateTokens();
        d.cacheReadTokens = s.getCacheReadTokens() == null ? 0 : s.getCacheReadTokens();
        d.invalidReason = s.getInvalidReason();
        return d;
    }
}
