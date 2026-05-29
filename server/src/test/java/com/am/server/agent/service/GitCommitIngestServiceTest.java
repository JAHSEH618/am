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
