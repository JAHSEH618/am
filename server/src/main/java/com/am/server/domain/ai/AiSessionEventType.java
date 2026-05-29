package com.am.server.domain.ai;

/**
 * AI 会话事件类型
 * gz
 */
public enum AiSessionEventType {

    /** 会话首次出现 */
    SESSION_OPEN,
    /** 会话超时被关闭 */
    SESSION_CLOSE,
    /** 活动状态变更（idle ↔ thinking ↔ writing 等） */
    STATUS_CHANGE,
    /** 工具调用（每次解析到新工具就落一行） */
    TOOL_CALL,
    /** 消息数变化（user/assistant 新增） */
    MESSAGE_DELTA,
    /** token 数变化 */
    TOKEN_DELTA
}
