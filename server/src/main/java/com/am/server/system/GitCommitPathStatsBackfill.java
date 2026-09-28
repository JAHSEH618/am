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
 * 线程按 git_commit 主键区间分批找候选、逐条补写（口径同前）；有补写失败则不写 marker，下次启动重跑（幂等）。
 * gz
 */
@Configuration
public class GitCommitPathStatsBackfill {

    private static final Logger log = LoggerFactory.getLogger(GitCommitPathStatsBackfill.class);

    static final String MARKER_KEY = "git.path_stats_file_backfill_v1";

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
            long[] bounds;
            try {
                if (OneShotBackfillSupport.markerExists(dataSource, MARKER_KEY)) {
                    return;
                }
                bounds = OneShotBackfillSupport.idBounds(dataSource, "git_commit");
                if (bounds == null) {
                    writeMarker(dataSource);
                    return;
                }
            } catch (SQLException e) {
                log.warn("git commit file backfill skipped: {}", e.getMessage());
                return;
            }
            Thread t = new Thread(() -> run(dataSource, gitCommitRepository, gitCommitIngestService, objectMapper, bounds),
                    "git-path-stats-backfill");
            t.setDaemon(true);
            t.start();
        };
    }

    private static void run(DataSource dataSource,
                            GitCommitRepository gitCommitRepository,
                            GitCommitIngestService gitCommitIngestService,
                            ObjectMapper objectMapper,
                            long[] bounds) {
        Result r;
        try {
            r = backfill(gitCommitRepository, gitCommitIngestService, objectMapper, bounds, PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            log.warn("git commit file backfill aborted (will retry next boot): {}", e.toString());
            return;
        }
        if (r.failed() > 0) {
            log.warn("git commit file backfill incomplete: commits={} failed={}; marker not written, will retry on next boot",
                    r.ok(), r.failed());
            return;
        }
        try {
            writeMarker(dataSource);
        } catch (SQLException e) {
            log.warn("git commit file backfill marker write failed: {}", e.getMessage());
            return;
        }
        log.info("git commit file backfill done: commits={}", r.ok());
    }

    /** {@code ok} = 补写成功的 commit 数；{@code failed} = 补写抛异常的条数（path_stats 解析不了的行不计，重跑也不会好）。 */
    record Result(int ok, int failed) {}

    static Result backfill(GitCommitRepository gitCommitRepository,
                           GitCommitIngestService gitCommitIngestService,
                           ObjectMapper objectMapper,
                           long[] bounds,
                           long pauseMs) throws InterruptedException {
        int ok = 0;
        int failed = 0;
        for (long[] range : OneShotBackfillSupport.ranges(bounds[0], bounds[1], COMMIT_ID_STEP)) {
            List<GitCommit> rows = gitCommitRepository.findWithPathStatsButNoFilesInIdRange(range[0], range[1]);
            for (GitCommit row : rows) {
                List<GitCommitReportRequest.PathStat> stats;
                try {
                    stats = objectMapper.readValue(
                            row.getPathStatsJson(),
                            new TypeReference<List<GitCommitReportRequest.PathStat>>() {});
                } catch (Exception e) {
                    log.warn("git commit file backfill: unparsable path_stats id={} hash={} err={}",
                            row.getId(), row.getCommitHash(), e.toString());
                    continue;
                }
                if (stats == null || stats.isEmpty()) {
                    continue;
                }
                try {
                    gitCommitIngestService.backfillFilesFromPathStats(row, stats);
                    ok++;
                } catch (Exception e) {
                    failed++;
                    log.warn("git commit file backfill failed id={} hash={} err={}",
                            row.getId(), row.getCommitHash(), e.toString());
                }
            }
            if (pauseMs > 0) {
                Thread.sleep(pauseMs);
            }
        }
        return new Result(ok, failed);
    }

    private static void writeMarker(DataSource dataSource) throws SQLException {
        OneShotBackfillSupport.writeMarker(dataSource, MARKER_KEY, "存量 git_commit 由 path_stats_json 补写 file 行完成标记");
    }
}
