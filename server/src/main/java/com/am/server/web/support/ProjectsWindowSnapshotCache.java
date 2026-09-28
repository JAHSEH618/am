package com.am.server.web.support;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.web.dto.ProjectSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 项目透视窗口快照：list / detail / git-commits 共享同一次聚合结果，45s TTL。
 *
 * <p>避免 detail 为取单个项目重复扫描全窗口 event 表（性能文档 M5）。
 * <ul>
 *   <li>event 表只扫一遍：(project, model, user) 一次分组，在内存折出项目汇总 / Top 模型 / 元信息
 *       （原来三条 SQL 各扫一遍同一窗口事件）。</li>
 *   <li>single-flight：同一窗口并发 miss（切窗时 list + detail 同时到）只有一个线程去算，其余等结果。</li>
 *   <li>过期条目随写入清理，静态表不随选过的窗口只增不减。</li>
 * </ul>
 * gz
 */
@Component
@RequiredArgsConstructor
public class ProjectsWindowSnapshotCache {

    static final Duration TTL = Duration.ofSeconds(45);

    private final AiSessionEventRepository eventRepository;
    private final AiSessionRepository aiSessionRepository;
    private final GitCommitRepository gitCommitRepository;

    private final TtlSingleFlightCache<CacheKey, Snapshot> cache = new TtlSingleFlightCache<>(TTL);

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
        return cache.get(CacheKey.of(t0, t1, activeTypes), () -> load(t0, t1, activeTypes));
    }

    private Snapshot load(LocalDateTime t0, LocalDateTime t1, Collection<String> activeTypes) {
        Folded folded = foldProjectRows(
                eventRepository.aggregateProjectModelUserInWindowAndTargetTypeIn(t0, t1, activeTypes));
        if (folded.summaries().isEmpty()) {
            return new Snapshot(Map.of(), Map.of());
        }

        Map<String, long[]> userAssistantByProject = projectUserAssistantSplit(
                aiSessionRepository.aggregateUserAssistantMessagesByProjectAndTargetTypeIn(t0, t1, activeTypes));
        Map<String, long[]> inOutTokensByProject = projectInputOutputTokens(
                aiSessionRepository.aggregateInputOutputTokensByProjectAndTargetTypeIn(t0, t1, activeTypes));

        Map<String, Long> commitsByRepo = new HashMap<>();
        for (Object[] rr : gitCommitRepository.countGroupedByRepoUrlInCommitWindow(t0, t1)) {
            String repo = asString(rr[0]);
            if (repo == null) {
                continue;
            }
            commitsByRepo.put(repo, toLong(rr[1]));
        }

        for (ProjectSummaryDto d : folded.summaries().values()) {
            applyProjectSplitMetrics(d, userAssistantByProject, inOutTokensByProject);
            if (d.getRepoUrl() != null) {
                d.setGitCommitCount(commitsByRepo.getOrDefault(d.getRepoUrl(), 0L).intValue());
            }
        }
        return new Snapshot(folded.summaries(), folded.meta());
    }

    record Folded(Map<String, ProjectSummaryDto> summaries, Map<String, ProjectMeta> meta) {}

    /**
     * 把 (project, model, user) 分组行折成项目级结果，口径与原三条 SQL 一致：
     * tokens / messages / sessions 求和（会话只落在一组），users 去重，
     * Top 模型 = 非空 model 中 tokens 最大者，lastActivity = MAX(eventTime)，repoUrl = MAX(repoUrl)。
     */
    static Folded foldProjectRows(List<Object[]> rows) {
        Map<String, ProjectSummaryDto> summaries = new HashMap<>();
        Map<String, java.util.Set<String>> usersByProject = new HashMap<>();
        Map<String, Map<String, Long>> tokensByProjectModel = new HashMap<>();
        Map<String, LocalDateTime> lastByProject = new HashMap<>();
        Map<String, String> repoByProject = new HashMap<>();
        for (Object[] r : rows) {
            String project = asString(r[0]);
            if (project == null) {
                continue;
            }
            String model = asString(r[1]);
            String user = asString(r[2]);
            long tokens = toLong(r[3]);
            ProjectSummaryDto d = summaries.computeIfAbsent(project, p -> {
                ProjectSummaryDto x = new ProjectSummaryDto();
                x.setProjectName(p);
                return x;
            });
            d.setTotalTokens(d.getTotalTokens() + tokens);
            d.setMessageCount(d.getMessageCount() + (int) toLong(r[4]));
            d.setSessionCount(d.getSessionCount() + (int) toLong(r[5]));
            if (user != null) {
                usersByProject.computeIfAbsent(project, p -> new java.util.HashSet<>()).add(user);
            }
            if (model != null) {
                tokensByProjectModel.computeIfAbsent(project, p -> new HashMap<>()).merge(model, tokens, Long::sum);
            }
            LocalDateTime last = asLocalDateTime(r[6]);
            if (last != null) {
                lastByProject.merge(project, last, (a, b) -> a.isAfter(b) ? a : b);
            }
            String repo = asString(r[7]);
            if (repo != null) {
                repoByProject.merge(project, repo, (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        }
        Map<String, ProjectMeta> meta = new HashMap<>();
        for (ProjectSummaryDto d : summaries.values()) {
            String project = d.getProjectName();
            d.setUserCount(usersByProject.getOrDefault(project, java.util.Set.of()).size());
            Map<String, Long> byModel = tokensByProjectModel.get(project);
            if (byModel != null) {
                byModel.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .ifPresent(top -> d.setTopModel(top.getKey()));
            }
            ProjectMeta m = new ProjectMeta(lastByProject.get(project), repoByProject.get(project));
            meta.put(project, m);
            d.setLastActivity(m.lastActivity());
            d.setRepoUrl(m.repoUrl());
        }
        return new Folded(summaries, meta);
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
