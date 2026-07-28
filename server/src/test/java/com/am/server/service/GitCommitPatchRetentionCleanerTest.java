package com.am.server.service;

import com.am.server.Application;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitFile;
import com.am.server.domain.git.GitCommitFileRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.system.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保留期清理会不可逆地丢掉 patch 内容，所以这里把"清什么 / 不清什么"钉死：
 * 只清超期 commit 的 blob，文件行必须原样留着，窗口内的 commit 一个字节都不能动。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class GitCommitPatchRetentionCleanerTest {

    private static final String REPO = "https://example.invalid/retention-test.git";

    @Autowired
    private GitCommitPatchRetentionCleaner cleaner;

    @Autowired
    private GitCommitRepository commitRepository;

    @Autowired
    private GitCommitFileRepository fileRepository;

    @Autowired
    private SystemConfigService systemConfigService;

    @Test
    void cleanup_clearsExpiredBlobsButKeepsRowsAndRecentPatches() {
        int retentionDays = systemConfigService.getInt(
                GitCommitPatchRetentionCleaner.CONFIG_KEY_RETENTION_DAYS, 60);

        GitCommit old = commitRepository.save(commit("aa-retention-old", LocalDateTime.now().minusDays(retentionDays + 5)));
        GitCommit recent = commitRepository.save(commit("bb-retention-new", LocalDateTime.now().minusDays(1)));
        fileRepository.save(file(old.getId(), "old-a.go"));
        fileRepository.save(file(old.getId(), "old-b.go"));
        fileRepository.save(file(recent.getId(), "new-a.go"));

        cleaner.cleanup();

        List<GitCommitFileRepository.FileRow> oldRows =
                fileRepository.findRowsByCommitIdOrderBySortOrderAsc(old.getId());
        assertThat(oldRows).as("文件行必须保留——列表、增删行数、归因都还要用").hasSize(2);
        assertThat(oldRows).allSatisfy(r -> {
            assertThat(r.getHasPatch()).isZero();
            assertThat(r.getTruncateReason()).isEqualTo("expired");
            assertThat(r.getLinesAdded()).as("元数据不受影响").isEqualTo(7);
        });
        assertThat(fileRepository.findByCommitIdAndPath(old.getId(), "old-a.go"))
                .allSatisfy(f -> assertThat(f.getPatchGzip()).as("blob 已清空").isNull());

        assertThat(fileRepository.findByCommitIdAndPath(recent.getId(), "new-a.go"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.getHasPatch()).as("保留期内的 patch 不得被动").isEqualTo(1);
                    assertThat(f.getPatchGzip()).isNotNull();
                });
    }

    private GitCommit commit(String hash, LocalDateTime commitTime) {
        GitCommit c = new GitCommit();
        c.setAgentId("agent-retention");
        c.setUserCode("U-retention");
        c.setHostHash("h-retention");
        c.setRepoUrl(REPO);
        c.setCommitHash(hash);
        c.setCommitTime(commitTime);
        return c;
    }

    private GitCommitFile file(Long commitId, String path) {
        GitCommitFile f = new GitCommitFile();
        f.setCommitId(commitId);
        f.setPath(path);
        f.setChangeType("M");
        f.setLinesAdded(7);
        f.setLinesDeleted(2);
        f.setHasPatch(1);
        f.setPatchGzip("not-really-gzip".getBytes(StandardCharsets.UTF_8));
        f.setPatchBytes(15);
        return f;
    }
}
