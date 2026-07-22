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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * GitCommitAttributionEngine：§2.4 归属规则单测——B 档直接映射（有/无工具会话）、
 * A 档最近活动事件 + 并列取重叠最长、NONE 兜底、repo 归一 SQL 语义对齐。
 * gz
 */
class GitCommitAttributionEngineTest {

    private final GitCommitRepository gitCommitRepository = mock(GitCommitRepository.class);
    private final GitCommitAttributionRepository attributionRepository = mock(GitCommitAttributionRepository.class);
    private final AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
    private final AiSessionEventRepository eventRepository = mock(AiSessionEventRepository.class);
    private final SystemConfigService systemConfigService = mock(SystemConfigService.class);
    private final DynamicScheduledTaskManager scheduledTaskManager = mock(DynamicScheduledTaskManager.class);

    private GitCommitAttributionEngine engine;

    private final LocalDateTime ct = LocalDateTime.of(2026, 7, 21, 15, 0, 0);

    @BeforeEach
    void setUp() throws Exception {
        engine = new GitCommitAttributionEngine(
                gitCommitRepository, attributionRepository, sessionRepository,
                eventRepository, systemConfigService, scheduledTaskManager);
        Field self = GitCommitAttributionEngine.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(engine, engine);

        when(systemConfigService.getString(anyString(), anyString())).thenReturn("[]");
        when(sessionRepository.findAttributionCandidates(anyString(), any(), any())).thenReturn(List.of());
        when(eventRepository.findEventTimesBySessionIdsInWindow(any(), any(), any())).thenReturn(List.of());
    }

    // ---------- helpers ----------

    private GitCommit commit(long id, String user, String repo, String body) {
        GitCommit c = new GitCommit();
        c.setId(id);
        c.setUserCode(user);
        c.setRepoUrl(repo);
        c.setCommitTime(ct);
        c.setLinesAdded(10);
        c.setLinesDeleted(2);
        c.setMessageBody(body);
        c.setIsMerge(0);
        return c;
    }

    private AiSession session(long id, String target, String model, String repo,
                              LocalDateTime startedAt, LocalDateTime lastActivity) {
        AiSession s = new AiSession();
        s.setId(id);
        s.setTargetType(target);
        s.setModel(model);
        s.setRepoUrl(repo);
        s.setProjectName("proj-" + id);
        s.setStartedAt(startedAt);
        s.setLastActivity(lastActivity);
        return s;
    }

    private List<GitCommitAttribution> run(List<GitCommit> commits) {
        int n = engine.attributeCommits(commits, false);
        ArgumentCaptor<List<GitCommitAttribution>> captor = ArgumentCaptor.forClass(List.class);
        verify(attributionRepository, atLeastOnce()).saveAll(captor.capture());
        List<GitCommitAttribution> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        assertEquals(n, all.size());
        return all;
    }

    // ---------- B 档 ----------

    @Test
    void tierBWithToolSessionResolved() {
        AiSession claude = session(101, "claude", "claude-fable-5", "https://git.x/am.git",
                ct.minusHours(2), ct.plusMinutes(5));
        AiSession cursor = session(102, "cursor", "gpt-x", "https://git.x/am.git",
                ct.minusHours(2), ct.plusMinutes(5));
        when(sessionRepository.findAttributionCandidates(eq("u1"), any(), any()))
                .thenReturn(List.of(claude, cursor));

        GitCommitAttribution row = run(List.of(commit(1, "u1", "https://git.x/am",
                "feat: x\n\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"))).get(0);

        assertEquals(GitCommitAttribution.TIER_B, row.getTier());
        assertEquals("claude_coauthor", row.getTrailerKind());
        assertEquals("claude", row.getTargetType());
        assertEquals(101L, row.getSessionId());
        assertEquals("claude-fable-5", row.getModel());
        assertEquals("proj-101", row.getProjectName());
        assertTrue(row.getOverlapSeconds() > 0);
    }

    @Test
    void tierBWithoutToolSessionFallsBackToUnknown() {
        // 窗口内只有 cursor 会话，trailer 指向 claude → session 置空、model=unknown、target 仍取 trailer
        AiSession cursor = session(102, "cursor", "gpt-x", "https://git.x/am.git",
                ct.minusHours(1), ct.plusMinutes(5));
        when(sessionRepository.findAttributionCandidates(eq("u1"), any(), any()))
                .thenReturn(List.of(cursor));

        GitCommitAttribution row = run(List.of(commit(1, "u1", "https://git.x/am",
                "🤖 Generated with [Claude Code](https://claude.com/claude-code)"))).get(0);

        assertEquals(GitCommitAttribution.TIER_B, row.getTier());
        assertEquals("claude", row.getTargetType());
        assertNull(row.getSessionId());
        assertEquals(GitCommitAttribution.MODEL_UNKNOWN, row.getModel());
        assertNull(row.getProjectName());
    }

    // ---------- A 档 ----------

    @Test
    void tierANearestActivityEventWins() {
        // 两个 repo 匹配的候选：s1 最近事件 14:58，s2 最近事件 14:30 → s1 胜（尽管 s2 重叠更长）
        AiSession s1 = session(201, "claude", "m1", "https://git.x/am.git", ct.minusMinutes(40), ct.minusMinutes(1));
        AiSession s2 = session(202, "cursor", "m2", "https://git.x/am.git", ct.minusHours(5), ct.plusMinutes(20));
        when(sessionRepository.findAttributionCandidates(eq("u1"), any(), any()))
                .thenReturn(List.of(s1, s2));
        when(eventRepository.findEventTimesBySessionIdsInWindow(any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{201L, ct.minusMinutes(2)},
                new Object[]{202L, ct.minusMinutes(30)}));

        GitCommitAttribution row = run(List.of(commit(1, "u1", "https://git.x/am", "fix: 无 trailer"))).get(0);

        assertEquals(GitCommitAttribution.TIER_A, row.getTier());
        assertEquals(201L, row.getSessionId());
        assertEquals("claude", row.getTargetType());
        assertEquals("m1", row.getModel());
        assertNull(row.getTrailerKind());
    }

    @Test
    void tierATieBreaksByLongestOverlap() {
        // 两候选无任何事件（hermes 类）：代理信号同为各自 last_activity ≤ ct？
        // s1 last=ct、s2 last=ct → 代理信号相同（都取 min(last,ct)=ct... s1 last=ct-10m）
        // 构造并列：两会话都无事件、last_activity 都 > ct → 代理信号均为 ct → 并列 → 重叠长者胜
        AiSession shortOne = session(301, "claude", "m1", "https://git.x/am.git", ct.minusMinutes(10), ct.plusHours(1));
        AiSession longOne = session(302, "codex", "m2", "https://git.x/am.git", ct.minusHours(3), ct.plusHours(1));
        when(sessionRepository.findAttributionCandidates(eq("u1"), any(), any()))
                .thenReturn(List.of(shortOne, longOne));

        GitCommitAttribution row = run(List.of(commit(1, "u1", "https://git.x/am", "chore: 无 trailer"))).get(0);

        assertEquals(GitCommitAttribution.TIER_A, row.getTier());
        assertEquals(302L, row.getSessionId()); // 重叠 60min > 40min
        assertEquals(1800 + 1800, row.getOverlapSeconds()); // 窗口 ±30min 全覆盖
    }

    @Test
    void tierARequiresRepoMatch() {
        AiSession otherRepo = session(401, "claude", "m1", "https://git.x/other.git",
                ct.minusHours(1), ct.plusMinutes(10));
        when(sessionRepository.findAttributionCandidates(eq("u1"), any(), any()))
                .thenReturn(List.of(otherRepo));

        GitCommitAttribution row = run(List.of(commit(1, "u1", "https://git.x/am", "fix: 无 trailer"))).get(0);

        assertEquals(GitCommitAttribution.TIER_NONE, row.getTier());
        assertNull(row.getSessionId());
        assertNull(row.getTargetType());
    }

    @Test
    void sessionOutsideWindowNotAttributed() {
        // 会话活动窗口 [started-30m, last+30m] 不含 commit_time → NONE
        AiSession stale = session(501, "claude", "m1", "https://git.x/am.git",
                ct.minusHours(5), ct.minusMinutes(31).minusSeconds(1));
        when(sessionRepository.findAttributionCandidates(eq("u1"), any(), any()))
                .thenReturn(List.of(stale));

        GitCommitAttribution row = run(List.of(commit(1, "u1", "https://git.x/am", "fix: 无 trailer"))).get(0);

        assertEquals(GitCommitAttribution.TIER_NONE, row.getTier());
    }

    @Test
    void everyNonMergeCommitGetsExactlyOneRow() {
        List<GitCommitAttribution> rows = run(List.of(
                commit(1, "u1", "https://git.x/am", "fix: a"),
                commit(2, "u1", "https://git.x/am", "fix: b"),
                commit(3, "u2", "https://git.x/am", "fix: c")));
        assertEquals(3, rows.size());
        rows.forEach(r -> assertEquals(GitCommitAttribution.TIER_NONE, r.getTier()));
        assertEquals(0, rows.get(0).getBackfilled());
    }

    // ---------- repo 归一 ----------

    @Test
    void repoNormalizationMatchesSqlSemantics() {
        assertEquals("https://git.x/am", GitCommitAttributionEngine.normalizeRepoUrl("https://git.x/am.git"));
        assertEquals("https://git.x/am", GitCommitAttributionEngine.normalizeRepoUrl("HTTPS://GIT.X/AM.git"));
        assertEquals("https://git.x/am", GitCommitAttributionEngine.normalizeRepoUrl("https://git.x/am//"));
        // SQL 语义：先剥尾部重复 .git 再剥 /，不回环——"repo.git/" 剥完 / 后保留 .git
        assertEquals("https://git.x/am.git", GitCommitAttributionEngine.normalizeRepoUrl("https://git.x/am.git/"));
        assertEquals("a", GitCommitAttributionEngine.normalizeRepoUrl("a.git.git"));
        assertNull(GitCommitAttributionEngine.normalizeRepoUrl(null));
        assertNull(GitCommitAttributionEngine.normalizeRepoUrl("  "));
    }
}
