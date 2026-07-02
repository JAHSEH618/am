package com.am.server.insight.audit;

import com.am.server.insight.config.InsightProperties;
import com.am.server.insight.config.InsightProperties.JudgeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DualJudgeServiceParallelTest {

    private final ExecutorService judgeCallExecutor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        judgeCallExecutor.shutdownNow();
    }

    /** A、B 各在返回前 await 同一个 CyclicBarrier(2);唯有并发执行两者才都能到达栅栏并放行。
     *  若仍是串行,A 独自等栅栏 → 2s 超时 → BrokenBarrier → JudgeException,测试失败。 */
    @Test
    void runsJudgeAAndBConcurrently() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        JudgeClient judgeA = new BarrierJudge("prov-a", barrier);
        JudgeClient judgeB = new BarrierJudge("prov-b", barrier);

        JudgeClientRegistry registry = new JudgeClientRegistry(List.of(judgeA, judgeB));
        registry.init(); // 包级可见,填 provider→client 映射

        InsightProperties props = new InsightProperties();
        props.getJudgeA().setProvider("prov-a");
        props.getJudgeB().setProvider("prov-b");

        DualJudgeService svc = new DualJudgeService(props, registry, judgeCallExecutor);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            DualJudgeService.Outcome outcome = svc.judge("prompt");
            assertThat(outcome.combined()).isNotNull();
            assertThat(outcome.judgeA()).isNotNull();
            assertThat(outcome.judgeB()).isNotNull();
        });
    }

    /** 返回前先卡在共享栅栏,证明两判官同时在跑。 */
    static final class BarrierJudge implements JudgeClient {
        private final String provider;
        private final CyclicBarrier barrier;

        BarrierJudge(String provider, CyclicBarrier barrier) {
            this.provider = provider;
            this.barrier = barrier;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public JudgeResult judge(JudgeConfig config, String prompt) {
            try {
                barrier.await(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new JudgeException("barrier not reached (serial?): " + e, e);
            }
            return JudgeResult.builder()
                    .judgeModel(provider)
                    .difficulty(3).outcome("completed").mode("leverage")
                    .capProblemDecomposition(3).capContextManagement(3).capDebuggingSkill(3)
                    .capToolOrchestration(3).capSelfCorrection(3).reason("ok")
                    .build();
        }
    }
}
