package com.am.server.config;

import com.am.server.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实应用上下文（全部 ExecutorService bean、Boot 自动配置都在）里，{@code @Async} 落在专用有界池上，
 * 而不是与 {@code @Scheduled} 共用的调度线程；调度池大小取自 application.yml。
 */
@SpringBootTest(classes = {Application.class, AsyncWiringIntegrationTest.ProbeConfig.class})
@ActiveProfiles("test")
class AsyncWiringIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {
        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    static class AsyncProbe {
        @Async
        public CompletableFuture<String> threadName() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }

    @Autowired
    AsyncProbe probe;

    @Autowired
    ThreadPoolTaskScheduler taskScheduler;

    @Test
    void asyncRunsOnTheAnalysisPool() throws Exception {
        assertThat(probe.threadName().get()).startsWith("analysis-job-");
    }

    @Test
    void scheduledPoolHasTheConfiguredSize() {
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
    }
}
