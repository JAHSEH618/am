package com.am.server.web.support;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.web.dto.ProjectSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 项目透视窗口快照：list / detail / git-commits 共享同一次 6 路聚合结果，45s TTL。
 *
 * <p>避免 detail 为取单个项目重复扫描全窗口 event 表（性能文档 M5）。
 * gz
 */
@Component
@RequiredArgsConstructor
public class ProjectsWindowSnapshotCache {

    static final Duration TTL = Duration.ofSeconds(45);

    private final AiSessionEventRepository eventRepository;
    private final AiSessionRepository aiSessionRepository;
    private final GitCommitRepository gitCommitRepository;

    private final ConcurrentHashMap<CacheKey, CachedEntry> cache = new ConcurrentHashMap<>();

    public record ProjectMeta(LocalDateTime lastActivity, String repoUrl) {}

    public static final class Snapshot {
        private final Map<String, ProjectSummaryDto> summaryByProject;
        private final Map<String, ProjectMeta> metaByProject;

        Snapshot(Map<String, ProjectSummaryDto> summaryByProject, Map<String, ProjectMeta> metaByProject) {
            this.summaryByProject = summaryByProject;
            this.metaByProject = metaByProject;
        }

        public List<ProjectSummaryDto> allSummaries() {
            return summaryByProject.values().stream()
                    .sorted(Comparator.comparingLong(ProjectSummaryDto::getTotalTokens).reversed())
                    .map(ProjectsWindowSnapshotCache::copySummary)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        public Optional<ProjectSummaryDto> summary(String projectName) {
            ProjectSummaryDto s = summaryByProject.get(projectName);
            return s == null ? Optional.empty() : Optional.of(copySummary(s));
        }

        public Optional<ProjectMeta> meta(String projectName) {
            return Optional.ofNullable(metaByProject.get(projectName));
        }
    }

    public Snapshot getOrLoad(LocalDateTime t0, LocalDateTime t1, Collection<String> activeTypes) {
        CacheKey key = CacheKey.of(t0, t1, activeTypes);
        Instant now = Instant.now();
        CachedEntry hit = cache.get(key);
        if (hit != null && hit.expiresAt.isAfter(now)) {
            return hit.snapshot;
        }
        Snapshot fresh = load(t0, t1, activeTypes);
        cache.put(key, new CachedEntry(fresh, now.plus(TTL)));
        return fresh;
    }

    private Snapshot load(LocalDateTime t0, LocalDateTime t1, Collection<String> activeTypes) {
        List<Object[]> rows = eventRepository.aggregateByProjectInWindowAndTargetTypeIn(t0, t1, activeTypes);
        if (rows.isEmpty()) {
            return new Snapshot(Map.of(), Map.of());
        }

        Map<String, long[]> userAssistantByProject = projectUserAssistantSplit(
                aiSessionRepository.aggregateUserAssistantMessagesByProjectAndTargetTypeIn(t0, t1, activeTypes));
        Map<String, long[]> inOutTokensByProject = projectInputOutputTokens(
                aiSessionRepository.aggregateInputOutputTokensByProjectAndTargetTypeIn(t0, t1, activeTypes));

        Map<String, String> topModelByProject = new HashMap<>();
        Map<String, Long> topModelTokens = new HashMap<>();
        for (Object[] mm : eventRepository.aggregateProjectModelMatrixAndTargetTypeIn(t0, t1, activeTypes)) {
            String project = asString(mm[0]);
            String model = asString(mm[1]);
            long tokens = toLong(mm[2]);
            if (project == null || model == null) {
                continue;
            }
            if (tokens > topModelTokens.getOrDefault(project, -1L)) {
                topModelTokens.put(project, tokens);
                topModelByProject.put(project, model);
            }
        }

        Map<String, ProjectMeta> metaByProject = new HashMap<>();
        for (Object[] meta : eventRepository.aggregateProjectMetaAndTargetTypeIn(t0, t1, activeTypes)) {
            String project = asString(meta[0]);
            if (project == null) {
                continue;
            }
            metaByProject.put(project, new ProjectMeta(asLocalDateTime(meta[1]), asString(meta[2])));
        }

        Map<String, Long> commitsByRepo = new HashMap<>();
        for (Object[] rr : gitCommitRepository.countGroupedByRepoUrlInCommitWindow(t0, t1)) {
            String repo = asString(rr[0]);
            if (repo == null) {
                continue;
            }
            commitsByRepo.put(repo, toLong(rr[1]));
        }

        Map<String, ProjectSummaryDto> summaryByProject = new HashMap<>(rows.size() * 2);
        for (Object[] r : rows) {
            String project = asString(r[0]);
            if (project == null) {
                continue;
            }
            ProjectSummaryDto d = new ProjectSummaryDto();
            d.setProjectName(project);
            d.setTotalTokens(toLong(r[1]));
            d.setMessageCount((int) toLong(r[2]));
            d.setSessionCount((int) toLong(r[3]));
            d.setUserCount((int) toLong(r[4]));
            applyProjectSplitMetrics(d, userAssistantByProject, inOutTokensByProject);

            ProjectMeta meta = metaByProject.get(project);
            if (meta != null) {
                d.setLastActivity(meta.lastActivity());
                d.setRepoUrl(meta.repoUrl());
                if (meta.repoUrl() != null) {
                    d.setGitCommitCount(commitsByRepo.getOrDefault(meta.repoUrl(), 0L).intValue());
                }
            }
            d.setTopModel(topModelByProject.get(project));
            summaryByProject.put(project, d);
        }
        return new Snapshot(summaryByProject, metaByProject);
    }

    private static ProjectSummaryDto copySummary(ProjectSummaryDto s) {
        ProjectSummaryDto d = new ProjectSummaryDto();
        d.setProjectName(s.getProjectName());
        d.setRepoUrl(s.getRepoUrl());
        d.setSessionCount(s.getSessionCount());
        d.setMessageCount(s.getMessageCount());
        d.setUserMessageCount(s.getUserMessageCount());
        d.setAssistantMessageCount(s.getAssistantMessageCount());
        d.setInputTokens(s.getInputTokens());
        d.setOutputTokens(s.getOutputTokens());
        d.setTotalTokens(s.getTotalTokens());
        d.setUserCount(s.getUserCount());
        d.setGitCommitCount(s.getGitCommitCount());
        d.setLastActivity(s.getLastActivity());
        d.setTopModel(s.getTopModel());
        return d;
    }

    static void applyProjectSplitMetrics(
            ProjectSummaryDto d,
            Map<String, long[]> userAssistantByProject,
            Map<String, long[]> inOutTokensByProject) {
        String project = d.getProjectName();
        long[] ua = userAssistantByProject.get(project);
        if (ua != null) {
            d.setUserMessageCount((int) ua[0]);
            d.setAssistantMessageCount((int) ua[1]);
        } else {
            d.setUserMessageCount(0);
            d.setAssistantMessageCount(d.getMessageCount());
        }
        long[] io = inOutTokensByProject.get(project);
        if (io != null) {
            d.setInputTokens(io[0]);
            d.setOutputTokens(io[1]);
        } else {
            d.setInputTokens(d.getTotalTokens());
            d.setOutputTokens(0L);
        }
    }

    private record CacheKey(LocalDateTime t0, LocalDateTime t1, String activeTypesKey) {
        static CacheKey of(LocalDateTime t0, LocalDateTime t1, Collection<String> activeTypes) {
            String typesKey = activeTypes.stream().sorted().collect(Collectors.joining("\0"));
            return new CacheKey(t0, t1, typesKey);
        }
    }

    private record CachedEntry(Snapshot snapshot, Instant expiresAt) {}

    private static Map<String, long[]> projectUserAssistantSplit(List<Object[]> rows) {
        Map<String, long[]> out = new HashMap<>(Math.max(8, rows.size() * 2));
        for (Object[] r : rows) {
            String p = asString(r[0]);
            if (p == null) {
                continue;
            }
            out.put(p, new long[]{toLong(r[1]), toLong(r[2])});
        }
        return out;
    }

    private static Map<String, long[]> projectInputOutputTokens(List<Object[]> rows) {
        Map<String, long[]> out = new HashMap<>(Math.max(8, rows.size() * 2));
        for (Object[] r : rows) {
            String p = asString(r[0]);
            if (p == null) {
                continue;
            }
            out.put(p, new long[]{toLong(r[1]), toLong(r[2])});
        }
        return out;
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static LocalDateTime asLocalDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate().atStartOfDay();
        }
        return null;
    }
}
