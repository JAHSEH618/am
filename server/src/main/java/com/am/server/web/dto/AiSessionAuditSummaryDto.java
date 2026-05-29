package com.am.server.web.dto;

import com.am.server.insight.domain.AiSessionAudit;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表 / 详情中附带的 Insight 评判摘要（{@code ai_session_audit}）。
 * gz
 */
@Data
public class AiSessionAuditSummaryDto {

    /** 合成难度（1–5），双 judge 归并结果 */
    private Integer difficulty;

    /** completed / partial / abandoned */
    private String outcome;

    /** leverage / learning / dependent / exploratory / debugging */
    private String mode;

    private LocalDateTime auditedAt;

    /** 写入 ai_session_audit 的 rubric / 流水线版本 */
    private String auditVersion;

    public static AiSessionAuditSummaryDto of(AiSessionAudit a) {
        if (a == null) return null;
        AiSessionAuditSummaryDto d = new AiSessionAuditSummaryDto();
        d.setDifficulty(a.getDifficulty());
        d.setOutcome(a.getOutcome());
        d.setMode(a.getMode());
        d.setAuditedAt(a.getAuditedTime());
        d.setAuditVersion(a.getAuditVersion());
        return d;
    }
}
