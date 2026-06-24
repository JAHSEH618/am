package com.am.server.service;

import com.am.server.domain.git.GitCommitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 渗透率（北极星指标）计算。
 *
 * <p>口径：渗透率 = AI协助提交的 lines_added / 全部非merge提交的 lines_added × 100。
 * 「AI 协助」由 {@link GitCommitRepository#aiPenetrationLines} 在查询时关联 ai_session 推断
 * （同人 + repo 归一化相等 + commit_time 落在会话活动 ±30min 窗口内）。
 *
 * <p>分母为 0 时返回 -1，前端据此显示「—」而非 0%。
 * gz
 */
@Service
@RequiredArgsConstructor
public class AiPenetrationService {

    private final GitCommitRepository gitCommitRepository;

    /** 计算指定窗口的渗透率百分比（0–100），无数据返回 -1。 */
    public int compute(PenetrationWindow window) {
        LocalDateTime from = window.from(LocalDateTime.now());
        List<Object[]> rows = gitCommitRepository.aiPenetrationLines(from);
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return -1;
        }
        Object[] r = rows.get(0);
        long assisted = toLong(r[0]);
        long total = toLong(r[1]);
        if (total <= 0) {
            return -1;
        }
        return (int) Math.round(assisted * 100.0 / total);
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
