package com.am.server.config;

import com.am.server.insight.config.InsightProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 洞察审计线程池。报告路径与后台扫描各用独立定长池,互不抢占(尺寸统一从 InsightProperties 读)。
 */
@Configuration
public class InsightAuditExecutorConfig {

    @Bean(name = "reportAuditExecutor", destroyMethod = "shutdown")
    public ExecutorService reportAuditExecutor(InsightProperties props) {
        return fixedDaemonPool(Math.max(1, props.getAuditConcurrency()), "insight-report-audit-");
    }

    @Bean(name = "backgroundAuditExecutor", destroyMethod = "shutdown")
    public ExecutorService backgroundAuditExecutor(InsightProperties props) {
        return fixedDaemonPool(Math.max(1, props.getAuditBackgroundConcurrency()), "insight-bg-audit-");
    }

    @Bean(name = "judgeCallExecutor", destroyMethod = "shutdown")
    public ExecutorService judgeCallExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "insight-judge-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        // cached:并发上限自然被 (report+background 会话并发)×2 约束;空闲线程 60s 回收。
        return Executors.newCachedThreadPool(factory);
    }

    private static ExecutorService fixedDaemonPool(int n, String namePrefix) {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, namePrefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(n, factory);
    }
}
