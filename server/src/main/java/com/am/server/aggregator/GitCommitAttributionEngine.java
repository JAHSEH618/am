package com.am.server.aggregator;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitAttribution;
import com.am.server.domain.git.GitCommitAttributionRepository;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * commit → AI 产出归因引擎（《管理后台-产出归因与能力使用分析 v1.0》§2）：把每个非 merge commit
 * 归成 B（trailer 确定）/ A（±30min 会话窗口疑似）/ NONE 三档写入 {@code git_commit_attribution}，
 * 单一归属保证任意维度组合求和 = 总数。
 *
 * <p><b>归属规则</b>（§2.4）：
 * <ol>
 *   <li>B 档优先直接映射：message_body 命中 {@link AiTrailerRules}，target_type 取 trailer；
 *       再在窗口内找该工具的会话（优先 repo 归一相等者）确定 session/model，找不到则 session 置空、
 *       model 记 unknown。</li>
 *   <li>A 档单一归属：repo 归一相等 + commit_time ∈ [started_at-30min, last_activity+30min] 的候选中，
 *       取「commit 前最近一次活动信号」的会话；并列取窗口重叠时长最长者，再并列取 session id 小者（确定性）。
 *       活动信号 = 会话事件时间（event_time ≤ commit_time 的最近一条）；对 hermes 这类基本不写 event 的
 *       provider，以 min(last_activity, commit_time) 为代理信号（started_at &gt; commit_time 则无信号）。</li>
 * </ol>
 * repo 归一与现网 {@code GitCommitRepository.aiPenetrationLines} 的 SQL 语义逐字对齐
 * （剥尾部重复 .git → 剥尾部 / → lowercase），保证渗透率迁移（P5）前后口径一致。
 *
 * <p><b>计算通道</b>：ingest 增量（{@link #enqueue} 15s 去抖）+ 夜间批（00:30 重算近 2 天，
 * 收口迟到会话/迟到 commit）+ 启动期全量回溯（GitCommitAttributionBackfillPatch，backfilled=1）。
 * 写入恒为 delete + insert（按 commit_id），天然幂等。
 * gz
 */
@Component
@RequiredArgsConstructor
public class GitCommitAttributionEngine {

    private static final Logger log = LoggerFactory.getLogger(GitCommitAttributionEngine.class);

    public static final String TASK_CODE_NIGHTLY = "git_attribution_nightly";
    public static final String CONFIG_KEY_TRAILER_RULES = "attribution.trailer_rules";

    /** A 档窗口半径，沿用现网 aiPenetrationLines 公式的 ±30min。 */
    private static final long WINDOW_MINUTES = 30;
    /** 夜间批回看天数（§2.7 默认 2 天）。 */
    private static final int NIGHTLY_LOOKBACK_DAYS = 2;
    /** 每事务写入行数上限：回溯期一天可能几千 commit，分批避免长事务。 */
    private static final int TX_CHUNK = 200;
    private static final long ENQUEUE_DEBOUNCE_MS = 15_000L;

    /** 自代理：内部自调用走 Spring 代理，@Transactional(replaceChunk) 才生效。见 DailySummaryAggregator 同款。 */
    @Autowired
    @Lazy
    private GitCommitAttributionEngine self;

    private final GitCommitRepository gitCommitRepository;
    private final GitCommitAttributionRepository attributionRepository;
    private final AiSessionRepository sessionRepository;
    private final AiSessionEventRepository eventRepository;
    private final SystemConfigService systemConfigService;
    private final DynamicScheduledTaskManager scheduledTaskManager;

    /** ingest 增量去抖队列：date → 受影响 user 并集，15s 合并一次。 */
    private final ConcurrentHashMap<LocalDate, Set<String>> pendingUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LocalDate, ScheduledFuture<?>> pendingRefresh = new ConcurrentHashMap<>();
    private final ScheduledExecutorService refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "git-attribution-refresh");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        systemConfigService.seedIfAbsent(CONFIG_KEY_TRAILER_RULES, "[]", "json", "attribution", false,
                "AI trailer 追加匹配规则（JSON 数组，叠加在内置常量规则之上）");
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE_NIGHTLY,
                        "Git 产出归因夜间批（重算近 2 天）",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 30 0 * * *",
                        true,
                        true,
                        true,
                        "每天 00:30 (Asia/Shanghai) 重算近 2 天的 commit→AI 归因，修正迟到会话 / 迟到 commit 与未闭合窗口右缘。",
                        "Asia/Shanghai"),
                this::nightlyJob);
    }

    @PreDestroy
    public void shutdown() {
        refreshExecutor.shutdownNow();
    }

    public void nightlyJob() {
        LocalDate today = LocalDate.now();
        LocalDateTime from = today.minusDays(NIGHTLY_LOOKBACK_DAYS).atStartOfDay();
        LocalDateTime to = today.atStartOfDay();
        try {
            int n = attributeWindow(from, to, false);
            log.info("git attribution nightly: window=[{}, {}) commits={}", from, to, n);
        } catch (Exception e) {
            log.error("git attribution nightly failed: window=[{}, {})", from, to, e);
        }
    }

    /**
     * ingest 增量入队：commit 落库后按 (commit 日期 → user) 入队，{@value #ENQUEUE_DEBOUNCE_MS}ms
     * 去抖合并（高频上报只算一次）。会话仍在进行导致的窗口右缘未闭合由夜间批兜底修正。
     */
    public void enqueue(Map<LocalDate, Set<String>> dateUsers) {
        if (dateUsers == null || dateUsers.isEmpty()) {
            return;
        }
        for (Map.Entry<LocalDate, Set<String>> en : dateUsers.entrySet()) {
            LocalDate d = en.getKey();
            Set<String> users = en.getValue();
            if (d == null || users == null || users.isEmpty()) {
                continue;
            }
            pendingUsers.compute(d, (date, acc) -> {
                Set<String> merged = (acc != null) ? acc : ConcurrentHashMap.newKeySet();
                merged.addAll(users);
                return merged;
            });
            pendingRefresh.compute(d, (date, prev) -> {
                if (prev != null) {
                    prev.cancel(false);
                }
                return refreshExecutor.schedule(() -> {
                    Set<String> drained = pendingUsers.remove(date);
                    try {
                        if (drained != null && !drained.isEmpty()) {
                            int n = attributeDayUsers(date, drained);
                            log.debug("git attribution refreshed (debounced): date={} commits={}", date, n);
                        }
                    } catch (Exception ex) {
                        log.warn("git attribution refresh failed: date={} reason={}", date, ex.toString());
                    } finally {
                        pendingRefresh.remove(date);
                    }
                }, ENQUEUE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            });
        }
    }

    /** 重算窗口 [from, to) 内全部非 merge commit。返回处理的 commit 数。 */
    public int attributeWindow(LocalDateTime from, LocalDateTime to, boolean backfilled) {
        return attributeCommits(gitCommitRepository.findNonMergeInCommitWindow(from, to), backfilled);
    }

    /** 增量：重算某日指定用户的非 merge commit。 */
    public int attributeDayUsers(LocalDate day, Set<String> users) {
        List<GitCommit> commits = gitCommitRepository.findNonMergeInCommitWindowAndUserCodeIn(
                day.atStartOfDay(), day.plusDays(1).atStartOfDay(), users);
        return attributeCommits(commits, false);
    }

    /** 核心：按用户分组取候选会话与活动信号，逐 commit 归属，分批 delete + insert。 */
    public int attributeCommits(List<GitCommit> commits, boolean backfilled) {
        if (commits == null || commits.isEmpty()) {
            return 0;
        }
        List<AiTrailerRules.TrailerRule> rules = AiTrailerRules.merged(
                systemConfigService.getString(CONFIG_KEY_TRAILER_RULES, "[]"));

        Map<String, List<GitCommit>> byUser = new LinkedHashMap<>();
        for (GitCommit c : commits) {
            if (c == null || c.getUserCode() == null || c.getCommitTime() == null) {
                continue;
            }
            byUser.computeIfAbsent(c.getUserCode(), k -> new ArrayList<>()).add(c);
        }

        List<GitCommitAttribution> rows = new ArrayList<>(commits.size());
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, List<GitCommit>> en : byUser.entrySet()) {
            String user = en.getKey();
            List<GitCommit> userCommits = en.getValue();
            LocalDateTime minCt = userCommits.stream().map(GitCommit::getCommitTime).min(Comparator.naturalOrder()).orElseThrow();
            LocalDateTime maxCt = userCommits.stream().map(GitCommit::getCommitTime).max(Comparator.naturalOrder()).orElseThrow();
            LocalDateTime winStart = minCt.minusMinutes(WINDOW_MINUTES);
            LocalDateTime winEnd = maxCt.plusMinutes(WINDOW_MINUTES);

            List<AiSession> candidates = sessionRepository.findAttributionCandidates(user, winStart, winEnd);
            Map<Long, TreeSet<LocalDateTime>> signals = loadSignals(candidates, winStart, maxCt);

            for (GitCommit c : userCommits) {
                rows.add(buildRow(c, candidates, signals, rules, backfilled, now));
            }
        }

        for (int i = 0; i < rows.size(); i += TX_CHUNK) {
            List<GitCommitAttribution> chunk = rows.subList(i, Math.min(i + TX_CHUNK, rows.size()));
            self.replaceChunk(new ArrayList<>(chunk));
        }
        return rows.size();
    }

    /** 单批 delete + insert，独立事务（幂等重算锚点 = uk_commit）。 */
    @Transactional
    public void replaceChunk(List<GitCommitAttribution> chunk) {
        List<Long> commitIds = chunk.stream().map(GitCommitAttribution::getCommitId).toList();
        attributionRepository.deleteByCommitIdIn(commitIds);
        attributionRepository.saveAll(chunk);
    }

    private Map<Long, TreeSet<LocalDateTime>> loadSignals(
            List<AiSession> candidates, LocalDateTime from, LocalDateTime to) {
        Map<Long, TreeSet<LocalDateTime>> out = new HashMap<>();
        if (candidates.isEmpty()) {
            return out;
        }
        List<Long> ids = candidates.stream().map(AiSession::getId).toList();
        for (Object[] row : eventRepository.findEventTimesBySessionIdsInWindow(ids, from, to)) {
            if (row == null || row.length < 2 || !(row[0] instanceof Number sid) ) {
                continue;
            }
            LocalDateTime t = row[1] instanceof LocalDateTime ldt ? ldt
                    : row[1] instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null;
            if (t == null) {
                continue;
            }
            out.computeIfAbsent(sid.longValue(), k -> new TreeSet<>()).add(t);
        }
        return out;
    }

    private GitCommitAttribution buildRow(GitCommit c, List<AiSession> candidates,
                                          Map<Long, TreeSet<LocalDateTime>> signals,
                                          List<AiTrailerRules.TrailerRule> rules,
                                          boolean backfilled, LocalDateTime computedTime) {
        GitCommitAttribution row = new GitCommitAttribution();
        row.setCommitId(c.getId());
        row.setRepoUrl(c.getRepoUrl() == null ? "" : c.getRepoUrl());
        row.setUserCode(c.getUserCode());
        row.setCommitTime(c.getCommitTime());
        row.setLinesAdded(c.getLinesAdded() == null ? 0 : c.getLinesAdded());
        row.setLinesDeleted(c.getLinesDeleted() == null ? 0 : c.getLinesDeleted());
        row.setBackfilled(backfilled ? 1 : 0);
        row.setComputedTime(computedTime);

        LocalDateTime ct = c.getCommitTime();
        List<AiSession> inWindow = candidates.stream()
                .filter(s -> s.getStartedAt() != null && s.getLastActivity() != null)
                .filter(s -> !s.getStartedAt().isAfter(ct.plusMinutes(WINDOW_MINUTES))
                        && !s.getLastActivity().isBefore(ct.minusMinutes(WINDOW_MINUTES)))
                .toList();

        var trailer = AiTrailerRules.match(c.getMessageBody(), rules);
        if (trailer.isPresent()) {
            AiTrailerRules.TrailerRule rule = trailer.get();
            row.setTier(GitCommitAttribution.TIER_B);
            row.setTrailerKind(truncate(rule.kind(), 32));
            row.setTargetType(rule.targetType());
            // 窗口内找该工具的会话：优先 repo 归一相等者，退而求其次任意该工具会话
            String commitRepo = normalizeRepoUrl(c.getRepoUrl());
            List<AiSession> toolSessions = inWindow.stream()
                    .filter(s -> rule.targetType() == null
                            || rule.targetType().equalsIgnoreCase(s.getTargetType()))
                    .toList();
            List<AiSession> repoMatched = commitRepo == null ? List.of() : toolSessions.stream()
                    .filter(s -> commitRepo.equals(normalizeRepoUrl(s.getRepoUrl())))
                    .toList();
            AiSession chosen = chooseBySignal(repoMatched.isEmpty() ? toolSessions : repoMatched, ct, signals);
            if (chosen != null) {
                row.setSessionId(chosen.getId());
                row.setModel(chosen.getModel());
                row.setProjectName(truncate(chosen.getProjectName(), 128));
                row.setOverlapSeconds(overlapSeconds(chosen, ct));
                if (rule.targetType() == null) {
                    row.setTargetType(chosen.getTargetType());
                }
            } else {
                row.setModel(GitCommitAttribution.MODEL_UNKNOWN);
            }
            return row;
        }

        String commitRepo = normalizeRepoUrl(c.getRepoUrl());
        List<AiSession> repoMatched = commitRepo == null ? List.of() : inWindow.stream()
                .filter(s -> commitRepo.equals(normalizeRepoUrl(s.getRepoUrl())))
                .toList();
        AiSession chosen = chooseBySignal(repoMatched, ct, signals);
        if (chosen != null) {
            row.setTier(GitCommitAttribution.TIER_A);
            row.setSessionId(chosen.getId());
            row.setTargetType(chosen.getTargetType());
            row.setModel(chosen.getModel());
            row.setProjectName(truncate(chosen.getProjectName(), 128));
            row.setOverlapSeconds(overlapSeconds(chosen, ct));
        } else {
            row.setTier(GitCommitAttribution.TIER_NONE);
        }
        return row;
    }

    /**
     * 单一归属选择：最近活动信号（≤ commit_time）最晚者 → 并列取窗口重叠最长 → 再并列取 id 小者。
     * 信号缺失（null）排最后，但仍可凭重叠时长胜出「全员无信号」的并列局。
     */
    private AiSession chooseBySignal(List<AiSession> sessions, LocalDateTime ct,
                                     Map<Long, TreeSet<LocalDateTime>> signals) {
        AiSession best = null;
        LocalDateTime bestSignal = null;
        int bestOverlap = -1;
        for (AiSession s : sessions) {
            LocalDateTime signal = lastSignalAtOrBefore(s, ct, signals);
            int overlap = overlapSeconds(s, ct);
            boolean wins;
            if (best == null) {
                wins = true;
            } else {
                int bySignal = compareNullableTime(signal, bestSignal);
                if (bySignal != 0) {
                    wins = bySignal > 0;
                } else if (overlap != bestOverlap) {
                    wins = overlap > bestOverlap;
                } else {
                    wins = s.getId() != null && best.getId() != null && s.getId() < best.getId();
                }
            }
            if (wins) {
                best = s;
                bestSignal = signal;
                bestOverlap = overlap;
            }
        }
        return best;
    }

    /** 会话在 commit 前的最近活动信号；无事件的会话（hermes 类）用 min(last_activity, ct) 代理。 */
    private static LocalDateTime lastSignalAtOrBefore(AiSession s, LocalDateTime ct,
                                                      Map<Long, TreeSet<LocalDateTime>> signals) {
        TreeSet<LocalDateTime> times = signals.get(s.getId());
        if (times != null && !times.isEmpty()) {
            return times.floor(ct);
        }
        if (s.getStartedAt() == null || s.getStartedAt().isAfter(ct)) {
            return null;
        }
        LocalDateTime last = s.getLastActivity();
        return last == null || last.isAfter(ct) ? ct : last;
    }

    private static int compareNullableTime(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return a.compareTo(b);
    }

    /** 会话活动区间 [started_at, last_activity] 与 commit ±30min 窗口的重叠秒数。 */
    private static int overlapSeconds(AiSession s, LocalDateTime ct) {
        if (s.getStartedAt() == null || s.getLastActivity() == null) {
            return 0;
        }
        LocalDateTime start = max(s.getStartedAt(), ct.minusMinutes(WINDOW_MINUTES));
        LocalDateTime end = min(s.getLastActivity(), ct.plusMinutes(WINDOW_MINUTES));
        if (!end.isAfter(start)) {
            return 0;
        }
        return (int) Math.min(Duration.between(start, end).toSeconds(), Integer.MAX_VALUE);
    }

    /**
     * repo 轻归一，与 {@code aiPenetrationLines} 的 SQL 逐字对齐：
     * {@code LOWER(TRIM(TRAILING '/' FROM TRIM(TRAILING '.git' FROM url)))}
     * ——先剥尾部重复 '.git'，再剥尾部重复 '/'，lowercase；两步不回环。
     */
    static String normalizeRepoUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String s = url.trim();
        while (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isBlank() ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }
}
