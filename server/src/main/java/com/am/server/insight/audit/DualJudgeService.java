package com.am.server.insight.audit;

import com.am.server.insight.config.InsightProperties;
import com.am.server.insight.config.InsightProperties.JudgeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 双 judge 编排：根据 InsightProperties 找 A / B 两个 JudgeClient，分别评判，再合成。
 *
 * <p>失败重试：单次 judge 失败时退避重试 3 次（500ms / 1500ms / 4500ms），全部失败抛
 * {@link JudgeException}，由上层 orchestrator 决定该 session 跳过。
 * gz
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DualJudgeService {

    private static final int MAX_RETRY = 3;
    private static final long[] BACKOFF_MS = {500L, 1500L, 4500L};

    private final InsightProperties properties;
    private final JudgeClientRegistry registry;
    private final java.util.concurrent.ExecutorService judgeCallExecutor;

    /**
     * 跑一次双 judge 并合成最终评判。
     *
     * @param prompt 拼接好的 prompt（rubric + 会话原文）
     * @return       合成后的 Combined + 单 judge 的原始评分（落库时同时记录 A/B 难度）
     */
    public Outcome judge(String prompt) {
        JudgeConfig aCfg = properties.getJudgeA();
        JudgeConfig bCfg = properties.getJudgeB();
        JudgeClient a = registry.require(aCfg.getProvider());
        JudgeClient b = registry.require(bCfg.getProvider());

        java.util.concurrent.CompletableFuture<JudgeResult> fa =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> callWithRetry(a, aCfg, prompt, "A"), judgeCallExecutor);
        java.util.concurrent.CompletableFuture<JudgeResult> fb =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> callWithRetry(b, bCfg, prompt, "B"), judgeCallExecutor);

        JudgeResult ra = join(fa, "A");
        JudgeResult rb = join(fb, "B");
        AuditConsistencyChecker.Combined combined = AuditConsistencyChecker.combine(ra, rb);

        return new Outcome(ra, rb, combined);
    }

    private JudgeResult callWithRetry(JudgeClient client, JudgeConfig cfg, String prompt, String tag) {
        JudgeException last = null;
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                return client.judge(cfg, prompt);
            } catch (JudgeException e) {
                last = e;
                long backoff = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
                log.warn("Judge {} attempt {}/{} failed: {} ; backoff {} ms",
                        tag, attempt + 1, MAX_RETRY, e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new JudgeException("interrupted while backoff", ie);
                }
            }
        }
        throw last != null ? last : new JudgeException("judge " + tag + " failed without exception");
    }

    private JudgeResult join(java.util.concurrent.CompletableFuture<JudgeResult> f, String tag) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JudgeException("interrupted while joining judge " + tag, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JudgeException je) {
                throw je;
            }
            throw new JudgeException("judge " + tag + " failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        }
    }

    public record Outcome(JudgeResult judgeA, JudgeResult judgeB,
                          AuditConsistencyChecker.Combined combined) {
    }
}
