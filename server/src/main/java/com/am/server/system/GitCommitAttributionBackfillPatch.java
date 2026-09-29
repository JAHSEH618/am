package com.am.server.system;

import com.am.server.aggregator.GitCommitAttributionEngine;
import com.am.server.domain.git.GitCommitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * git_commit_attribution 历史全量回溯（《管理后台-产出归因与能力使用分析 v1.0》§2.7）：
 * 从最早 commit_time 起按天调 {@link GitCommitAttributionEngine#attributeWindow}，
 * 回溯行打 backfilled=1（前端趋势图对回溯区间注明「上线前数据为回溯推算」）。
 * B 档回溯（trailer 文本匹配）可信；A 档回溯依赖历史会话事件完整性。
 *
 * <p><b>可续跑 + 容错</b>（骨架见 {@link ResumableBackfill}）：进度游标落 sys_config
 * {@code attribution.backfill_v1.progress}，重启从游标续跑；单日失败记 WARN + 记入
 * {@code attribution.backfill_v1.failed}、跳过继续（delete+insert 幂等，重算安全）；整轮跑完写完成标记
 * （{@value #MARKER_KEY}）；之后每次启动只重试失败日，最多 {@value ResumableBackfill#MAX_RETRY_ATTEMPTS} 次。
 * 此前“一天失败就不写标记、每次重启整段重算”。
 *
 * <p>与 ingest 增量 / 夜间批并发写同一批 commit 的冲突由引擎内的写锁串行化
 * （{@link GitCommitAttributionEngine#attributeCommits}）。后台 daemon 线程执行，不阻塞启动。
 * gz
 */
@Configuration
public class GitCommitAttributionBackfillPatch {

    private static final Logger log = LoggerFactory.getLogger(GitCommitAttributionBackfillPatch.class);

    static final String MARKER_KEY = "attribution.backfill_v1";

    static final String MARKER_DESCRIPTION = "git_commit_attribution 历史全量回溯完成标记";

    /** 晚于 GitCommitAttributionSchemaPatches（@Order(130)）：先建表再回溯。 */
    @Bean
    @Order(910)
    ApplicationRunner backfillGitCommitAttribution(DataSource dataSource,
                                                   GitCommitAttributionEngine engine,
                                                   GitCommitRepository gitCommitRepository) {
        return args -> {
            ResumableBackfill state = new ResumableBackfill(
                    ResumableBackfill.Store.jdbc(dataSource), MARKER_KEY, MARKER_DESCRIPTION);
            try {
                if (!state.hasPendingWork()) {
                    return;
                }
            } catch (SQLException e) {
                log.warn("git attribution backfill skipped: {}", e.getMessage());
                return;
            }
            Thread t = new Thread(() -> run(state, engine, gitCommitRepository), "git-attribution-backfill");
            t.setDaemon(true);
            t.start();
        };
    }

    private static void run(ResumableBackfill state, GitCommitAttributionEngine engine,
                            GitCommitRepository gitCommitRepository) {
        try {
            if (state.isDone()) {
                ResumableBackfill.RetryResult r = state.retryFailed(label -> attributeDay(engine, label));
                if (r.retried() > 0) {
                    log.info("git attribution backfill retry: retried={} recovered={} remaining={}",
                            r.retried(), r.recovered(), r.remaining());
                }
                return;
            }
            var minCommitTime = gitCommitRepository.findMinCommitTime();
            if (minCommitTime == null) {
                state.loadFailed();
                state.complete();
                return;
            }
            LocalDate from = minCommitTime.toLocalDate();
            LocalDate to = LocalDate.now();
            ResumableBackfill.PassResult r = backfill(state, from, to, engine);
            log.info("git attribution backfill {}: from={} to={} ok={} failed={}",
                    r.status().name().toLowerCase(), from, to, r.ok(), r.failed());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("git attribution backfill aborted (will resume next boot): {}", e.toString());
        }
    }

    /** 一整轮日期回填（可测入口：状态与引擎都由调用方注入）。 */
    static ResumableBackfill.PassResult backfill(ResumableBackfill state, LocalDate from, LocalDate to,
                                                 GitCommitAttributionEngine engine) throws InterruptedException {
        return state.runDayPass(from, to, label -> attributeDay(engine, label));
    }

    static void attributeDay(GitCommitAttributionEngine engine, String isoDate) {
        LocalDate d = LocalDate.parse(isoDate);
        engine.attributeWindow(d.atStartOfDay(), d.plusDays(1).atStartOfDay(), true);
    }
}
