package com.am.server.agent.service;

import com.am.server.Application;
import com.am.server.agent.api.dto.GitCommitReportRequest;
import com.am.server.agent.security.SignatureContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 打真库、走真事务（不加 @Transactional，每次 ingestOne 自己提交）：新 commit 的 file 行不再先空删，
 * 父行 INSERT 改由事务提交时刷写——钉死"父行、子行都真的落库"（1.3.2 之前父行被 clear 静默丢掉过），
 * 以及"更富的重报仍 delete + insert 重写"。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class GitCommitIngestPersistenceTest {

    private static final String REPO = "https://example.invalid/ingest-persistence-test.git";

    @Autowired
    private GitCommitIngestService service;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE f FROM git_commit_file f JOIN git_commit c ON c.id = f.commit_id WHERE c.repo_url = ?", REPO);
        jdbc.update("DELETE FROM git_commit WHERE repo_url = ?", REPO);
    }

    @Test
    void newCommitPersistsParentAndFiles_thenRicherReReportReplacesFiles() {
        SignatureContext ctx = new SignatureContext("agent-persist", "U-persist", "host-persist");

        service.ingestOne(item(file("a.go"), file("b.go")), ctx, Set.of(), false);

        Long commitId = jdbc.queryForObject(
                "SELECT id FROM git_commit WHERE repo_url = ? AND commit_hash = 'persist1'", Long.class, REPO);
        assertThat(commitId).as("父行必须落库").isNotNull();
        assertThat(filePaths(commitId)).containsExactly("a.go", "b.go");

        service.ingestOne(item(file("a.go"), file("b.go"), file("c.go")), ctx, Set.of(), false);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM git_commit WHERE repo_url = ?", Integer.class, REPO))
                .isEqualTo(1);
        assertThat(filePaths(commitId)).containsExactly("a.go", "b.go", "c.go");
    }

    private List<String> filePaths(Long commitId) {
        return jdbc.queryForList(
                "SELECT path FROM git_commit_file WHERE commit_id = ? ORDER BY sort_order, path", String.class, commitId);
    }

    private static GitCommitReportRequest.Item item(GitCommitReportRequest.FileDetail... files) {
        GitCommitReportRequest.Item item = new GitCommitReportRequest.Item();
        item.setRepoUrl(REPO);
        item.setCommitHash("persist1");
        item.setCommitTime(LocalDateTime.of(2026, 9, 1, 10, 0));
        item.setAuthorEmail("dev@example.com");
        item.setFiles(List.of(files));
        return item;
    }

    private static GitCommitReportRequest.FileDetail file(String path) {
        GitCommitReportRequest.FileDetail f = new GitCommitReportRequest.FileDetail();
        f.setPath(path);
        f.setLinesAdded(1);
        return f;
    }
}
