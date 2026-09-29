package com.am.server.config;

import com.am.server.aggregator.CapabilityDailyAggregator;
import com.am.server.aggregator.DailySummaryAggregator;
import com.am.server.aggregator.GitCommitAttributionEngine;
import com.am.server.insight.config.InsightProperties;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BackgroundExecutorConfigTest {

    // ------------------------------------------------------------------ @Async 去向

    /** 复现"没有 applicationTaskExecutor"的现状：容器里已有 ExecutorService bean（洞察审计池）。 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAsync
    @EnableScheduling
    static class AsyncProbeConfig {
        @Bean
        ExecutorService reportAuditExecutor() {
            return Executors.newFixedThreadPool(1);
        }

        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    static class Probe {
        @Async
        public CompletableFuture<String> threadName() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class));
    }

    @Test
    void withoutTheDedicatedPool_asyncSilentlyRunsOnTheSingleSchedulerThread() {
        // 记录问题现状：@Async 落在 Boot 的 taskScheduler（scheduling-N）上，与所有 @Scheduled 共用
        runner().withUserConfiguration(AsyncProbeConfig.class).run(ctx -> {
            assertThat(ctx.getBean(Probe.class).threadName().get()).startsWith("scheduling-");
        });
    }

    @Test
    void withTheDedicatedPool_asyncRunsOnTheBoundedAnalysisPool() {
        runner().withUserConfiguration(AsyncProbeConfig.class, BackgroundExecutorConfig.class).run(ctx -> {
            assertThat(ctx.getBean(Probe.class).threadName().get()).startsWith("analysis-job-");
        });
    }

    // ------------------------------------------------------------------ analysis-job 池

    @Test
    void analysisJobPoolIsBoundedAndRejectsInsteadOfGrowing() throws Exception {
        ThreadPoolTaskExecutor pool = new BackgroundExecutorConfig().analysisJobExecutor();
        pool.initialize();
        CountDownLatch release = new CountDownLatch(1);
        try {
            ThreadPoolExecutor tpe = pool.getThreadPoolExecutor();
            assertThat(tpe.getCorePoolSize()).isEqualTo(BackgroundExecutorConfig.ANALYSIS_JOB_THREADS);
            assertThat(tpe.getMaximumPoolSize()).isEqualTo(BackgroundExecutorConfig.ANALYSIS_JOB_THREADS);
            assertThat(tpe.getQueue().remainingCapacity()).isEqualTo(BackgroundExecutorConfig.ANALYSIS_JOB_QUEUE);

            int accepted = BackgroundExecutorConfig.ANALYSIS_JOB_THREADS + BackgroundExecutorConfig.ANALYSIS_JOB_QUEUE;
            for (int i = 0; i < accepted; i++) {
                pool.execute(() -> awaitQuietly(release));
            }
            assertThatThrownBy(() -> pool.execute(() -> { }))
                    .as("队列满 → 显式拒绝（调用方可见），而不是无界开线程 / 静默丢")
                    .isInstanceOf(TaskRejectedException.class);
            assertThat(tpe.getPoolSize()).isLessThanOrEqualTo(BackgroundExecutorConfig.ANALYSIS_JOB_THREADS);
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }

    @Test
    void analysisJobPoolNamesThreadsAndWaitsForRunningTasksOnShutdown() throws Exception {
        ThreadPoolTaskExecutor pool = new BackgroundExecutorConfig().analysisJobExecutor();
        pool.initialize();
        try {
            String name = pool.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
            assertThat(name).startsWith("analysis-job-");
            assertThat(pool.getThreadPoolExecutor().getThreadFactory().newThread(() -> { }).isDaemon()).isTrue();
            org.springframework.beans.DirectFieldAccessor f = new org.springframework.beans.DirectFieldAccessor(pool);
            assertThat(f.getPropertyValue("waitForTasksToCompleteOnShutdown")).isEqualTo(true);
            assertThat(f.getPropertyValue("awaitTerminationMillis")).isEqualTo(4_000L);
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------ judge 池

    @Test
    void judgePoolIsBoundedBySizeOfItsCallers() {
        InsightProperties props = new InsightProperties();
        props.setAuditConcurrency(8);
        props.setAuditBackgroundConcurrency(2);
        ExecutorService judge = new InsightAuditExecutorConfig().judgeCallExecutor(props);
        try {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) judge;
            assertThat(tpe.getCorePoolSize()).as("2 × (报告 worker + 后台 worker)").isEqualTo(20);
            assertThat(tpe.getMaximumPoolSize()).isEqualTo(20);
            assertThat(tpe.getQueue().remainingCapacity()).isEqualTo(20);
        } finally {
            judge.shutdownNow();
        }
    }

    @Test
    void judgePoolBlocksTheSubmitterWhenFullInsteadOfRejecting() throws Exception {
        InsightProperties props = new InsightProperties();
        props.setAuditConcurrency(1);
        props.setAuditBackgroundConcurrency(1);   // 2 × (1+1) = 4 线程 + 4 队列
        ExecutorService judge = new InsightAuditExecutorConfig().judgeCallExecutor(props);
        ExecutorService submitter = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 8; i++) {
                judge.execute(() -> awaitQuietly(release));
            }
            AtomicBoolean ran = new AtomicBoolean();
            Future<?> ninth = submitter.submit(() -> judge.execute(() -> ran.set(true)));
            Thread.sleep(300);
            assertThat(ninth.isDone()).as("池 + 队列都满：第 9 个提交被阻塞（背压），没有被拒绝、也没有丢").isFalse();

            release.countDown();
            ninth.get(5, TimeUnit.SECONDS);
            judge.shutdown();
            assertThat(judge.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ran).isTrue();
        } finally {
            release.countDown();
            submitter.shutdownNow();
            judge.shutdownNow();
        }
    }

    @Test
    void blockedSubmitterFailsFastOnceThePoolIsShutDown() throws Exception {
        InsightAuditExecutorConfig.GracefulPool pool = new InsightAuditExecutorConfig.GracefulPool(
                "t-", 1, new java.util.concurrent.LinkedBlockingQueue<>(1),
                new InsightAuditExecutorConfig.BlockingSubmitPolicy(), 50);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService submitter = Executors.newSingleThreadExecutor();
        try {
            pool.execute(() -> awaitQuietly(release));
            pool.execute(() -> { });   // 占满队列
            Future<?> blocked = submitter.submit(() -> pool.execute(() -> { }));
            Thread.sleep(200);
            assertThat(blocked.isDone()).isFalse();

            pool.shutdownNow();   // 清空队列 → 卡在 put 的提交方得以入队，随即发现池已关闭并撤回、抛出
            assertThatThrownBy(() -> blocked.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            submitter.shutdownNow();
        }
        assertThatThrownBy(() -> pool.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void gracefulPoolWaitsBrieflyThenInterruptsStragglersOnDestroy() throws Exception {
        InsightAuditExecutorConfig.GracefulPool pool = new InsightAuditExecutorConfig.GracefulPool(
                "t-", 1, new java.util.concurrent.LinkedBlockingQueue<>(),
                new ThreadPoolExecutor.AbortPolicy(), 100);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        pool.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long t0 = System.nanoTime();
        pool.destroy();

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).as("限时等待，不会等满 30s").isLessThan(5_000);
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void auditPoolsKeepTheirConfiguredSizesAndNames() throws Exception {
        InsightProperties props = new InsightProperties();
        props.setAuditConcurrency(3);
        props.setAuditBackgroundConcurrency(2);
        InsightAuditExecutorConfig config = new InsightAuditExecutorConfig();
        ExecutorService report = config.reportAuditExecutor(props);
        ExecutorService background = config.backgroundAuditExecutor(props);
        try {
            assertThat(((ThreadPoolExecutor) report).getMaximumPoolSize()).isEqualTo(3);
            assertThat(((ThreadPoolExecutor) background).getMaximumPoolSize()).isEqualTo(2);
            assertThat(report.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS))
                    .startsWith("insight-report-audit-");
            assertThat(background.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS))
                    .startsWith("insight-bg-audit-");
        } finally {
            report.shutdownNow();
            background.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 配置与连接总账

    @Test
    void schedulingPoolIsNoLongerTheBootDefaultOfOne() {
        Properties app = yaml("application.yml");
        assertThat(app.getProperty("spring.task.scheduling.pool.size")).isEqualTo("3");
        assertThat(app.getProperty("spring.task.scheduling.shutdown.await-termination")).isEqualTo("true");
    }

    /**
     * 把 {@link BackgroundExecutorConfig} 类注释里的算术固化成断言：重上报 + 稳态后台 + 一次报告运行 ≤ Hikari − 4。
     * 数字来自真实配置 / 真实类，而不是文档里的字面量——谁调大任何一项都会在这里撞上。
     */
    @Test
    void connectionBudgetStaysWithinHikariPool() throws Exception {
        int hikari = Integer.parseInt(yaml("application-prod.yml").getProperty("spring.datasource.hikari.maximum-pool-size"));
        Properties app = yaml("application.yml");
        int ingest = Integer.parseInt(app.getProperty("aiwatch.agent.ingest-max-concurrency"));
        int scheduling = Integer.parseInt(app.getProperty("spring.task.scheduling.pool.size"));

        DynamicScheduledTaskManager dyn = new DynamicScheduledTaskManager(mock(SystemConfigService.class));
        dyn.init();
        int dynSched;
        try {
            dynSched = ((ThreadPoolTaskScheduler) dyn.getScheduler()).getScheduledThreadPoolExecutor().getCorePoolSize();
        } finally {
            dyn.destroy();
        }

        List<Class<?>> refreshOwners = List.of(
                DailySummaryAggregator.class, CapabilityDailyAggregator.class, GitCommitAttributionEngine.class);
        for (Class<?> c : refreshOwners) {
            assertThat(c.getDeclaredField("refreshExecutor").getType().getSimpleName())
                    .as(c.getSimpleName() + " 的单线程 refreshExecutor").contains("ExecutorService");
        }
        int refresh = refreshOwners.size();

        InsightProperties insight = new InsightProperties();
        int steady = scheduling + dynSched + refresh;
        int whileReportRuns = steady + BackgroundExecutorConfig.ANALYSIS_JOB_THREADS + insight.getAuditConcurrency();

        int consoleReserve = 4;
        assertThat(ingest + whileReportRuns)
                .as("重上报 %d + 后台(稳态 %d = sched %d + dyn %d + refresh %d；报告期 %d) 须 ≤ Hikari %d − 预留 %d，"
                                + "调大任何一项前先重算 BackgroundExecutorConfig 类注释里的连接总账",
                        ingest, steady, scheduling, dynSched, refresh, whileReportRuns, hikari, consoleReserve)
                .isLessThanOrEqualTo(hikari - consoleReserve);
    }

    private static Properties yaml(String name) {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new ClassPathResource(name));
        Properties p = y.getObject();
        assertThat(p).isNotNull();
        return p;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
