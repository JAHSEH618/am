package com.am.server.system;

import com.am.server.agent.service.GitCommitIngestService;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitCommitPathStatsBackfillTest {

    private final GitCommitRepository commitRepo = mock(GitCommitRepository.class);
    private final GitCommitIngestService ingestService = mock(GitCommitIngestService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void scansByIdRangeAndBackfillsParsableRows() throws Exception {
        GitCommit ok = commit(1L, "[{\"path\":\"a.go\",\"lines_added\":1,\"lines_deleted\":0}]");
        GitCommit bad = commit(2L, "{\"not\":\"a list\"}");
        when(commitRepo.findWithPathStatsButNoFilesInIdRange(1, 2_001)).thenReturn(List.of(ok, bad));
        when(commitRepo.findWithPathStatsButNoFilesInIdRange(2_001, 4_001)).thenReturn(List.of());

        GitCommitPathStatsBackfill.Result r = GitCommitPathStatsBackfill.backfill(
                commitRepo, ingestService, objectMapper, new long[]{1, 3_000}, 0);

        assertThat(r.ok()).isEqualTo(1);
        // path_stats 解析不了是确定性的，重跑也不会好：跳过但不算失败，不挡 marker
        assertThat(r.failed()).isZero();
        verify(ingestService).backfillFilesFromPathStats(eq(ok), anyList());
        verify(ingestService, never()).backfillFilesFromPathStats(eq(bad), any());
        verify(commitRepo).findWithPathStatsButNoFilesInIdRange(2_001, 4_001);
    }

    @Test
    void persistenceFailureIsCountedSoMarkerIsNotWritten() throws Exception {
        GitCommit row = commit(5L, "[{\"path\":\"a.go\"}]");
        when(commitRepo.findWithPathStatsButNoFilesInIdRange(5, 2_005)).thenReturn(List.of(row));
        doThrow(new RuntimeException("deadlock")).when(ingestService).backfillFilesFromPathStats(eq(row), anyList());

        GitCommitPathStatsBackfill.Result r = GitCommitPathStatsBackfill.backfill(
                commitRepo, ingestService, objectMapper, new long[]{5, 5}, 0);

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.ok()).isZero();
    }

    private static GitCommit commit(Long id, String pathStatsJson) {
        GitCommit c = new GitCommit();
        c.setId(id);
        c.setCommitHash("h" + id);
        c.setPathStatsJson(pathStatsJson);
        return c;
    }
}
