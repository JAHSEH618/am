package com.am.server.config;

import com.am.server.insight.config.InsightProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class InsightAuditExecutorConfigTest {

    @Test
    void reportAndBackgroundPoolsAreDistinctAndSizedFromProperties() {
        InsightProperties props = new InsightProperties();
        props.setAuditConcurrency(8);
        props.setAuditBackgroundConcurrency(2);

        InsightAuditExecutorConfig config = new InsightAuditExecutorConfig();
        ExecutorService report = config.reportAuditExecutor(props);
        ExecutorService background = config.backgroundAuditExecutor(props);
        try {
            assertThat(report).isNotSameAs(background);
            assertThat(((ThreadPoolExecutor) report).getMaximumPoolSize()).isEqualTo(8);
            assertThat(((ThreadPoolExecutor) background).getMaximumPoolSize()).isEqualTo(2);
        } finally {
            report.shutdownNow();
            background.shutdownNow();
        }
    }
}
