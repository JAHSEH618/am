package com.am.server.web;

import com.am.server.service.GitCommitDetailService;
import com.am.server.web.dto.GitCommitFileRowDto;
import com.am.server.web.dto.GitCommitPatchDto;
import com.am.server.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Git 提交文件明细与 diff（按需加载，不含于列表接口）。
 * gz
 */
@RestController
@RequestMapping("/api/v1/git-commits")
@RequiredArgsConstructor
public class GitCommitDetailController {

    private final GitCommitDetailService gitCommitDetailService;

    @GetMapping("/files")
    public R<List<GitCommitFileRowDto>> listFiles(
            @RequestParam("repo_url") String repoUrl,
            @RequestParam("commit_hash") String commitHash) {
        return R.ok(gitCommitDetailService.listFiles(repoUrl, commitHash));
    }

    @GetMapping("/patch")
    public R<GitCommitPatchDto> getPatch(
            @RequestParam("repo_url") String repoUrl,
            @RequestParam("commit_hash") String commitHash,
            @RequestParam("path") String path) {
        if (gitCommitDetailService.findCommit(repoUrl, commitHash).isEmpty()) {
            return R.fail(404, "commit not found");
        }
        return gitCommitDetailService.getPatch(repoUrl, commitHash, path)
                .map(R::ok)
                .orElseGet(() -> R.fail(404, "file not found for commit"));
    }
}
