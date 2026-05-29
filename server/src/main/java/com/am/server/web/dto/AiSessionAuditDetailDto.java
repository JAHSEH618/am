package com.am.server.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.am.server.insight.domain.AiSessionAudit;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单次会话 Insight 评判完整视图（弹框详情用），映射 {@link AiSessionAudit} 全字段。
 * gz
 */
@Data
public class AiSessionAuditDetailDto {

    private Long id;
    private Long aiSessionId;
    private String userCode;
    private String targetType;
    private String auditVersion;

    /** 必须与前端 {@code judge_a_model} 对齐（全局 snake_case 会将 judgeAModel 误序成 judge_amodel） */
    @JsonProperty("judge_a_model")
    private String judgeAModel;

    @JsonProperty("judge_b_model")
    private String judgeBModel;

    /** 合成难度 */
    private Integer difficulty;
    private Integer difficultyA;
    private Integer difficultyB;

    private String outcome;
    private String mode;

    private Integer capProblemDecomposition;
    private Integer capContextManagement;
    private Integer capDebuggingSkill;
    private Integer capToolOrchestration;
    private Integer capSelfCorrection;

    /** 0 / 1 */
    private Integer judgeDisagreement;
    /** 双 judge 说明与合成依据 */
    private String judgeReasonText;

    private Integer messageCountAtAudit;
    private LocalDateTime auditedAt;

    public static AiSessionAuditDetailDto of(AiSessionAudit a) {
        AiSessionAuditDetailDto d = new AiSessionAuditDetailDto();
        d.setId(a.getId());
        d.setAiSessionId(a.getAiSessionId());
        d.setUserCode(a.getUserCode());
        d.setTargetType(a.getTargetType());
        d.setAuditVersion(a.getAuditVersion());
        d.setJudgeAModel(normModelStored(a.getJudgeAModel()));
        d.setJudgeBModel(normModelStored(a.getJudgeBModel()));
        d.setDifficulty(a.getDifficulty());
        d.setDifficultyA(a.getDifficultyA());
        d.setDifficultyB(a.getDifficultyB());
        d.setOutcome(a.getOutcome());
        d.setMode(a.getMode());
        d.setCapProblemDecomposition(a.getCapProblemDecomposition());
        d.setCapContextManagement(a.getCapContextManagement());
        d.setCapDebuggingSkill(a.getCapDebuggingSkill());
        d.setCapToolOrchestration(a.getCapToolOrchestration());
        d.setCapSelfCorrection(a.getCapSelfCorrection());
        d.setJudgeDisagreement(a.getJudgeDisagreement());
        d.setJudgeReasonText(a.getJudgeReasonText());
        d.setMessageCountAtAudit(a.getMessageCountAtAudit());
        d.setAuditedAt(a.getAuditedTime());
        return d;
    }

    /** 库里的空串 / NULL 不传成空串 UI，交由前端渲染为「—」 */
    private static String normModelStored(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        return v.trim();
    }
}
