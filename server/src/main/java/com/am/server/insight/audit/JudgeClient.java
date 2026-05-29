package com.am.server.insight.audit;

import com.am.server.insight.config.InsightProperties.JudgeConfig;

/**
 * Judge LLM 客户端抽象。
 * 一个具体实现 = 一种 provider（mock / openai-compatible / ...）。
 *
 * <p>{@link DualJudgeService} 会同时持有两个实现（judge A & judge B），各跑一次后做一致性比对。
 * gz
 */
public interface JudgeClient {

    /** 返回 provider 标识（mock / openai-compatible）。 */
    String provider();

    /**
     * 评判一段会话。
     *
     * @param config   provider 对应的配置（endpoint / key / model / timeout）
     * @param prompt   组装好的 prompt（含 rubric + 会话原文）
     * @return         单 judge 的评判结果，必须 5 维度评分 + difficulty + outcome + mode 全部就绪
     * @throws JudgeException 当 LLM 返回不可解析、不合规、或网络超时时抛出
     */
    JudgeResult judge(JudgeConfig config, String prompt) throws JudgeException;
}
