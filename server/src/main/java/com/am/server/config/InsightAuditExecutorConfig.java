package com.am.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 洞察审计共享线程池，避免每次扫描 / 批量审计新建 FixedThreadPool。
 */
@Configuration
public class InsightAuditExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService insightAuditExecutor(
            @Value("${aiwatch.insight.audit-concurrency:2}") int auditConcurrency,
            @Value("${aiwatch.insight.audit-background-concurrency:2}") int backgroundConcurrency) {
        int n = Math.max(1, Math.max(auditConcurrency, backgroundConcurrency));
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "insight-audit-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(n, factory);
    }
}
