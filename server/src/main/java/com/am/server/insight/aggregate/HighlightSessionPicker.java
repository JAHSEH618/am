package com.am.server.insight.aggregate;

import com.am.server.insight.domain.AiSessionAudit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 为单个员工挑出 6 个"典型会话"，让管理员**眼见为实**地理解该员工的画像（docs/design/employee-insight-from-ai-sessions-v1.0.md §5.3 Tab 6）。
 *
 * <p>挑选策略（按顺序追加，已经选过的不重复）：
 * <ol>
 *   <li>难度 ≥ 4 且 outcome=completed 的 2 个 —— 展示亮点</li>
 *   <li>capability_avg 最高的 2 个 —— 能力体现</li>
 *   <li>难度 ≥ 3 但 outcome=abandoned 的 2 个 —— 值得讨论的样本</li>
 * </ol>
 *
 * <p>不足 6 个时按 difficulty 倒序补足；最多 6 个。
 * gz
 */
@Component
public class HighlightSessionPicker {

    private static final int TARGET = 6;

    public List<Long> pick(List<AiSessionAudit> audits) {
        if (audits == null || audits.isEmpty()) {
            return List.of();
        }

        Set<Long> picked = new LinkedHashSet<>();

        // 1. 高难度且完成的 2 个
        audits.stream()
                .filter(a -> a.getDifficulty() >= 4 && "completed".equals(a.getOutcome()))
                .sorted(Comparator.<AiSessionAudit>comparingInt(AiSessionAudit::getDifficulty).reversed()
                        .thenComparing(this::capAvg, Comparator.reverseOrder()))
                .limit(2)
                .map(AiSessionAudit::getAiSessionId)
                .forEach(picked::add);

        // 2. capability_avg 最高的 2 个（已经在 1 里的跳过）
        audits.stream()
                .filter(a -> !picked.contains(a.getAiSessionId()))
                .sorted(Comparator.comparingDouble(this::capAvg).reversed())
                .limit(2)
                .map(AiSessionAudit::getAiSessionId)
                .forEach(picked::add);

        // 3. 难度 ≥ 3 且 abandoned 的 2 个
        audits.stream()
                .filter(a -> !picked.contains(a.getAiSessionId()))
                .filter(a -> a.getDifficulty() >= 3 && "abandoned".equals(a.getOutcome()))
                .sorted(Comparator.<AiSessionAudit>comparingInt(AiSessionAudit::getDifficulty).reversed())
                .limit(2)
                .map(AiSessionAudit::getAiSessionId)
                .forEach(picked::add);

        // 4. 不足 6 个：按 difficulty 倒序补
        if (picked.size() < TARGET) {
            Set<Long> already = new HashSet<>(picked);
            audits.stream()
                    .filter(a -> !already.contains(a.getAiSessionId()))
                    .sorted(Comparator.comparingInt(AiSessionAudit::getDifficulty).reversed())
                    .limit(TARGET - picked.size())
                    .map(AiSessionAudit::getAiSessionId)
                    .forEach(picked::add);
        }

        return new ArrayList<>(picked).stream().limit(TARGET).toList();
    }

    private double capAvg(AiSessionAudit a) {
        int sum = nz(a.getCapProblemDecomposition())
                + nz(a.getCapContextManagement())
                + nz(a.getCapDebuggingSkill())
                + nz(a.getCapToolOrchestration())
                + nz(a.getCapSelfCorrection());
        return sum / 5.0;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
