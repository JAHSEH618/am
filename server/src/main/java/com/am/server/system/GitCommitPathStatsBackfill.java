package com.am.server.system;

import com.am.server.agent.api.dto.GitCommitReportRequest;
import com.am.server.agent.service.GitCommitIngestService;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

/**
 * 存量：path_stats_json 有数据但 git_commit_file 为空的提交，补写 file 行（无 patch）。
 *
 * <p>现行 ingest 在新 commit 落库时同事务写 file 行，这里只服务历史数据，却曾每次启动都整表扫 git_commit
 * （逐行 JSON_LENGTH + NOT EXISTS）。现改为 sys_config marker（{@value #MARKER_KEY}）一次性：后台 daemon
 * 线程按 git_commit 主键区间分批找候选、逐条补写（口径同前）。
 *
 * <p><b>可续跑 + 容错</b>（骨架见 {@link ResumableBackfill}）：区间游标落 {@code git.path_stats_file_backfill_v1.progress}，
 * 重启从游标续跑；单个 commit 补写失败记 WARN（带 id / hash）+ 记入 {@code .failed}、跳过继续（补写幂等：
 * 已有 file 行即跳过）；整轮跑完写完成标记；之后每次启动只重试失败的 commit，最多
 * {@value ResumableBackfill#MAX_RETRY_ATTEMPTS} 次。此前“一个 commit 失败就不写标记、每次重启整表重扫”。
 * path_stats 解析不了的行是确定性的（重跑也不会好），直接跳过、不算失败。
 * gz
 */
@Configuration
public class GitCommitPathStatsBackfill {

    private static final Logger log = LoggerFactory.getLogger(GitCommitPathStatsBackfill.class);

    static final String MARKER_KEY = "git.path_stats_file_backfill_v1";

    static final String MARKER_DESCRIPTION = "存量 git_commit 由 path_stats_json 补写 file 行完成标记";

    /** git_commit 行较宽（message_body / path_stats_json），按 2000 一批。 */
    static final int COMMIT_ID_STEP = 2_000;

    /** 批间停顿：一次性全表扫，不和 ingest / 页面查询抢 IO。 */
    private static final long PAUSE_MS = 20L;

    /** id_sequences 种子（IdSequenceSeedSchemaPatches，HIGHEST_PRECEDENCE）先于本 runner 同步跑完，后台线程才起。 */
    @Bean
    ApplicationRunner backfillGitCommitFiles(DataSource dataSource,
                                           GitCommitRepository gitCommitRepository,
                                           GitCommitIngestService gitCommitIngestService,
                                           ObjectMapper objectMapper) {
        return args -> {
            ResumableBackfill state = new ResumableBackfill(
                    ResumableBackfill.Store.jdbc(dataSource), MARKER_KEY, MARKER_DESCRIPTION);
            try {
                if (!state.hasPendingWork()) {
                    return;
                }
            } catch (SQLException e) {
                log.warn("git commit file backfill skipped: {}", e.getMessage());
                return;
            }
            Thread t = new Thread(() -> run(dataSource, state, gitCommitRepository, gitCommitIngestService, objectMapper),
                    "git-path-stats-backfill");
            t.setDaemon(true);
            t.start();
        };
    }

    private static void run(DataSource dataSource,
                            ResumableBackfill state,
                            GitCommitRepository gitCommitRepository,
                            GitCommitIngestService gitCommitIngestService,
                            ObjectMapper objectMapper) {
        try {
            if (state.isDone()) {
                ResumableBackfill.RetryResult r = state.retryFailed(label -> {
                    long id = Long.parseLong(label);
                    // 只重试“仍缺 file 行”的那条；已被补上（或不再满足条件）视为成功
                    for (GitCommit row : gitCommitRepository.findWithPathStatsButNoFilesInIdRange(id, id + 1)) {
                        backfillOne(gitCommitIngestService, objectMapper, row);
                    }
                });
                if (r.retried() > 0) {
                    log.info("git commit file backfill retry: retried={} recovered={} remaining={}",
                            r.retried(), r.recovered(), r.remaining());
                }
                return;
            }
            long[] bounds = OneShotBackfillSupport.idBounds(dataSource, "git_commit");
            if (bounds == null) {
                state.loadFailed();
                state.complete();
                return;
            }
            Result r = backfill(state, gitCommitRepository, gitCommitIngestService, objectMapper, bounds, PAUSE_MS);
            log.info("git commit file backfill {}: commits={} failed={}", r.status().name().toLowerCase(), r.ok(), r.failed());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("git commit file backfill aborted (will resume next boot): {}", e.toString());
        }
    }

    /** {@code ok} = 补写成功的 commit 数；{@code failed} = 补写抛异常并记入失败清单的条数（path_stats 解析不了的行不计）。 */
    record Result(ResumableBackfill.PassStatus status, int ok, int failed) {}

    /** 一整轮区间回填（可测入口：状态与仓库都由调用方注入）。 */
    static Result backfill(ResumableBackfill state,
                           GitCommitRepository gitCommitRepository,
                           GitCommitIngestService gitCommitIngestService,
                           ObjectMapper objectMapper,
                           long[] bounds,
                           long pauseMs) throws InterruptedException {
        state.loadFailed();
        long scannedUpTo = ResumableBackfill.resumeId(state.readProgress(), bounds[0]);
        int ok = 0;
        int failed = 0;
        for (long[] range : OneShotBackfillSupport.ranges(bounds[0], bounds[1], COMMIT_ID_STEP)) {
            if (range[1] <= scannedUpTo) {
                continue; // 上一轮已扫完
            }
            List<GitCommit> rows = gitCommitRepository.findWithPathStatsButNoFilesInIdRange(range[0], range[1]);
            for (GitCommit row : rows) {
                boolean[] wrote = {false};
                ResumableBackfill.UnitResult r = state.runUnit(String.valueOf(row.getId()),
                        () -> wrote[0] = backfillOne(gitCommitIngestService, objectMapper, row));
                if (r == ResumableBackfill.UnitResult.OVERFLOW) {
                    return new Result(ResumableBackfill.PassStatus.ABORTED, ok, failed);
                }
                if (r == ResumableBackfill.UnitResult.FAILED) {
                    failed++;
                } else if (wrote[0]) {
                    ok++;
                }
            }
            state.checkpoint(String.valueOf(range[1]));
            if (pauseMs > 0) {
                Thread.sleep(pauseMs);
            }
        }
        state.complete();
        return new Result(ResumableBackfill.PassStatus.COMPLETED, ok, failed);
    }

    /**
     * 补写一条。path_stats 解析不了 / 为空 → 跳过返回 false（确定性，重跑不会好，不算失败）；
     * 落库异常向上抛（由骨架计入失败）。
     */
    private static boolean backfillOne(GitCommitIngestService gitCommitIngestService,
                                       ObjectMapper objectMapper,
                                       GitCommit row) throws Exception {
        List<GitCommitReportRequest.PathStat> stats;
        try {
            stats = objectMapper.readValue(
                    row.getPathStatsJson(),
                    new TypeReference<List<GitCommitReportRequest.PathStat>>() {});
        } catch (Exception e) {
            log.warn("git commit file backfill: unparsable path_stats id={} hash={} err={}",
                    row.getId(), row.getCommitHash(), e.toString());
            return false;
        }
        if (stats == null || stats.isEmpty()) {
            return false;
        }
        try {
            gitCommitIngestService.backfillFilesFromPathStats(row, stats);
        } catch (Exception e) {
            log.warn("git commit file backfill failed id={} hash={} err={}",
                    row.getId(), row.getCommitHash(), e.toString());
            throw e;
        }
        return true;
    }
}
