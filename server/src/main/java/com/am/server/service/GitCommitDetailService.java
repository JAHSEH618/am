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
        List<GitCommitFile> rows = gitCommitFileRepository.findByCommitIdOrderBySortOrderAsc(commit.get().getId());
        List<GitCommitFileRowDto> out = new ArrayList<>(rows.size());
        for (GitCommitFile f : rows) {
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
        String want = GitPathNormalizer.normalize(path);
        for (GitCommitFile f : gitCommitFileRepository.findByCommitIdOrderBySortOrderAsc(commit.get().getId())) {
            if (!pathMatches(want, f)) {
                continue;
            }
            return Optional.of(toPatchDto(f));
        }
        return Optional.empty();
    }

    private static boolean pathMatches(String want, GitCommitFile f) {
        if (GitPathNormalizer.pathsEqual(want, f.getPath())) {
            return true;
        }
        return f.getOldPath() != null && GitPathNormalizer.pathsEqual(want, f.getOldPath());
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
