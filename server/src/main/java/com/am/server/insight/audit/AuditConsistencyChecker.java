package com.am.server.insight.audit;

/**
 * 双 judge 一致性检查：把两份 JudgeResult 合成最终入库结果，并判定 disagreement。
 *
 * <p>规则（见 docs/design/employee-insight-from-ai-sessions-v1.0.md §4.3）：
 * <ul>
 *   <li>difficulty   abs(A-B) ≤ 1 → 取均值；否则视为 disagreement</li>
 *   <li>outcome      A==B → 接受；否则取 difficultyA 更高那一方</li>
 *   <li>5 维能力     abs(A_d - B_d) ≤ 1 → 取均值；否则视为 disagreement（按维度计）</li>
 *   <li>mode         A==B → 接受；否则取 difficulty 更高那一方（更可靠的视角）</li>
 * </ul>
 *
 * <p>{@link Combined#judgeDisagreement} = 1 表示本审计样本至少有 1 个维度差异超阈值；
 * 聚合时此样本权重 × 0.3（仍参与，避免完全丢弃浪费 LLM 成本）。
 * gz
 */
public final class AuditConsistencyChecker {

    private AuditConsistencyChecker() {
    }

    public static Combined combine(JudgeResult a, JudgeResult b) {
        boolean disagree = false;

        int diff = avgWithDisagreement(a.getDifficulty(), b.getDifficulty(), 1);
        if (Math.abs(a.getDifficulty() - b.getDifficulty()) > 1) {
            disagree = true;
        }

        String outcome;
        if (a.getOutcome().equals(b.getOutcome())) {
            outcome = a.getOutcome();
        } else {
            disagree = true;
            outcome = a.getDifficulty() >= b.getDifficulty() ? a.getOutcome() : b.getOutcome();
        }

        String mode;
        if (a.getMode().equals(b.getMode())) {
            mode = a.getMode();
        } else {
            disagree = true;
            mode = a.getDifficulty() >= b.getDifficulty() ? a.getMode() : b.getMode();
        }

        int problem = avgWithDisagreement(a.getCapProblemDecomposition(), b.getCapProblemDecomposition(), 1);
        int context = avgWithDisagreement(a.getCapContextManagement(), b.getCapContextManagement(), 1);
        int debugging = avgWithDisagreement(a.getCapDebuggingSkill(), b.getCapDebuggingSkill(), 1);
        int tool = avgWithDisagreement(a.getCapToolOrchestration(), b.getCapToolOrchestration(), 1);
        int self = avgWithDisagreement(a.getCapSelfCorrection(), b.getCapSelfCorrection(), 1);

        if (Math.abs(a.getCapProblemDecomposition() - b.getCapProblemDecomposition()) > 1
                || Math.abs(a.getCapContextManagement() - b.getCapContextManagement()) > 1
                || Math.abs(a.getCapDebuggingSkill() - b.getCapDebuggingSkill()) > 1
                || Math.abs(a.getCapToolOrchestration() - b.getCapToolOrchestration()) > 1
                || Math.abs(a.getCapSelfCorrection() - b.getCapSelfCorrection()) > 1) {
            disagree = true;
        }

        String reason = pickReason(a, b);

        return new Combined(diff, outcome, mode,
                problem, context, debugging, tool, self,
                disagree, reason);
    }

    /** A、B 差异 ≤ tolerance 视为一致 → 取均值（四舍五入）；否则同样取均值，但外层会标 disagree=1。 */
    private static int avgWithDisagreement(int a, int b, int tolerance) {
        return Math.round((a + b) / 2.0f);
    }

    /** 取偏长的 reason，截断到 500 字符避免 audit 表 reason 字段过长。 */
    private static String pickReason(JudgeResult a, JudgeResult b) {
        String ra = a.getReason() == null ? "" : a.getReason();
        String rb = b.getReason() == null ? "" : b.getReason();
        String merged = "[A:" + a.getJudgeModel() + "] " + ra + "\n[B:" + b.getJudgeModel() + "] " + rb;
        if (merged.length() > 500) {
            merged = merged.substring(0, 500);
        }
        return merged;
    }

    /** 合成后的最终评判结果。 */
    public record Combined(
            int difficulty,
            String outcome,
            String mode,
            int capProblemDecomposition,
            int capContextManagement,
            int capDebuggingSkill,
            int capToolOrchestration,
            int capSelfCorrection,
            boolean judgeDisagreement,
            String reason) {
    }
}
