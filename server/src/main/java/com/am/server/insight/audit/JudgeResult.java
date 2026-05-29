package com.am.server.insight.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 judge 对一段会话的评判结果。
 *
 * <p>对应 rubric 的四组维度：
 * <ul>
 *   <li>difficulty 1-5</li>
 *   <li>outcome completed / partial / abandoned</li>
 *   <li>mode leverage / learning / dependent / exploratory / debugging</li>
 *   <li>5 维能力 1-5</li>
 * </ul>
 * gz
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JudgeResult {

    private String judgeModel;

    private int difficulty;

    private String outcome;

    private String mode;

    private int capProblemDecomposition;
    private int capContextManagement;
    private int capDebuggingSkill;
    private int capToolOrchestration;
    private int capSelfCorrection;

    /** 模型给出的简短解释，用于人工 spot-check。 */
    private String reason;
}
