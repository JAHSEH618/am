package com.am.server.config;

import com.am.server.insight.config.InsightProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 洞察审计线程池。报告路径与后台扫描各用独立定长池,互不抢占(尺寸统一从 InsightProperties 读)。
 *
 * <p><b>三个池都是有界的</b>（线程数）；整体线程 / 连接预算见 {@link BackgroundExecutorConfig}。
 * <ul>
 *   <li>{@code reportAuditExecutor}（{@code audit-concurrency}，默认 8）/ {@code backgroundAuditExecutor}
 *       （{@code audit-background-concurrency}，默认 2）：定长、队列无界但流量受控——提交方
 *       {@code SessionAuditService#auditAll} 按 LLM 预算（{@code max-llm-calls-per-report}）截断待审会话数，
 *       后台扫描每批 ≤ {@code audit-scan-batch-size} 个且 {@code f.get()} 等完再取下一批。这些线程只在 LLM 调用之间
 *       短暂读写库，不带长事务。</li>
 *   <li>{@code judgeCallExecutor}：只跑 LLM HTTP 调用（不占 DB 连接）。调用方是上面两个池的 worker，
 *       每个 worker 提交 A / B 两个任务后 {@code join}——所以同时在途的任务数天然 ≤
 *       2 × (report + background) 个 worker，这里把它显式做成池上界（原来是无界 cached pool）：
 *       核心 = 最大 = 该值，队列同长，<b>满了阻塞提交方而不是拒绝</b>（LLM 判定不能丢，提交方本身是有界池的 worker，
 *       阻塞不会死锁——judge 任务是叶子，不再等别的任务）。空闲线程 60s 回收。</li>
 * </ul>
 * 停机：三个池都是 daemon 线程 + {@link GracefulPool#destroy()}（shutdown → 最多等 1s → shutdownNow）。
 * 等待时间刻意很短：Docker 默认 stop 超时 10s，全部池 + 调度器的等待之和必须 &lt; 10s。
 */
@Configuration
public class InsightAuditExecutorConfig {

    /** 停机时等在途任务收尾的最长毫秒数（每个池）。 */
    static final long SHUTDOWN_AWAIT_MS = 1_000L;

    @Bean(name = "reportAuditExecutor", destroyMethod = "")
    public ExecutorService reportAuditExecutor(InsightProperties props) {
        return new GracefulPool("insight-report-audit-", Math.max(1, props.getAuditConcurrency()),
                new LinkedBlockingQueue<>(), new ThreadPoolExecutor.AbortPolicy(), SHUTDOWN_AWAIT_MS);
    }

    @Bean(name = "backgroundAuditExecutor", destroyMethod = "")
    public ExecutorService backgroundAuditExecutor(InsightProperties props) {
        return new GracefulPool("insight-bg-audit-", Math.max(1, props.getAuditBackgroundConcurrency()),
                new LinkedBlockingQueue<>(), new ThreadPoolExecutor.AbortPolicy(), SHUTDOWN_AWAIT_MS);
    }

    /**
     * 判官调用池：线程数 = 队列容量 = 2 × (报告 worker + 后台 worker)——每个 worker 同时最多在途 A、B 两个调用。
     * 池 / 队列都满时 {@link BlockingSubmitPolicy 阻塞提交方}。
     */
    @Bean(name = "judgeCallExecutor", destroyMethod = "")
    public ExecutorService judgeCallExecutor(InsightProperties props) {
        int workers = Math.max(1, props.getAuditConcurrency()) + Math.max(1, props.getAuditBackgroundConcurrency());
        int size = 2 * workers;
        return new GracefulPool("insight-judge-", size, new LinkedBlockingQueue<>(size),
                new BlockingSubmitPolicy(), SHUTDOWN_AWAIT_MS);
    }

    /**
     * 定长 daemon 线程池（核心 = 最大，空闲线程可回收），Spring 销毁时先 {@code shutdown}、限时等待、再 {@code shutdownNow}。
     * 不用 {@code destroyMethod="shutdown"}：那不等任何在途任务；JDK 19+ 上 Spring 推断的 {@code close()}
     * 则会无限期等——两头都不对，所以显式实现 {@link DisposableBean} 并把 {@code destroyMethod} 置空。
     */
    static final class GracefulPool extends ThreadPoolExecutor implements DisposableBean {

        private final long awaitMillis;

        GracefulPool(String namePrefix, int threads, BlockingQueue<Runnable> queue,
                     RejectedExecutionHandler handler, long awaitMillis) {
            super(threads, threads, 60L, TimeUnit.SECONDS, queue, daemonFactory(namePrefix), handler);
            allowCoreThreadTimeOut(true);
            this.awaitMillis = awaitMillis;
        }

        @Override
        public void destroy() {
            shutdown();
            try {
                if (!awaitTermination(awaitMillis, TimeUnit.MILLISECONDS)) {
                    shutdownNow();
                }
            } catch (InterruptedException e) {
                shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        private static ThreadFactory daemonFactory(String namePrefix) {
            AtomicInteger seq = new AtomicInteger();
            return r -> {
                Thread t = new Thread(r, namePrefix + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            };
        }
    }

    /**
     * 池 + 队列都满时阻塞提交方直到腾出队列位置，而不是拒绝：LLM 判定调用必须排队、不能丢。
     * 提交方是有界 worker 池里的线程、且被提交的任务是不再等待其它任务的叶子，所以这种背压不会死锁。
     * 池已关闭时抛 {@link RejectedExecutionException}（提交方据此失败）。
     */
    static final class BlockingSubmitPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("executor has been shut down");
            }
            try {
                executor.getQueue().put(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("interrupted while waiting for an executor slot", e);
            }
            if (executor.isShutdown() && executor.getQueue().remove(r)) {
                // 入队后池恰好被关闭：任务永远不会被执行，别让提交方永远等下去
                throw new RejectedExecutionException("executor has been shut down");
            }
        }
    }
}
