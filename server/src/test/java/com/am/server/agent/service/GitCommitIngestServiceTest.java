package com.am.server.agent.service;

import com.am.server.agent.api.dto.GitCommitReportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
