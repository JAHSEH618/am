package com.am.server.service;

import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitFile;
import com.am.server.domain.git.GitCommitFileRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.git.GitPathNormalizer;
import com.am.server.web.dto.GitCommitFileRowDto;
import com.am.server.web.dto.GitCommitPatchDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Git 提交文件明细与 patch 查询。
 * gz
 */
@Service
@RequiredArgsConstructor
public class GitCommitDetailService {

    private final GitCommitRepository gitCommitRepository;
    private final GitCommitFileRepository gitCommitFileRepository;

    public Optional<GitCommit> findCommit(String repoUrl, String commitHash) {
        if (repoUrl == null || repoUrl.isBlank() || commitHash == null || commitHash.isBlank()) {
            return Optional.empty();
        }
        return gitCommitRepository.findByRepoUrlAndCommitHash(repoUrl.trim(), commitHash.trim());
    }

    public List<GitCommitFileRowDto> listFiles(String repoUrl, String commitHash) {
        Optional<GitCommit> commit = findCommit(repoUrl, commitHash);
        if (commit.isEmpty()) {
            return List.of();
        }
        // 走不含 patch_gzip 的投影：列表只要元数据，取实体会把整个 commit 的 diff blob 全读进堆。
        List<GitCommitFileRepository.FileRow> rows =
                gitCommitFileRepository.findRowsByCommitIdOrderBySortOrderAsc(commit.get().getId());
        List<GitCommitFileRowDto> out = new ArrayList<>(rows.size());
        for (GitCommitFileRepository.FileRow f : rows) {
            GitCommitFileRowDto d = new GitCommitFileRowDto();
            d.setPath(f.getPath());
            d.setOldPath(f.getOldPath());
            d.setChangeType(f.getChangeType());
            d.setLinesAdded(nz(f.getLinesAdded()));
            d.setLinesDeleted(nz(f.getLinesDeleted()));
            d.setBinary(nz(f.getIsBinary()) == 1);
            d.setHasPatch(nz(f.getHasPatch()) == 1);
            d.setPatchTruncated(nz(f.getPatchTruncated()) == 1);
            d.setTruncateReason(f.getTruncateReason());
            out.add(d);
        }
        return out;
    }

    public Optional<GitCommitPatchDto> getPatch(String repoUrl, String commitHash, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        Optional<GitCommit> commit = findCommit(repoUrl, commitHash);
        if (commit.isEmpty()) {
            return Optional.empty();
        }
        // 只取目标文件那一行——原先是遍历整个 commit 的实体列表再挑一条，
        // 等于为看一个文件的 diff 把所有文件的 blob 都读进了堆。
        String want = GitPathNormalizer.normalize(path);
        return gitCommitFileRepository.findByCommitIdAndPath(commit.get().getId(), want)
                .stream()
                .findFirst()
                .map(GitCommitDetailService::toPatchDto);
    }

    private static GitCommitPatchDto toPatchDto(GitCommitFile f) {
        GitCommitPatchDto d = new GitCommitPatchDto();
        d.setPath(f.getPath());
        d.setBinary(nz(f.getIsBinary()) == 1);
        d.setPatchTruncated(nz(f.getPatchTruncated()) == 1);
        d.setTruncateReason(f.getTruncateReason());
        if (d.isBinary()) {
            d.setPatch("");
            return d;
        }
        if (f.getPatchGzip() == null || f.getPatchGzip().length == 0) {
            d.setPatch("");
            return d;
        }
        d.setPatch(gunzipUtf8(f.getPatchGzip()));
        return d;
    }

    private static String gunzipUtf8(byte[] gz) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
