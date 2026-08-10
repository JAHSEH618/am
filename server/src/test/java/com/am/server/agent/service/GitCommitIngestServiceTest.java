package com.am.server.agent.service;

import com.am.server.agent.api.dto.GitCommitReportRequest;
import com.am.server.agent.security.SignatureContext;
import com.am.server.aggregator.GitCommitAttributionEngine;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.domain.git.GitCommitFileRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitCommitIngestServiceTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .propertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void resolveFileDetails_prefersFilesOverPathStats() {
        GitCommitReportRequest.Item item = new GitCommitReportRequest.Item();
        GitCommitReportRequest.FileDetail file = new GitCommitReportRequest.FileDetail();
        file.setPath("from-files.go");
        item.setFiles(List.of(file));

        GitCommitReportRequest.PathStat ps = new GitCommitReportRequest.PathStat();
        ps.setPath("from-stats.go");
        item.setPathStats(List.of(ps));

        List<GitCommitReportRequest.FileDetail> got = GitCommitIngestService.resolveFileDetails(item);
        assertEquals(1, got.size());
        assertEquals("from-files.go", got.get(0).getPath());
    }

    @Test
    void resolveFileDetails_fallsBackToPathStats() {
        GitCommitReportRequest.Item item = new GitCommitReportRequest.Item();
        GitCommitReportRequest.PathStat ps = new GitCommitReportRequest.PathStat();
        ps.setPath("only-stats.go");
        ps.setLinesAdded(3);
        ps.setLinesDeleted(1);
        item.setPathStats(List.of(ps));

        List<GitCommitReportRequest.FileDetail> got = GitCommitIngestService.resolveFileDetails(item);
        assertEquals(1, got.size());
        assertEquals("only-stats.go", got.get(0).getPath());
        assertEquals(3, got.get(0).getLinesAdded());
        assertFalse(got.get(0).isHasPatch());
    }

    @Test
    void isRicherThanStored_falseWhenSameCommitReportedAgain() {
        // 已存 3 行 / 3 个 patch，重报同样的 3 行 —— 不得触发 replaceFiles，
        // 否则 patch_gzip 全删全插，binlog 记前后镜像 = 2 倍 blob 字节而数据没变。
        List<GitCommitReportRequest.FileDetail> incoming = List.of(
                filePatch("a.go"), filePatch("b.go"), filePatch("c.go"));
        assertFalse(GitCommitIngestService.isRicherThanStored(incoming, 3, 3));
    }

    @Test
    void isRicherThanStored_trueWhenPatchesArriveForBackfilledRows() {
        // 存量是 path_stats 合成的无 patch 行；这次带来了真 patch，值得重写。
        List<GitCommitReportRequest.FileDetail> incoming = List.of(filePatch("a.go"), filePatch("b.go"));
        assertTrue(GitCommitIngestService.isRicherThanStored(incoming, 2, 0));
    }

    @Test
    void isRicherThanStored_trueWhenMoreFilesThanStored() {
        // 之前的明细被截断过（只存了 1 行），这次拿到了 3 行。
        List<GitCommitReportRequest.FileDetail> incoming = List.of(
                fileNoPatch("a.go"), fileNoPatch("b.go"), fileNoPatch("c.go"));
        assertTrue(GitCommitIngestService.isRicherThanStored(incoming, 1, 0));
    }

    @Test
    void isRicherThanStored_falseWhenAllBinaryCommitReportedAgain() {
        // 全二进制 commit：两边 patch 数都是 0，行数也一样 —— 同样不该重写。
        List<GitCommitReportRequest.FileDetail> incoming = List.of(fileNoPatch("logo.png"));
        assertFalse(GitCommitIngestService.isRicherThanStored(incoming, 1, 0));
    }

    @Test
    void handle_reportsFailedCountWhenPersistenceThrows() {
        // 落库异常此前被逐条 catch 掉、接口照样返 200，客户端据此推进 gitlog cursor，
        // 这批 commit 就永久消失了。failed 必须回传，且不得触发归因重算。
        GitCommitRepository commitRepo = mock(GitCommitRepository.class);
        GitCommitFileRepository fileRepo = mock(GitCommitFileRepository.class);
        AgentDeviceRepository deviceRepo = mock(AgentDeviceRepository.class);
        GitCommitAttributionEngine engine = mock(GitCommitAttributionEngine.class);
        when(deviceRepo.findByAgentId(anyString())).thenReturn(Optional.empty());
        when(commitRepo.findByRepoUrlAndCommitHash(anyString(), anyString()))
                .thenThrow(new RuntimeException("max_allowed_packet exceeded"));

        // inner 只用来跑真实的 ingestOne（Spring 里是自代理），它自己的 self 不会被用到。
        GitCommitIngestService inner =
                new GitCommitIngestService(commitRepo, fileRepo, objectMapper, deviceRepo, engine, null);
        GitCommitIngestService svc =
                new GitCommitIngestService(commitRepo, fileRepo, objectMapper, deviceRepo, engine, inner);

        GitCommitIngestService.IngestSummary summary = svc.handle(
                requestWith(commitItem("abc")), new SignatureContext("agent-1", "u1", "host-1"));

        assertEquals(1, summary.failed());
        assertEquals(0, summary.inserted());
        verify(engine, never()).enqueue(any());
    }

    @Test
    void handle_emptyCommitsReportsNoFailure() {
        GitCommitIngestService svc = new GitCommitIngestService(
                mock(GitCommitRepository.class), mock(GitCommitFileRepository.class), objectMapper,
                mock(AgentDeviceRepository.class), mock(GitCommitAttributionEngine.class), null);

        GitCommitIngestService.IngestSummary summary =
                svc.handle(requestWith(), new SignatureContext("agent-1", "u1", "host-1"));

        assertEquals(0, summary.failed());
    }

    private static GitCommitReportRequest requestWith(GitCommitReportRequest.Item... items) {
        GitCommitReportRequest req = new GitCommitReportRequest();
        req.setAgentId("agent-1");
        req.setCapturedAt(LocalDateTime.of(2026, 8, 10, 10, 0));
        req.setCommits(List.of(items));
        return req;
    }

    private static GitCommitReportRequest.Item commitItem(String hash) {
        GitCommitReportRequest.Item item = new GitCommitReportRequest.Item();
        item.setRepoUrl("https://gitlab.example.com/a/b.git");
        item.setCommitHash(hash);
        item.setCommitTime(LocalDateTime.of(2026, 8, 10, 9, 30));
        item.setAuthorEmail("dev@example.com");
        return item;
    }

    private static GitCommitReportRequest.FileDetail filePatch(String path) {
        GitCommitReportRequest.FileDetail f = new GitCommitReportRequest.FileDetail();
        f.setPath(path);
        f.setHasPatch(true);
        return f;
    }

    private static GitCommitReportRequest.FileDetail fileNoPatch(String path) {
        GitCommitReportRequest.FileDetail f = new GitCommitReportRequest.FileDetail();
        f.setPath(path);
        return f;
    }

    @Test
    void deserializeAgentFilePayload() throws Exception {
        String json = """
                {
                  "repo_url": "https://gitlab.example.com/a/b.git",
                  "commit_hash": "abc",
                  "commit_time": "2026-05-19T10:00:00",
                  "files": [{
                    "path": "x.go",
                    "lines_added": 1,
                    "lines_deleted": 0,
                    "is_binary": false,
                    "has_patch": true,
                    "patch_gzip_base64": "H4sIAAAAAAAA/1OqBgCS3s3RAgAAAA==",
                    "patch_bytes": 10,
                    "patch_truncated": false,
                    "sort_order": 0
                  }]
                }
                """;
        GitCommitReportRequest.Item item = objectMapper.readValue(json, GitCommitReportRequest.Item.class);
        assertEquals(1, item.getFiles().size());
        GitCommitReportRequest.FileDetail f = item.getFiles().get(0);
        assertEquals("x.go", f.getPath());
        assertTrue(f.isHasPatch());
        assertFalse(f.isBinary());
    }
}
