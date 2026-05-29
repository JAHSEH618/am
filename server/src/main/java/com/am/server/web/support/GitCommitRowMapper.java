package com.am.server.web.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.am.server.domain.git.GitCommit;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.web.dto.ProjectGitCommitRowDto;

import java.util.List;

/**
 * git_commit 实体 → 项目透视 / 员工数据共用弹框 DTO
 * gz
 */
public final class GitCommitRowMapper {

    private GitCommitRowMapper() {
    }

    public static ProjectGitCommitRowDto toRow(GitCommit g, EmployeeDisplayService employeeDisplayService,
                                               ObjectMapper objectMapper) {
        ProjectGitCommitRowDto d = new ProjectGitCommitRowDto();
        d.setRepoUrl(g.getRepoUrl());
        d.setCommitHash(g.getCommitHash());
        d.setCommitTime(g.getCommitTime());
        d.setUserCode(g.getUserCode());
        d.setUserDisplay(employeeDisplayService.displayOf(g.getUserCode()));
        d.setAuthorName(g.getAuthorName());
        d.setAuthorEmail(g.getAuthorEmail());
        d.setMessageSubject(g.getMessageSubject());
        d.setBranchName(g.getBranchName());
        d.setFilesChanged(g.getFilesChanged() == null ? 0 : g.getFilesChanged());
        d.setLinesAdded(g.getLinesAdded() == null ? 0 : g.getLinesAdded());
        d.setLinesDeleted(g.getLinesDeleted() == null ? 0 : g.getLinesDeleted());
        d.setPathStats(parsePathStats(objectMapper, g.getPathStatsJson()));
        d.setDetailStatus(g.getDetailStatus());
        return d;
    }

    public static List<ProjectGitCommitRowDto.PathStatEntry> parsePathStats(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ProjectGitCommitRowDto.PathStatEntry>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
