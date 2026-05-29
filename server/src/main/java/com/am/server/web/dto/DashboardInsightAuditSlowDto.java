package com.am.server.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大屏：洞察审计进度「慢」指标（总有效会话 / 未审计口径）。
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardInsightAuditSlowDto {

    /** 启用 Agent 下的有效会话总数 */
    private long totalValidSessionCount;

    /** 尚未达到「稳定已审」口径的会话数 = total - stable（与慢接口内一次计算） */
    private long unauditedSessionCount;
}
