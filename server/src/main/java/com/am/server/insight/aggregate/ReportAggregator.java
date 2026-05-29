package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.git.GitCommit;
import com.am.server.domain.git.GitCommitRepository;
import com.am.server.domain.summary.DailySummary;
import com.am.server.domain.summary.DailySummaryRepository;
import com.am.server.insight.aggregate.PercentileCalculator.FivePercentiles;
import com.am.server.insight.aggregate.WatchlistEvaluator.TeamBaselines;
import com.am.server.insight.domain.AiSessionAudit;
import com.am.server.insight.domain.AnalysisReport;
import com.am.server.insight.domain.AnalysisReportUser;
import com.am.server.insight.domain.AnalysisReportUserRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;

/**
 * 报告聚合器：把 ai_session + ai_session_audit + git_commit 三个源
 * 按用户聚合成 {@link AnalysisReportUser} 行，并算出团队基线。
 *
 * <p>聚合口径见 docs/design/employee-insight-from-ai-sessions-v1.0.md §2.2：
 * <ul>
 *   <li>输入会话列表由 {@link com.am.server.domain.ai.AiSessionRepository#findOverlappingByStartedOrLastActivityAndTargetTypeIn}
 *       拉取，已排除 {@code invalid_reason} 非空的无效会话</li>
 *   <li>会话计数 / token 走 ai_session 累积字段</li>
 *   <li>协作时长（人维度小时数、团队总时长）与员工数据一致：窗口内按自然日累加
 *       {@code daily_summary.ai_active_seconds_union}（{@link com.am.server.aggregator.DailySummaryAggregator} 信号流口径）</li>
 *   <li>难度、能力、模式、完成率走 ai_session_audit（双 judge 合成结果）</li>
 *   <li>commit / lines / revert / 高难度 commit ratio 走 git_commit；提交条数与新增行数按窗口内已入库记录全量统计，不筛 {@code ai_assisted}</li>
 *   <li>用户<strong>主动斜杠</strong>：{@code ai_session_message.slash_* } 在 ingest 入库时按全文写入；
 *       报告阶段只汇总落库字段</li>
 *   <li>团队 P10/P25/P50/P75/P90 由本批活跃用户的指标分布算出</li>
 * </ul>
 * gz
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportAggregator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 数据量不足阈值：会话 < 10 视为 insufficient_data，仍出基本量但不出能力评判。 */
    private static final int INSUFFICIENT_SESSION_THRESHOLD = 10;

    /** 难度权重：见 docs/design/employee-insight-from-ai-sessions-v1.0.md §2.2 B。突出高难度任务的价值。 */
    private static final Map<Integer, Integer> DIFFICULTY_WEIGHTS = Map.of(
            1, 1, 2, 2, 3, 4, 4, 8, 5, 16);

    /** disagree 样本的聚合权重折扣，仍参与但弱化。 */
    private static final double DISAGREE_WEIGHT = 0.3;

    /** 用户斜杠调用饼图单项上限，其余合并为「其他」；避免 JSON 过大。 */
    private static final int SLASH_DIST_TOP_N = 22;

    /** 用户 Top 模型/项目条数上限 */
    private static final int TOP_USAGE_N = 5;

    private final DailySummaryRepository dailySummaryRepository;
    private final GitCommitRepository commitRepository;
    private final AiSessionMessageRepository messageRepository;
    private final AiSessionEventRepository eventRepository;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final HighlightSessionPicker highlightPicker;
    private final WatchlistEvaluator watchlistEvaluator;
    private final AnalysisReportUserRepository userRepository;

    /**
     * 把一个窗口的所有数据聚合成报告产物，写入 analysis_report_user 并补齐 analysis_report 团队字段。
     *
     * @param report  目标 report 行（status 仍是 running，本方法不动）
     * @param sessions 窗口内所有 ai_session
     * @param auditsBySession  sessionId → 已审计行
     */
    @Transactional
    public AggregateOutcome aggregate(AnalysisReport report,
                                      List<AiSession> sessions,
                                      Map<Long, AiSessionAudit> auditsBySession) {

        // 1. 按 user 分桶
        Map<String, List<AiSession>> sessionByUser = new LinkedHashMap<>();
        for (AiSession s : sessions) {
            sessionByUser.computeIfAbsent(s.getUserCode(), k -> new ArrayList<>()).add(s);
        }

        Map<String, Long> unionSecondsByUser =
                loadUnionSecondsByUser(report.getWindowFrom(), report.getWindowTo(), sessionByUser.keySet());

        // 2. 拉窗口内所有 git_commit（仅与本批 user 相关）
        LocalDateTime t0 = report.getWindowFrom().atStartOfDay();
        LocalDateTime t1 = report.getWindowTo().plusDays(1).atStartOfDay();
        Map<String, List<GitCommit>> commitByUser = new HashMap<>();
        Map<String, List<GitCommit>> revertSubjectsByUser = new HashMap<>();
        List<String> reportUsers = new ArrayList<>(sessionByUser.keySet());
        for (String user : reportUsers) {
            commitByUser.put(user, new ArrayList<>());
            revertSubjectsByUser.put(user, new ArrayList<>());
        }
        if (!reportUsers.isEmpty()) {
            LocalDateTime revertEnd = t1.plusDays(7);
            for (GitCommit c : commitRepository.findByUserCodeInAndCommitTimeBetween(reportUsers, t0, revertEnd)) {
                String user = c.getUserCode();
                if (user == null || c.getCommitTime() == null) {
                    continue;
                }
                if (!c.getCommitTime().isBefore(t0) && c.getCommitTime().isBefore(t1)) {
                    commitByUser.computeIfAbsent(user, k -> new ArrayList<>()).add(c);
                }
                if (RevertSubjectSignals.matches(c.getMessageSubject())) {
                    revertSubjectsByUser.computeIfAbsent(user, k -> new ArrayList<>()).add(c);
                }
            }
        }

        // 3. 算每个用户的 UserMetrics
        Map<String, UserMetrics> metricsByUser = new LinkedHashMap<>();
        for (Map.Entry<String, List<AiSession>> e : sessionByUser.entrySet()) {
            String user = e.getKey();
            List<AiSession> userSessions = e.getValue();
            UserMetrics m = computeUserMetrics(user, userSessions, auditsBySession,
                    unionSecondsByUser.getOrDefault(user, 0L),
                    commitByUser.getOrDefault(user, List.of()),
                    revertSubjectsByUser.getOrDefault(user, List.of()));
            metricsByUser.put(user, m);
        }

        String teamSlashJson = attachUserSlashBreakdown(sessions, metricsByUser);

        LocalDateTime windowT0 = report.getWindowFrom().atStartOfDay();
        LocalDateTime windowT1 = report.getWindowTo().plusDays(1).atStartOfDay();
        Collection<String> activeTypes = activeTargetTypesProvider.getActiveTypes();
        attachTopUsage(windowT0, windowT1, activeTypes, sessionByUser, metricsByUser);

        // 4. 算团队基线（P10/P25/P50/P75/P90），仅基于"有充分数据"的用户
        List<Double> sessionCnt = new ArrayList<>();
        List<Double> activeHours = new ArrayList<>();
        List<Double> commitsPerHour = new ArrayList<>();
        List<Double> compositeScores = new ArrayList<>();
        List<Double> capProblem = new ArrayList<>();
        List<Double> capContext = new ArrayList<>();
        List<Double> capDebug = new ArrayList<>();
        List<Double> capTool = new ArrayList<>();
        List<Double> capSelf = new ArrayList<>();
        for (UserMetrics m : metricsByUser.values()) {
            if (m.isInsufficientData()) continue;
            sessionCnt.add((double) m.getSessionCount());
            activeHours.add(m.getAiActiveHours());
            if (m.getAiCommitsPerActiveHour() != null) commitsPerHour.add(m.getAiCommitsPerActiveHour());
            if (m.getCapProblemDecomposition() != null) capProblem.add(m.getCapProblemDecomposition());
            if (m.getCapContextManagement() != null) capContext.add(m.getCapContextManagement());
            if (m.getCapDebuggingSkill() != null) capDebug.add(m.getCapDebuggingSkill());
            if (m.getCapToolOrchestration() != null) capTool.add(m.getCapToolOrchestration());
            if (m.getCapSelfCorrection() != null) capSelf.add(m.getCapSelfCorrection());
        }

        TeamBaselines baselines = new TeamBaselines(
                PercentileCalculator.five(sessionCnt),
                PercentileCalculator.five(activeHours),
                PercentileCalculator.five(commitsPerHour));

        Map<String, FivePercentiles> teamCapabilityPercentiles = Map.of(
                "problem_decomposition", PercentileCalculator.five(capProblem),
                "context_management", PercentileCalculator.five(capContext),
                "debugging_skill", PercentileCalculator.five(capDebug),
                "tool_orchestration", PercentileCalculator.five(capTool),
                "self_correction", PercentileCalculator.five(capSelf));

        // 5. 算 composite_score + composite_percentile
        for (UserMetrics m : metricsByUser.values()) {
            if (m.isInsufficientData()) {
                m.setCompositeScore(null);
                m.setCompositePercentile(null);
                continue;
            }
            double capAvg = avgNonNull(
                    m.getCapProblemDecomposition(), m.getCapContextManagement(),
                    m.getCapDebuggingSkill(), m.getCapToolOrchestration(),
                    m.getCapSelfCorrection());
            double prodPercentile = PercentileCalculator.rankPercentile(
                    commitsPerHour, safe(m.getAiCommitsPerActiveHour()));
            double prodIndex = Double.isNaN(prodPercentile) ? 0.0 : prodPercentile / 100.0;

            double qualityIndex = 0.5;
            if (m.getAiCommitCount() >= 3 && m.getCommitRevertRate() != null) {
                qualityIndex = Math.max(0.0, 1.0 - safe(m.getCommitRevertRate()));
            }

            double score = (0.35 * (capAvg / 5.0)
                    + 0.35 * prodIndex
                    + 0.20 * safe(m.getHighDifficultyRatio())
                    + 0.10 * qualityIndex) * 100.0;
            m.setCompositeScore(score);
            compositeScores.add(score);
        }
        // 算 composite_percentile（每个有效 user 在 compositeScores 内的排名）
        for (UserMetrics m : metricsByUser.values()) {
            if (m.isInsufficientData() || m.getCompositeScore() == null) continue;
            double p = PercentileCalculator.rankPercentile(compositeScores, m.getCompositeScore());
            m.setCompositePercentile(p);
        }

        // 6. 落盘：清旧、批量插入 AnalysisReportUser
        userRepository.deleteByReportId(report.getId());
        Map<String, List<String>> watchlistSummary = new LinkedHashMap<>();
        List<AnalysisReportUser> reportUserRows = new ArrayList<>(metricsByUser.size());
        for (Map.Entry<String, UserMetrics> e : metricsByUser.entrySet()) {
            UserMetrics m = e.getValue();
            // watchlist 评估（insufficient_data 已被 evaluator 内部短路）
            List<String> flags = watchlistEvaluator.evaluate(m, baselines);
            for (String flag : flags) {
                watchlistSummary.computeIfAbsent(flag, k -> new ArrayList<>()).add(m.getUserCode());
            }
            // highlight session
            List<AiSessionAudit> userAudits = sessionByUser.get(m.getUserCode()).stream()
                    .map(s -> auditsBySession.get(s.getId()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            List<Long> highlightIds = highlightPicker.pick(userAudits);
            List<AiSession> userSessions = sessionByUser.get(m.getUserCode());
            List<GitCommit> userCommits = commitByUser.getOrDefault(m.getUserCode(), List.of());
            String highlightJson = buildHighlightSessionsJson(
                    highlightIds, userSessions, userAudits, userCommits);

            reportUserRows.add(toEntity(report.getId(), m, flags, highlightIds, highlightJson));
        }
        if (!reportUserRows.isEmpty()) {
            userRepository.saveAll(reportUserRows);
        }

        // 7. 团队级 payload 一并算出（团队 difficulty / mode / completion 分布）
        TeamPayload teamPayload = buildTeamPayload(sessions, auditsBySession,
                baselines, watchlistSummary, commitByUser, unionSecondsByUser, teamSlashJson,
                teamCapabilityPercentiles);

        return new AggregateOutcome(metricsByUser, teamPayload);
    }

    /**
     * 汇总用户斜杠使用情况：读 {@code ai_session_message} 入库时写入的 {@code slash_*_count} 与
     * {@code slash_hits_json}（全文多行扫描结果，见 ingest）。
     */
    private void attachTopUsage(LocalDateTime t0,
                                LocalDateTime t1,
                                Collection<String> activeTypes,
                                Map<String, List<AiSession>> sessionByUser,
                                Map<String, UserMetrics> metricsByUser) {
        for (Map.Entry<String, UserMetrics> e : metricsByUser.entrySet()) {
            String user = e.getKey();
            UserMetrics m = e.getValue();
            List<Map<String, Object>> models = new ArrayList<>();
            List<Map<String, Object>> projects = new ArrayList<>();
            if (activeTypes != null && !activeTypes.isEmpty()) {
                for (Object[] r : eventRepository.aggregateModelsForUserInWindowAndTargetTypeIn(
                        user, t0, t1, activeTypes)) {
                    String name = r[0] == null ? null : r[0].toString();
                    if (name == null || name.isBlank()) continue;
                    models.add(nameValue(name, toLong(r[1])));
                    if (models.size() >= TOP_USAGE_N) break;
                }
                for (Object[] r : eventRepository.aggregateProjectsForUserInWindowAndTargetTypeIn(
                        user, t0, t1, activeTypes)) {
                    String name = r[0] == null ? null : r[0].toString();
                    if (name == null || name.isBlank()) continue;
                    projects.add(nameValue(name, toLong(r[1])));
                    if (projects.size() >= TOP_USAGE_N) break;
                }
            }
            m.setTopModelsJson(writeJson(models));
            m.setTopProjectsJson(writeJson(projects));
            m.setAgentDistJson(buildAgentDistJson(sessionByUser.getOrDefault(user, List.of())));
        }
    }

    private String buildAgentDistJson(List<AiSession> sessions) {
        Map<String, Long> byAgent = new LinkedHashMap<>();
        for (AiSession s : sessions) {
            String t = s.getTargetType();
            if (t == null) continue;
            byAgent.merge(t, 1L, Long::sum);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        byAgent.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> items.add(nameValue(e.getKey(), e.getValue())));
        return writeJson(items);
    }

    private String buildHighlightSessionsJson(List<Long> ids,
                                              List<AiSession> userSessions,
                                              List<AiSessionAudit> userAudits,
                                              List<GitCommit> commits) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        Map<Long, AiSessionAudit> auditBySessionId = new HashMap<>();
        for (AiSessionAudit a : userAudits) {
            auditBySessionId.put(a.getAiSessionId(), a);
        }
        Map<Long, AiSession> sessionById = new HashMap<>();
        for (AiSession s : userSessions) {
            sessionById.put(s.getId(), s);
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Long id : ids) {
            AiSessionAudit a = auditBySessionId.get(id);
            AiSession s = sessionById.get(id);
            if (a == null) continue;
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("session_id", id);
            card.put("difficulty", a.getDifficulty());
            card.put("mode", a.getMode());
            card.put("reason", summarizeJudgeReason(a.getJudgeReasonText()));
            card.put("nearby_commit", s != null && hasNearbyCommit(s, commits));
            cards.add(card);
        }
        return writeJson(cards);
    }

    private static boolean hasNearbyCommit(AiSession session, List<GitCommit> commits) {
        if (commits.isEmpty() || session.getLastActivity() == null) {
            return false;
        }
        LocalDateTime anchor = session.getLastActivity();
        LocalDateTime from = anchor.minusMinutes(30);
        LocalDateTime to = anchor.plusMinutes(30);
        String repo = session.getRepoUrl();
        for (GitCommit c : commits) {
            if (c.getCommitTime() == null) continue;
            if (repo != null && c.getRepoUrl() != null && !repo.equals(c.getRepoUrl())) continue;
            if (!c.getCommitTime().isBefore(from) && !c.getCommitTime().isAfter(to)) {
                return true;
            }
        }
        return false;
    }

    private static String summarizeJudgeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String oneLine = raw.replace('\n', ' ').trim();
        oneLine = oneLine.replaceAll("\\[A:[^\\]]+\\]\\s*", "").replaceAll("\\[B:[^\\]]+\\]\\s*", "");
        if (oneLine.length() > 80) {
            return oneLine.substring(0, 80) + "…";
        }
        return oneLine;
    }

    private static Map<String, Object> nameValue(String name, long value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("value", value);
        return row;
    }

    private String attachUserSlashBreakdown(List<AiSession> sessions, Map<String, UserMetrics> metricsByUser) {
        List<Long> sessionIds = sessions.stream()
                .map(AiSession::getId)
                .filter(Objects::nonNull)
                .toList();
        if (sessionIds.isEmpty()) {
            for (UserMetrics m : metricsByUser.values()) {
                m.setToolCommandCount(0);
                m.setToolSkillCount(0);
                m.setToolBreakdownJson("[]");
            }
            return emptyTeamSlashBreakdownJson();
        }

        Map<String, Long> cmdSumByUser = new HashMap<>();
        Map<String, Long> skSumByUser = new HashMap<>();
        Map<String, Map<String, Long>> slashByUser = new HashMap<>();
        Map<String, Long> teamSlash = new HashMap<>();

        final int batch = 500;
        for (int i = 0; i < sessionIds.size(); i += batch) {
            List<Long> slice = sessionIds.subList(i, Math.min(i + batch, sessionIds.size()));
            for (Object[] row : messageRepository.sumStoredSlashCountsByUserForSessions(slice)) {
                String user = (String) row[0];
                cmdSumByUser.merge(user, toLong(row[1]), Long::sum);
                skSumByUser.merge(user, toLong(row[2]), Long::sum);
            }
            for (Object[] row : messageRepository.loadStoredSlashHitsJsonForSessions(slice)) {
                String user = (String) row[0];
                String json = stringifyDbValue(row[1]);
                mergeSlashHitsJson(user, json, slashByUser, teamSlash);
            }
        }

        long teamCmd = cmdSumByUser.values().stream().mapToLong(Long::longValue).sum();
        long teamSk = skSumByUser.values().stream().mapToLong(Long::longValue).sum();

        for (Map.Entry<String, UserMetrics> e : metricsByUser.entrySet()) {
            String u = e.getKey();
            UserMetrics m = e.getValue();
            m.setToolCommandCount((int) Math.min(cmdSumByUser.getOrDefault(u, 0L), Integer.MAX_VALUE));
            m.setToolSkillCount((int) Math.min(skSumByUser.getOrDefault(u, 0L), Integer.MAX_VALUE));
            Map<String, Long> toks = slashByUser.getOrDefault(u, Map.of());
            m.setToolBreakdownJson(toks.isEmpty() ? "[]" : writeJson(buildCappedSlashItems(toks, SLASH_DIST_TOP_N)));
        }

        if (teamCmd == 0 && teamSk == 0 && teamSlash.isEmpty()) {
            return emptyTeamSlashBreakdownJson();
        }
        return buildTeamSlashBreakdownJson(teamSlash, teamCmd, teamSk);
    }

    private void mergeSlashHitsJson(String userCode, String json,
                                    Map<String, Map<String, Long>> slashByUser,
                                    Map<String, Long> teamSlash) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) {
                return;
            }
            for (JsonNode n : arr) {
                String kind = n.path("kind").asText("");
                if ("noise".equals(kind)) {
                    continue;
                }
                String token = n.path("token").asText("");
                if (token.isEmpty()) {
                    continue;
                }
                teamSlash.merge(token, 1L, Long::sum);
                slashByUser.computeIfAbsent(userCode, k -> new HashMap<>()).merge(token, 1L, Long::sum);
            }
        } catch (JsonProcessingException e) {
            log.warn("slash_hits_json parse failed user={}: {}", userCode, e.getMessage());
        }
    }

    private static String stringifyDbValue(Object cell) {
        if (cell == null) {
            return null;
        }
        return cell.toString();
    }

    private String buildTeamSlashBreakdownJson(Map<String, Long> teamTokens, long commandTotal, long skillTotal) {
        List<Map<String, Object>> items =
                teamTokens.isEmpty() ? List.of() : buildCappedSlashItems(teamTokens, SLASH_DIST_TOP_N);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        payload.put("command_total", commandTotal);
        payload.put("skill_total", skillTotal);
        return writeJson(payload);
    }

    private String emptyTeamSlashBreakdownJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", List.of());
        payload.put("command_total", 0);
        payload.put("skill_total", 0);
        return writeJson(payload);
    }

    private static List<Map<String, Object>> buildCappedSlashItems(Map<String, Long> slashCounts, int topN) {
        List<Map.Entry<String, Long>> sorted = slashCounts.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed())
                .toList();
        List<Map<String, Object>> out = new ArrayList<>();
        long restCommand = 0;
        long restSkill = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Long> e = sorted.get(i);
            if (i < topN) {
                out.add(slashItem(e.getKey(), e.getValue()));
            } else {
                if (UserSlashInvocationKind.isSkillSlashKey(e.getKey())) {
                    restSkill += e.getValue();
                } else {
                    restCommand += e.getValue();
                }
            }
        }
        if (restCommand > 0) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "其他（斜杠命令）");
            row.put("count", restCommand);
            row.put("kind", "command");
            out.add(row);
        }
        if (restSkill > 0) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "其他（斜杠技能）");
            row.put("count", restSkill);
            row.put("kind", "skill");
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> slashItem(String name, long count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("count", count);
        m.put("kind", UserSlashInvocationKind.kindOfSlashKey(name));
        return m;
    }

    private static long toLong(Object o) {
        if (o instanceof Long l) {
            return l;
        }
        if (o instanceof Integer intv) {
            return intv.longValue();
        }
        if (o instanceof java.math.BigInteger bi) {
            return bi.longValue();
        }
        if (o instanceof BigDecimal bd) {
            return bd.longValue();
        }
        return 0L;
    }

    // ------------------------------------------------------------------

    /**
     * 与 {@link com.am.server.web.PeopleController} 一致：窗口内每个自然日的
     * {@code ai_active_seconds_union} 求和（员工数据按日 timeline / summary 同源）。
     */
    private Map<String, Long> loadUnionSecondsByUser(LocalDate from, LocalDate to, Set<String> userCodes) {
        Map<String, Long> out = new HashMap<>();
        for (String u : userCodes) {
            out.put(u, 0L);
        }
        if (userCodes.isEmpty() || from == null || to == null || from.isAfter(to)) {
            return out;
        }
        if (userCodes.isEmpty()) {
            return out;
        }
        for (Object[] row : dailySummaryRepository.sumUnionSecondsGroupedByUserInWorkDateRange(
                from, to, userCodes)) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            out.put(row[0].toString(), toLong(row[1]));
        }
        return out;
    }

    private UserMetrics computeUserMetrics(String userCode,
                                           List<AiSession> sessions,
                                           Map<Long, AiSessionAudit> auditsBySession,
                                           long unionActiveSeconds,
                                           List<GitCommit> userCommits,
                                           List<GitCommit> revertCandidates) {
        UserMetrics m = new UserMetrics();
        m.setUserCode(userCode);
        m.setSessionCount(sessions.size());

        long inputT = 0, outputT = 0;
        for (AiSession s : sessions) {
            inputT += nz(s.getInputTokens());
            outputT += nz(s.getOutputTokens());
        }
        m.setTotalTokens(inputT + outputT);
        m.setAiActiveHours(unionActiveSeconds / 3600.0);

        // 难度分布 / mode 占比 / 能力雷达 / 完成率：从 audit 取
        Map<Integer, Integer> diffDist = new LinkedHashMap<>();
        for (int d = 1; d <= 5; d++) diffDist.put(d, 0);
        Map<String, Integer> modeCount = new LinkedHashMap<>();
        double diffSum = 0.0;
        int auditedCount = 0;
        // 5 维能力难度加权累加
        double[] capWeighted = new double[5];
        double weightSum = 0.0;
        // 完成率难度加权（completed = 1, partial = 0.5, abandoned = 0）
        double outcomeWeighted = 0.0;
        double outcomeWeightSum = 0.0;
        int abandonedCount = 0;

        for (AiSession s : sessions) {
            AiSessionAudit a = auditsBySession.get(s.getId());
            if (a == null) continue;
            auditedCount++;
            int d = a.getDifficulty();
            diffDist.merge(d, 1, Integer::sum);
            modeCount.merge(a.getMode(), 1, Integer::sum);
            diffSum += d;

            double disagreeFactor = (a.getJudgeDisagreement() != null && a.getJudgeDisagreement() == 1)
                    ? DISAGREE_WEIGHT : 1.0;
            double w = DIFFICULTY_WEIGHTS.getOrDefault(d, 1) * disagreeFactor;

            capWeighted[0] += a.getCapProblemDecomposition() * w;
            capWeighted[1] += a.getCapContextManagement() * w;
            capWeighted[2] += a.getCapDebuggingSkill() * w;
            capWeighted[3] += a.getCapToolOrchestration() * w;
            capWeighted[4] += a.getCapSelfCorrection() * w;
            weightSum += w;

            double outcomeScore = switch (a.getOutcome()) {
                case "completed" -> 1.0;
                case "partial" -> 0.5;
                default -> 0.0;
            };
            outcomeWeighted += outcomeScore * w;
            outcomeWeightSum += w;
            if ("abandoned".equals(a.getOutcome())) abandonedCount++;
        }

        m.setDifficultyDist(diffDist);

        if (auditedCount < INSUFFICIENT_SESSION_THRESHOLD) {
            m.setInsufficientData(true);
            // 基本量已经填好，能力维度全部为 null
        } else {
            m.setAvgDifficulty(round2(diffSum / auditedCount));
            int highCount = diffDist.getOrDefault(4, 0) + diffDist.getOrDefault(5, 0);
            m.setHighDifficultyRatio(round4(highCount * 1.0 / auditedCount));
            m.setCompletionRate(round4(outcomeWeightSum == 0 ? 0 : outcomeWeighted / outcomeWeightSum));
            m.setAbandonedRate(round4(abandonedCount * 1.0 / auditedCount));
            m.setCapProblemDecomposition(round2(capWeighted[0] / weightSum));
            m.setCapContextManagement(round2(capWeighted[1] / weightSum));
            m.setCapDebuggingSkill(round2(capWeighted[2] / weightSum));
            m.setCapToolOrchestration(round2(capWeighted[3] / weightSum));
            m.setCapSelfCorrection(round2(capWeighted[4] / weightSum));

            Map<String, Double> modeDist = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : modeCount.entrySet()) {
                modeDist.put(e.getKey(), round4(e.getValue() * 1.0 / auditedCount));
            }
            m.setModeDist(modeDist);
        }

        // git_commit 产出
        long linesAdded = 0;
        int aiCommit = 0;
        int highDiffCommitCount = 0;       // 难度≥4 会话在提交时刻 ±30min 窗口内与 commit 时间重叠的条数
        int aiCommitWithFollowupRevert = 0; // 简化 revert: 该 commit 后 7 天内有 revert 关键词的 commit
        Set<Long> highDifficultySessionIds = new HashSet<>();
        for (AiSession s : sessions) {
            AiSessionAudit a = auditsBySession.get(s.getId());
            if (a != null && a.getDifficulty() >= 4) {
                highDifficultySessionIds.add(s.getId());
            }
        }

        for (GitCommit c : userCommits) {
            aiCommit++;
            linesAdded += nz(c.getLinesAdded());
            if (commitOverlapsHighDifficultySession(c, highDifficultySessionIds, sessions)) {
                highDiffCommitCount++;
            }
            if (hasRevertAfter(c, revertCandidates)) {
                aiCommitWithFollowupRevert++;
            }
        }
        m.setAiCommitCount(aiCommit);
        m.setAiLinesAdded(linesAdded);

        if (aiCommit > 0 && m.getAiActiveHours() > 0) {
            m.setAiCommitsPerActiveHour(round4(aiCommit / m.getAiActiveHours()));
        }
        if (aiCommit > 0 && m.getTotalTokens() > 0) {
            m.setAiLinesPer1kToken(round4(linesAdded * 1000.0 / m.getTotalTokens()));
        }
        if (aiCommit > 0) {
            m.setCommitRevertRate(round4(aiCommitWithFollowupRevert * 1.0 / aiCommit));
            m.setHighDifficultyCommitRatio(round4(highDiffCommitCount * 1.0 / aiCommit));
        }

        return m;
    }

    private static boolean commitOverlapsHighDifficultySession(
            GitCommit commit, Set<Long> highDifficultySessionIds, List<AiSession> sessions) {
        if (highDifficultySessionIds.isEmpty() || commit.getCommitTime() == null) {
            return false;
        }
        LocalDateTime from = commit.getCommitTime().minusMinutes(30);
        LocalDateTime to = commit.getCommitTime().plusMinutes(5);
        String repo = commit.getRepoUrl();
        for (AiSession s : sessions) {
            if (!highDifficultySessionIds.contains(s.getId())) {
                continue;
            }
            if (repo != null && s.getRepoUrl() != null && !repo.equals(s.getRepoUrl())) {
                continue;
            }
            if (!s.getStartedAt().isAfter(to) && !s.getLastActivity().isBefore(from)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRevertAfter(GitCommit base, List<GitCommit> revertCandidates) {
        if (revertCandidates.isEmpty()) return false;
        LocalDateTime baseT = base.getCommitTime();
        LocalDateTime cutoff = baseT.plusDays(7);
        String baseRepo = base.getRepoUrl();
        String baseHash = base.getCommitHash();
        for (GitCommit c : revertCandidates) {
            if (!Objects.equals(c.getRepoUrl(), baseRepo)) {
                continue;
            }
            if (baseHash != null && baseHash.equals(c.getCommitHash())) {
                continue;
            }
            if (c.getCommitTime().isAfter(baseT) && !c.getCommitTime().isAfter(cutoff)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------

    private TeamPayload buildTeamPayload(List<AiSession> sessions,
                                         Map<Long, AiSessionAudit> auditsBySession,
                                         TeamBaselines baselines,
                                         Map<String, List<String>> watchlistSummary,
                                         Map<String, List<GitCommit>> commitByUser,
                                         Map<String, Long> unionSecondsByUser,
                                         String teamToolBreakdownJson,
                                         Map<String, FivePercentiles> teamCapabilityPercentiles) {

        Set<String> activeUsers = new HashSet<>();
        Map<Integer, Integer> teamDiff = new LinkedHashMap<>();
        for (int d = 1; d <= 5; d++) teamDiff.put(d, 0);
        Map<String, Integer> teamMode = new LinkedHashMap<>();

        long activeSecondsTotal = 0L;
        for (long sec : unionSecondsByUser.values()) {
            activeSecondsTotal += sec;
        }
        for (AiSession s : sessions) {
            activeUsers.add(s.getUserCode());
            AiSessionAudit a = auditsBySession.get(s.getId());
            if (a == null) continue;
            teamDiff.merge(a.getDifficulty(), 1, Integer::sum);
            teamMode.merge(a.getMode(), 1, Integer::sum);
        }

        int totalAiCommit = 0;
        for (List<GitCommit> commits : commitByUser.values()) {
            totalAiCommit += commits.size();
        }

        TeamPayload p = new TeamPayload();
        p.activeUserCount = activeUsers.size();
        p.totalSessionCount = sessions.size();
        p.totalActiveHours = BigDecimal.valueOf(activeSecondsTotal / 3600.0)
                .setScale(2, RoundingMode.HALF_UP);
        p.totalAiCommit = totalAiCommit;
        p.teamDifficultyDistJson = writeJson(teamDiff);
        p.teamModeDistJson = writeJson(teamMode);
        p.teamPercentilesJson = writeJson(Map.of(
                "session_count", asMap(baselines.sessionCount()),
                "ai_active_hours", asMap(baselines.activeHours()),
                "ai_commits_per_active_hour", asMap(baselines.commitsPerHour())));
        p.teamCapabilityPercentilesJson = writeJson(capabilityPercentilesMap(teamCapabilityPercentiles));
        p.watchlistSummaryJson = writeJson(watchlistSummary);
        p.teamToolBreakdownJson = teamToolBreakdownJson;
        return p;
    }

    private Map<String, Double> asMap(FivePercentiles p) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("p10", nan(p.p10()));
        m.put("p25", nan(p.p25()));
        m.put("p50", nan(p.p50()));
        m.put("p75", nan(p.p75()));
        m.put("p90", nan(p.p90()));
        return m;
    }

    private static Double nan(double v) {
        return Double.isNaN(v) ? null : Math.round(v * 1000.0) / 1000.0;
    }

    private static String writeJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.warn("write json failed: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Map<String, Double>> capabilityPercentilesMap(
            Map<String, FivePercentiles> caps) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (Map.Entry<String, FivePercentiles> e : caps.entrySet()) {
            out.put(e.getKey(), asMap(e.getValue()));
        }
        return out;
    }

    private AnalysisReportUser toEntity(Long reportId, UserMetrics m,
                                        List<String> flags, List<Long> highlightIds,
                                        String highlightSessionsJson) {
        AnalysisReportUser row = new AnalysisReportUser();
        row.setReportId(reportId);
        row.setUserCode(m.getUserCode());
        row.setSessionCount(m.getSessionCount());
        row.setAiActiveHours(BigDecimal.valueOf(m.getAiActiveHours()).setScale(2, RoundingMode.HALF_UP));
        row.setTotalTokens(m.getTotalTokens());
        row.setAiCommitCount(m.getAiCommitCount());
        row.setAiLinesAdded(m.getAiLinesAdded());
        row.setDifficultyDistJson(writeJson(m.getDifficultyDist()));
        row.setAvgDifficulty(scale2(m.getAvgDifficulty()));
        row.setHighDifficultyRatio(scale4(m.getHighDifficultyRatio()));
        row.setCompletionRate(scale4(m.getCompletionRate()));
        row.setAbandonedRate(scale4(m.getAbandonedRate()));
        row.setCapProblemDecomposition(scale2(m.getCapProblemDecomposition()));
        row.setCapContextManagement(scale2(m.getCapContextManagement()));
        row.setCapDebuggingSkill(scale2(m.getCapDebuggingSkill()));
        row.setCapToolOrchestration(scale2(m.getCapToolOrchestration()));
        row.setCapSelfCorrection(scale2(m.getCapSelfCorrection()));
        row.setModeDistJson(m.getModeDist() == null ? null : writeJson(m.getModeDist()));
        row.setAiCommitsPerActiveHour(scale4(m.getAiCommitsPerActiveHour()));
        row.setAiLinesPer1kToken(scale4(m.getAiLinesPer1kToken()));
        row.setCommitRevertRate(scale4(m.getCommitRevertRate()));
        row.setHighDifficultyCommitRatio(scale4(m.getHighDifficultyCommitRatio()));
        row.setCompositeScore(m.getCompositeScore() == null ? null :
                BigDecimal.valueOf(m.getCompositeScore()).setScale(2, RoundingMode.HALF_UP));
        row.setCompositePercentile(m.getCompositePercentile() == null ? null :
                BigDecimal.valueOf(m.getCompositePercentile()).setScale(2, RoundingMode.HALF_UP));
        row.setWatchlistFlagsJson(writeJson(flags));
        row.setHighlightSessionIdsJson(writeJson(highlightIds));
        row.setHighlightSessionsJson(highlightSessionsJson != null ? highlightSessionsJson : "[]");
        row.setInsufficientData(m.isInsufficientData() ? 1 : 0);
        row.setToolCommandCount(m.getToolCommandCount());
        row.setToolSkillCount(m.getToolSkillCount());
        row.setToolBreakdownJson(m.getToolBreakdownJson() != null ? m.getToolBreakdownJson() : "[]");
        row.setTopModelsJson(m.getTopModelsJson() != null ? m.getTopModelsJson() : "[]");
        row.setTopProjectsJson(m.getTopProjectsJson() != null ? m.getTopProjectsJson() : "[]");
        row.setAgentDistJson(m.getAgentDistJson() != null ? m.getAgentDistJson() : "[]");
        return row;
    }

    // ------------------------------------------------------------------ utils

    private static double avgNonNull(Double... vs) {
        double sum = 0;
        int cnt = 0;
        for (Double v : vs) {
            if (v != null) {
                sum += v;
                cnt++;
            }
        }
        return cnt == 0 ? 0 : sum / cnt;
    }

    private static double safe(Double v) {
        return v == null || v.isNaN() ? 0.0 : v;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static BigDecimal scale2(Double v) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale4(Double v) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    /** 团队级 payload 中间产物，orchestrator 写回 analysis_report 字段时使用。 */
    public static class TeamPayload {
        public int activeUserCount;
        public int totalSessionCount;
        public BigDecimal totalActiveHours;
        public int totalAiCommit;
        public String teamDifficultyDistJson;
        public String teamModeDistJson;
        public String teamPercentilesJson;
        public String teamCapabilityPercentilesJson;
        public String watchlistSummaryJson;
        public String teamToolBreakdownJson;
    }

    public record AggregateOutcome(Map<String, UserMetrics> userMetrics,
                                   TeamPayload teamPayload) {
    }
}
