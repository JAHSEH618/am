package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大盘 Top 项目 / Top 员工 通用 DTO
 *
 * <p>{@code key} 是数据维度（项目名 / 员工 user_code），用于服务端聚合的 group key 与前端跳转参数；
 * {@code displayLabel} 是 UI 渲染用的展示名（员工："姓名|工号"，项目：项目名），允许前端不做映射直接渲染。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopItemDto {
    private String key;
    /** UI 渲染用的展示文本；员工时由 EmployeeDisplayService 拼为"姓名|工号" */
    private String displayLabel;
    private long sessionCount;
    /**
     * 主消息数（来自 ai_session_event.messagesDelta 窗内累加）——做总量与排序，不分 role。
     * <p>历史字段保留，前端默认展示走 {@code userMessageCount}/{@code assistantMessageCount}（与
     * AI 会话列表 "user/assistant" 口径一致），后端两条统计同时下发。
     */
    private long messageCount;
    private long tokenCount;
    private long extraCount;
    /**
     * 来源：{@code AiSessionRepository.aggregateUserAssistantMessagesByProject/ByUser}，
     * 对窗内有活动的 ai_session 的 user_messages / assistant_messages 求和。
     * <p>与 AI 会话列表 "X/Y" 同口径（会话生命周期累计），不依赖 ai_session_message 表，
     * 因此不会因为 v1.0.0 客户端的 tail(10/40) 截断而偏小。
     */
    private long userMessageCount;
    private long assistantMessageCount;
    /**
     * 窗内 input/output token（来自 ai_session 累计字段，last_activity 切片），供 Top 表「in / out」展示；
     * 与 {@link #tokenCount}（event 流 tokens_delta 合计）口径不同，数值可能略有不一致。
     */
    private long inputTokenCount;
    private long outputTokenCount;

    /** 兼容旧的 5 参构造（项目无 displayLabel 时直接复用 key） */
    public TopItemDto(String key, long sessionCount, long messageCount, long tokenCount, long extraCount) {
        this(key, key, sessionCount, messageCount, tokenCount, extraCount, 0L, 0L, 0L, 0L);
    }

    /** 兼容旧的 6 参构造（含 displayLabel，没有 user/assistant 拆分） */
    public TopItemDto(String key, String displayLabel,
                      long sessionCount, long messageCount, long tokenCount, long extraCount) {
        this(key, displayLabel, sessionCount, messageCount, tokenCount, extraCount, 0L, 0L, 0L, 0L);
    }
}
