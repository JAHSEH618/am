package com.am.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 管理平台 Spring Boot 启动类
 *
 * <p>{@code @EnableAsync} 为「分析报告 v3.0」的 AnalysisReportOrchestrator 服务：
 * 报告生成走异步线程池（pending → running → completed），不阻塞 HTTP 触发线程。
 * gz
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
@EnableAsync
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
