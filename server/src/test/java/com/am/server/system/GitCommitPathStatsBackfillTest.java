package com.am.server.system;

import com.am.server.agent.service.GitCommitIngestService;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.system.ResumableBackfill.FailedState;
import com.am.server.system.ResumableBackfill.PassStatus;
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

    private static final String MARKER = GitCommitPathStatsBackfill.MARKER_KEY;

    private final GitCommitRepository commitRepo = mock(GitCommitRepository.class);
    private final GitCommitIngestService ingestService = mock(GitCommitIngestService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryBackfillStore store = new InMemoryBackfillStore();
    private final ResumableBackfill state = new ResumableBackfill(store, MARKER, "desc", 0);

    @Test
    void scansByIdRangeAndBackfillsParsableRows() throws Exception {
        GitCommit ok = commit(1L, "[{\"path\":\"a.go\",\"lines_added\":1,\"lines_deleted\":0}]");
        GitCommit bad = commit(2L, "{\"not\":\"a list\"}");
        when(commitRepo.findWithPathStatsButNoFilesInIdRange(1, 2_001)).thenReturn(List.of(ok, bad));
        when(commitRepo.findWithPathStatsButNoFilesInIdRange(2_001, 4_001)).thenReturn(List.of());

        GitCommitPathStatsBackfill.Result r = GitCommitPathStatsBackfill.backfill(
                state, commitRepo, ingestService, objectMapper, new long[]{1, 3_000}, 0);

        assertThat(r.status()).isEqualTo(PassStatus.COMPLETED);
        assertThat(r.ok()).isEqualTo(1);
        // path_stats 解析不了是确定性的，重跑也不会好：跳过但不算失败，不进失败清单
        assertThat(r.failed()).isZero();
        verify(ingestService).backfillFilesFromPathStats(eq(ok), anyList());
        verify(ingestService, never()).backfillFilesFromPathStats(eq(bad), any());
        verify(commitRepo).findWithPathStatsButNoFilesInIdRange(2_001, 4_001);
        assertThat(store.markers).containsExactly(MARKER);
        assertThat(store.values).doesNotContainKey(MARKER + ".failed");
    }

    @Test
    void persistenceFailureIsRecordedSkippedAndTheMarkerIsStillWritten() throws Exception {
        GitCommit bad = commit(5L, "[{\"path\":\"a.go\"}]");
        GitCommit good = commit(6L, "[{\"path\":\"b.go\"}]");
        when(commitRepo.findWithPathStatsButNoFilesInIdRange(5, 2_005)).thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("deadlock")).when(ingestService).backfillFilesFromPathStats(eq(bad), anyList());

        GitCommitPathStatsBackfill.Result r = GitCommitPathStatsBackfill.backfill(
                state, commitRepo, ingestService, objectMapper, new long[]{5, 6}, 0);

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.ok()).isEqualTo(1);
        verify(ingestService).backfillFilesFromPathStats(eq(good), anyList()); // 失败不挡后面的 commit
        assertThat(store.markers).as("一个 commit 失败也要写完成标记——否则每次重启整表重扫").containsExactly(MARKER);
        assertThat(FailedState.parse(store.values.get(MARKER + ".failed")).items()).containsExactly("5");
    }

    @Test
    void resumesAfterTheSavedRangeCursor() throws Exception {
        store.values.put(MARKER + ".progress", "2001"); // [1,2001) 已扫完
        when(commitRepo.findWithPathStatsButNoFilesInIdRange(2_001, 4_001)).thenReturn(List.of());

        GitCommitPathStatsBackfill.backfill(state, commitRepo, ingestService, objectMapper, new long[]{1, 3_000}, 0);

        verify(commitRepo, never()).findWithPathStatsButNoFilesInIdRange(1, 2_001);
        verify(commitRepo).findWithPathStatsButNoFilesInIdRange(2_001, 4_001);
        assertThat(store.markers).containsExactly(MARKER);
    }

    private static GitCommit commit(Long id, String pathStatsJson) {
        GitCommit c = new GitCommit();
        c.setId(id);
        c.setCommitHash("h" + id);
        c.setPathStatsJson(pathStatsJson);
        return c;
    }
}
