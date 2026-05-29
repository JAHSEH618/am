package com.am.server.system;

import com.am.server.agent.api.dto.GitCommitReportRequest;
import com.am.server.agent.service.GitCommitIngestService;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitFileRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/**
 * 存量：path_stats_json 有数据但 git_commit_file 为空的提交，补写 file 行（无 patch）。
 * gz
 */
@Configuration
public class GitCommitPathStatsBackfill {

    private static final Logger log = LoggerFactory.getLogger(GitCommitPathStatsBackfill.class);

    @Bean
    ApplicationRunner backfillGitCommitFiles(GitCommitRepository gitCommitRepository,
                                           GitCommitFileRepository gitCommitFileRepository,
                                           GitCommitIngestService gitCommitIngestService,
                                           ObjectMapper objectMapper) {
        return args -> backfill(gitCommitRepository, gitCommitFileRepository, gitCommitIngestService, objectMapper);
    }

    void backfill(GitCommitRepository gitCommitRepository,
                  GitCommitFileRepository gitCommitFileRepository,
                  GitCommitIngestService gitCommitIngestService,
                  ObjectMapper objectMapper) {
        List<GitCommit> rows = gitCommitRepository.findWithPathStatsButNoFiles();
        if (rows.isEmpty()) {
            return;
        }
        int ok = 0;
        for (GitCommit row : rows) {
            try {
                List<GitCommitReportRequest.PathStat> stats = objectMapper.readValue(
                        row.getPathStatsJson(),
                        new TypeReference<List<GitCommitReportRequest.PathStat>>() {});
                if (stats == null || stats.isEmpty()) {
                    continue;
                }
                gitCommitIngestService.backfillFilesFromPathStats(row, stats);
                ok++;
            } catch (Exception e) {
                log.warn("git commit file backfill failed id={} hash={} err={}",
                        row.getId(), row.getCommitHash(), e.toString());
            }
        }
        if (ok > 0) {
            log.info("git commit file backfill: commits={}", ok);
        }
    }
}
