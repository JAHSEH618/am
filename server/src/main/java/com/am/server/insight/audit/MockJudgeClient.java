package com.am.server.insight.audit;

import com.am.server.insight.config.InsightProperties.JudgeConfig;
import org.springframework.stereotype.Component;

import java.util.zip.CRC32;

/**
 * Mock JudgeClient —— provider=mock 时启用。
 *
 * <p>用 prompt 的 CRC32 作为伪随机源，输出**确定性可重复**的评分，让流水线在没有真实 LLM
 * 的情况下也能跑通、且双 judge 互验逻辑（一致性、disagreement）可以被验证。
 *
 * <p>同一段 prompt 跑两次结果完全一致；不同长度 / 内容的 prompt 会被映射到不同分布的
 * difficulty / outcome / mode / capability，便于 dev 阶段做端到端 UI 调试。
 *
 * <p><b>生产上不允许用 mock</b>——把 application.yml 里 provider 改成 openai-compatible 即可。
 * gz
 */
@Component
public class MockJudgeClient implements JudgeClient {

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public JudgeResult judge(JudgeConfig config, String prompt) {
        long seed = seed(prompt + "|" + config.getModel());

        int difficulty = 1 + (int) (mod(seed, 5));
        String outcome = switch ((int) mod(seed >>> 3, 5)) {
            case 0, 1, 2 -> "completed";
            case 3 -> "partial";
            default -> "abandoned";
        };
        String mode = switch ((int) mod(seed >>> 7, 10)) {
            case 0, 1, 2, 3 -> "leverage";
            case 4, 5 -> "debugging";
            case 6 -> "learning";
            case 7 -> "exploratory";
            default -> "dependent";
        };

        // 给两个不同的 model 略不同的扰动，模拟双判会有偏差
        int salt = config.getModel() == null ? 0 : Math.abs(config.getModel().hashCode() % 3);

        return JudgeResult.builder()
                .judgeModel(config.getModel())
                .difficulty(difficulty)
                .outcome(outcome)
                .mode(mode)
                .capProblemDecomposition(cap(seed >>> 11, salt))
                .capContextManagement(cap(seed >>> 17, salt))
                .capDebuggingSkill(cap(seed >>> 23, salt))
                .capToolOrchestration(cap(seed >>> 29, salt))
                .capSelfCorrection(cap(seed >>> 31, salt))
                .reason("[mock] seed=" + seed + " difficulty=" + difficulty + " outcome=" + outcome)
                .build();
    }

    /** 把 prompt 映射到一个稳定的 long 种子；CRC32 已经够稳定且零依赖。 */
    private static long seed(String text) {
        if (text == null || text.isEmpty()) {
            return 1L;
        }
        CRC32 crc = new CRC32();
        crc.update(text.getBytes());
        return crc.getValue();
    }

    /** 5 档评分：把 long 的若干 bit 映射到 1..5。salt 用来在双 judge 间制造 ±1 的偏差。 */
    private static int cap(long bits, int salt) {
        int base = 1 + (int) mod(bits, 5);
        int v = base + (salt - 1); // salt ∈ {0,1,2} → 偏移 -1/0/+1
        if (v < 1) v = 1;
        if (v > 5) v = 5;
        return v;
    }

    private static long mod(long v, int m) {
        long r = v % m;
        return r < 0 ? r + m : r;
    }
}
