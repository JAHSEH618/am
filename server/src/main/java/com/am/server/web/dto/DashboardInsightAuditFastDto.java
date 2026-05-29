package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大屏：洞察审计进度「快」指标（与队列剩余同频刷新）。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardInsightAuditFastDto {

    /** 已稳定完成洞察审计的会话数（DONE + 无需重审 + 消息未越过阈值） */
    private long stableAuditedSessionCount;

    /** 扫描队列中仍待 LLM 审计的会话数（与后台扫描器判定一致） */
    private long pendingAuditCount;
}
