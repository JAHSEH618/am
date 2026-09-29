package com.am.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * {@code @Async} 的执行器，以及整个服务端<b>后台并发资源</b>（线程 / DB 连接）的总账。
 *
 * <h2>为什么要有它：{@code @Async} 曾经跑在 {@code scheduling-1} 上</h2>
 * Spring 的 {@code @Async} 默认找唯一的 {@code TaskExecutor} bean。Boot 的 {@code applicationTaskExecutor}
 * 因为容器里已有 {@code ExecutorService} bean（洞察审计池）而退让（{@code @ConditionalOnMissingBean(Executor)}），
 * 剩下唯一的 {@code TaskExecutor} 是 Boot 的 {@code taskScheduler}（{@code ThreadPoolTaskScheduler} 也是 TaskExecutor）
 * ——所以 {@code AnalysisJobRunner#runAsync}（分析报告流水线，跑数分钟到数十分钟，全程等 LLM）一直跑在
 * <b>与所有 {@code @Scheduled} 共用的单线程池</b>里：报告一跑，SSE 心跳（25s）、员工展示名刷新、活跃类型刷新全部停摆，
 * 第二份报告还要排在第一份后面。这里用 {@link AsyncConfigurer} 显式指定专用有界池（不依赖 bean 类型的唯一性，
 * 以后谁再加一个 TaskExecutor bean 也不会悄悄改变 {@code @Async} 去向）。
 *
 * <h2>线程 / 连接总账（prod：Hikari {@code maximum-pool-size = 40}）</h2>
 * <table>
 *   <caption>后台并发资源清单（线程数即"同时持有一条连接"的上界，一线程一次持一条）</caption>
 *   <tr><th>线程池</th><th>线程数</th><th>说明 / 拒绝策略</th></tr>
 *   <tr><td>Tomcat 重上报（{@code AgentIngestBulkheadFilter}）</td><td>16</td>
 *       <td>{@code aiwatch.agent.ingest-max-concurrency}；超额 503。每会话一个事务，取 id 区间时另借第二条
 *           （{@link com.am.server.domain.IdAllocation}：1000 一个区间后≈不发生）</td></tr>
 *   <tr><td>{@code scheduling-*}（Boot {@code @Scheduled}：SSE 心跳 / 展示名 / 活跃类型 / 审计扫描）</td><td>3</td>
 *       <td>{@code spring.task.scheduling.pool.size}；原来 1（且被 @Async 占用）。审计扫描 tick 会阻塞等一批 LLM，
 *           其余三个都是毫秒级，3 足够</td></tr>
 *   <tr><td>{@code dyn-sched-*}（{@code DynamicScheduledTaskManager}，10 个 cron 任务）</td><td>4</td>
 *       <td>固定；任务都是小时 / 每日 / 每分钟级，同时命中的极少</td></tr>
 *   <tr><td>{@code daily-summary-refresh} / {@code capability-daily-refresh} / {@code git-attribution-refresh}</td><td>3</td>
 *       <td>各 1 个单线程；队列长度被 per-date single-flight 集合限死（同一天只排一个），页面调用只涉及今天与最近几天，
 *           无需拒绝策略</td></tr>
 *   <tr><td>{@code analysis-job-*}（本类，{@code @Async}）</td><td>{@value #ANALYSIS_JOB_THREADS}</td>
 *       <td>队列 {@value #ANALYSIS_JOB_QUEUE}；满了抛 {@code TaskRejectedException}：报告行已提交为
 *           {@code pending}（started_time 为空），管理员再点「生成」会走 {@code recoverStuckPendingIfNeeded} 补调度，
 *           不静默丢失</td></tr>
 *   <tr><td>{@code insight-report-audit-*}</td><td>8</td>
 *       <td>{@code aiwatch.insight.audit-concurrency}；只在 LLM 调用之间短暂读写库，不带长事务</td></tr>
 *   <tr><td>{@code insight-bg-audit-*}</td><td>2</td>
 *       <td>后台审计扫描（prod 默认关：{@code audit-scan-enabled=false}）</td></tr>
 *   <tr><td>{@code insight-judge-*}</td><td>—</td><td>只跑 LLM HTTP，不占连接；上界 2×(8+2)，满了阻塞提交方</td></tr>
 *   <tr><td>启动期一次性回填 {@code new Thread} × 5</td><td>≤5</td>
 *       <td>sys_config marker 保证只跑一遍、主键区间分批；只在首次部署后的一段时间存在，稳态为 0</td></tr>
 * </table>
 *
 * <p><b>算术</b>：
 * <ul>
 *   <li>稳态后台 = 3（scheduling）+ 4（dyn-sched）+ 3（refresh）= <b>10</b> 条；</li>
 *   <li>管理员跑分析报告时再加 2（analysis-job）+ 8（report-audit）= <b>20</b> 条
 *       （后台审计扫描 +2，prod 关闭，不计）；</li>
 *   <li>重上报最多 16 条 ⇒ 16 + 20 = <b>36 ≤ 40</b>，给控制台 / 心跳 / 验签查询至少留 <b>4</b> 条。
 *       稳态（无报告）：16 + 10 = 26，留 14 条。</li>
 *   <li>以上是"每个线程此刻都恰好在 DB 里"的<b>硬上界</b>，实际远低于它（审计线程绝大多数时间在等 LLM）。
 *       报告期间控制台若仍觉得紧，先把 {@code aiwatch.insight.audit-concurrency} 从 8 降到 4（省 4 条）。</li>
 *   <li>调大任何一项（Hikari、{@code ingest-max-concurrency}、{@code spring.task.scheduling.pool.size}、审计并发）前先重算
 *       这道加法——{@code BackgroundExecutorConfigTest#connectionBudgetStaysWithinHikariPool} 用真实配置值把它固化成了断言。</li>
 * </ul>
 *
 * <h2>停机</h2>
 * 全部池是 daemon 线程，{@code shutdown} 时限时等待在途任务、超时不再等（Docker 默认 stop 超时 10s：
 * analysis-job 4s + 三个洞察池各 1s + 调度器 2s = 9s）。被截断的分析报告留在 {@code running}，
 * 下次启动由 {@code AnalysisReportStartupCleaner} 收尾。
 */
@Configuration
public class BackgroundExecutorConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(BackgroundExecutorConfig.class);

    public static final String ANALYSIS_JOB_EXECUTOR = "analysisJobExecutor";

    /** 同时跑的分析报告流水线数：每条流水线自己再通过 reportAuditExecutor（8）扇出，所以 2 条已足够，更多只是抢同一批 worker。 */
    public static final int ANALYSIS_JOB_THREADS = 2;

    /** 等待中的报告任务上限；报告是管理员手动触发的，队列几乎总是空的，超出说明在刷接口。 */
    public static final int ANALYSIS_JOB_QUEUE = 16;

    /** 停机等待秒数，见类注释里的 10s 预算。 */
    static final int ANALYSIS_JOB_AWAIT_SECONDS = 4;

    @Bean(name = ANALYSIS_JOB_EXECUTOR)
    public ThreadPoolTaskExecutor analysisJobExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("analysis-job-");
        ex.setCorePoolSize(ANALYSIS_JOB_THREADS);
        ex.setMaxPoolSize(ANALYSIS_JOB_THREADS);
        ex.setQueueCapacity(ANALYSIS_JOB_QUEUE);
        ex.setAllowCoreThreadTimeOut(true);
        ex.setKeepAliveSeconds(60);
        ex.setDaemon(true);
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(ANALYSIS_JOB_AWAIT_SECONDS);
        // 默认 AbortPolicy：队列满 → TaskRejectedException 传给调用方（不静默丢，见类注释）
        return ex;
    }

    /** {@code @Async} 一律走专用有界池，而不是碰巧唯一的 TaskExecutor（那曾是 scheduling-1）。 */
    @Override
    public Executor getAsyncExecutor() {
        return analysisJobExecutor();
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("uncaught exception in @Async {}#{}", method.getDeclaringClass().getSimpleName(),
                        method.getName(), ex);
    }
}
