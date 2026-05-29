package com.am.server.insight.aggregate;

import com.am.server.insight.aggregate.PercentileCalculator.FivePercentiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Watchlist 评估器：对单个用户在窗口内的 UserMetrics 做 6 类信号判定。
 *
 * <p>窗口级（docs/design/employee-insight-from-ai-sessions-v1.0.md §3）：所有规则在单一窗口内判定，不跨窗口（"连续 N 周"类规则前端做对比，
 * 不进 watchlist）。
 *
 * <p>每个 flag 都有最小样本门槛（session_count / commit_count），避免小样本误报。
 * gz
 */
@Component
public class WatchlistEvaluator {

    public static final String HIGH_INVEST_LOW_OUTPUT = "high_invest_low_output";
    public static final String HIGH_REVERT = "high_revert";
    public static final String LOW_DIFFICULTY = "low_difficulty";
    public static final String HIGH_DEPENDENT = "high_dependent";
    public static final String DEBUGGING_LOOP = "debugging_loop";
    public static final String SILENT_PRODUCTIVE = "silent_productive";

    /** 小团队 watchlist 最小会话门槛（设计文档原 30，≤15 人团队降为 15）。 */
    private static final int MIN_SESSIONS_FOR_WATCHLIST = 15;

    public List<String> evaluate(UserMetrics m, TeamBaselines baselines) {
        List<String> flags = new ArrayList<>();
        if (m == null || m.isInsufficientData()) {
            return flags;
        }

        // 高投入低产出：时长 P75↑ 且 commits/hour P25↓
        if (m.getSessionCount() >= MIN_SESSIONS_FOR_WATCHLIST
                && nz(m.getAiActiveHours()) >= baselines.activeHours().p75()
                && safe(m.getAiCommitsPerActiveHour()) <= baselines.commitsPerHour().p25()) {
            flags.add(HIGH_INVEST_LOW_OUTPUT);
        }

        // 高回滚率：revert > 25% 且 commit ≥ 5
        if (m.getAiCommitCount() >= 5
                && safe(m.getCommitRevertRate()) > 0.25) {
            flags.add(HIGH_REVERT);
        }

        // 任务难度持续偏低：难度 1-2 占比 > 80% 且 session ≥ 门槛
        if (m.getSessionCount() >= MIN_SESSIONS_FOR_WATCHLIST && m.getDifficultyDist() != null) {
            int total = m.getSessionCount();
            int low = m.getDifficultyDist().getOrDefault(1, 0)
                    + m.getDifficultyDist().getOrDefault(2, 0);
            if (low * 1.0 / total > 0.80) {
                flags.add(LOW_DIFFICULTY);
            }
        }

        // 依赖型模式偏高：mode.dependent > 35% 且 session ≥ 门槛
        if (m.getSessionCount() >= MIN_SESSIONS_FOR_WATCHLIST && m.getModeDist() != null
                && m.getModeDist().getOrDefault("dependent", 0.0) > 0.35) {
            flags.add(HIGH_DEPENDENT);
        }

        // 调试循环：debugging 占比高且产出效率低
        if (m.getSessionCount() >= MIN_SESSIONS_FOR_WATCHLIST && m.getModeDist() != null
                && m.getModeDist().getOrDefault("debugging", 0.0) > 0.40
                && safe(m.getAiCommitsPerActiveHour()) <= baselines.commitsPerHour().p25()) {
            flags.add(DEBUGGING_LOOP);
        }

        // 沉默 + 产出正常：session ≤ P10 且 commits/hour ≥ P50
        // 注意：commits/hour 在 ai_active_hours 极小时会失真，这里加一道"commit ≥ 3"门
        if (nz(m.getSessionCount()) <= baselines.sessionCount().p10()
                && m.getAiCommitCount() >= 3
                && safe(m.getAiCommitsPerActiveHour()) >= baselines.commitsPerHour().p50()) {
            flags.add(SILENT_PRODUCTIVE);
        }

        return flags;
    }

    private static double safe(Double v) {
        return v == null || v.isNaN() ? 0.0 : v;
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }

    /**
     * 团队级 5 档分位基线，构造一次给同一报告所有用户复用。
     */
    public record TeamBaselines(
            FivePercentiles sessionCount,
            FivePercentiles activeHours,
            FivePercentiles commitsPerHour) {
    }
}
