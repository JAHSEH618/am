package com.am.server.web.support;

import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.insight.aggregate.UserSlashInvocationExtractor;
import com.am.server.web.dto.PeopleDetailDto;
import com.am.server.web.dto.ToolStatDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 员工数据 / 模型与工具页：用户主动斜杠调用（{@code slash_command_count + slash_skill_count}，
 * Top 来自 {@code slash_hits_json}，缺失时从 {@code content_text} 回算）。
 */
@Component
@RequiredArgsConstructor
public class SlashCommandStatSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiSessionMessageRepository messageRepository;

    public List<PeopleDetailDto.NameValuePair> topCommandTokensForProject(
            String projectName, LocalDateTime from, LocalDateTime to,
            Collection<String> activeTypes, int topN) {
        if (projectName == null || projectName.isBlank()
                || activeTypes == null || activeTypes.isEmpty() || topN <= 0) {
            return List.of();
        }
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : messageRepository.loadUserMessagesForSlashStatsByProjectInWindow(
                projectName, from, to, activeTypes)) {
            accumulateSlashRow(counts, stringify(row[0]), stringify(row[1]), stringify(row[2]));
        }
        return toNameValuePairs(counts, topN);
    }

    public List<PeopleDetailDto.NameValuePair> topCommandTokensForUser(
            String userCode, LocalDateTime from, LocalDateTime to,
            Collection<String> activeTypes, int topN) {
        if (activeTypes == null || activeTypes.isEmpty() || topN <= 0) {
            return List.of();
        }
        Map<String, Long> counts = new HashMap<>();
        for (String json : messageRepository.loadSlashHitsJsonOnlyForUserInWindow(
                userCode, from, to, activeTypes)) {
            mergeSlashHits(counts, json);
        }
        for (Object[] row : messageRepository.loadSlashFallbackContentForUserInWindow(
                userCode, from, to, activeTypes)) {
            accumulateSlashRow(counts, stringify(row[0]), stringify(row[1]), null);
        }
        return toNameValuePairs(counts, topN);
    }

    public List<ToolStatDto> topCommandTokensGlobal(
            LocalDateTime from, LocalDateTime to, Collection<String> activeTypes, int limit) {
        if (activeTypes == null || activeTypes.isEmpty() || limit <= 0) {
            return List.of();
        }
        Map<String, Long> counts = new HashMap<>();
        Map<String, Set<String>> usersByToken = new HashMap<>();
        Map<String, Set<Long>> sessionsByToken = new HashMap<>();
        for (Object[] row : messageRepository.loadSlashHitsJsonOnlyInWindowGlobal(from, to, activeTypes)) {
            String user = stringify(row[0]);
            Long sessionId = row[1] == null ? null : ((Number) row[1]).longValue();
            String json = stringify(row[2]);
            String targetType = stringify(row[3]);
            accumulateSlashRowWithDims(counts, usersByToken, sessionsByToken,
                    null, targetType, json, user, sessionId);
        }
        for (Object[] row : messageRepository.loadSlashFallbackContentInWindowGlobal(from, to, activeTypes)) {
            String user = stringify(row[0]);
            Long sessionId = row[1] == null ? null : ((Number) row[1]).longValue();
            String contentText = stringify(row[2]);
            String targetType = stringify(row[3]);
            accumulateSlashRowWithDims(counts, usersByToken, sessionsByToken,
                    contentText, targetType, null, user, sessionId);
        }
        List<Map.Entry<String, Long>> sorted = counts.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed())
                .toList();
        int cap = Math.min(sorted.size(), limit);
        List<ToolStatDto> out = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            Map.Entry<String, Long> e = sorted.get(i);
            String token = e.getKey();
            out.add(new ToolStatDto(
                    token,
                    e.getValue(),
                    usersByToken.getOrDefault(token, Set.of()).size(),
                    sessionsByToken.getOrDefault(token, Set.of()).size()));
        }
        return out;
    }

    private static void accumulateSlashRow(
            Map<String, Long> counts, String contentText, String targetType, String slashHitsJson) {
        String json = resolveSlashHitsJson(contentText, targetType, slashHitsJson);
        mergeSlashHits(counts, json);
    }

    private static void accumulateSlashRowWithDims(
            Map<String, Long> counts,
            Map<String, Set<String>> usersByToken,
            Map<String, Set<Long>> sessionsByToken,
            String contentText,
            String targetType,
            String slashHitsJson,
            String userCode,
            Long sessionId) {
        String json = resolveSlashHitsJson(contentText, targetType, slashHitsJson);
        mergeSlashHitsWithDims(counts, usersByToken, sessionsByToken, json, userCode, sessionId);
    }

    /** 优先用已落库 JSON；历史消息仅有正文时现场回算。 */
    static String resolveSlashHitsJson(String contentText, String targetType, String slashHitsJson) {
        if (hasSlashHitsPayload(slashHitsJson)) {
            return slashHitsJson;
        }
        if (contentText == null || contentText.isBlank()) {
            return null;
        }
        UserSlashInvocationExtractor.Annotation ann =
                UserSlashInvocationExtractor.annotateUserContent(contentText, targetType);
        return ann.slashHitsJson();
    }

    private static boolean hasSlashHitsPayload(String json) {
        return json != null && !json.isBlank() && !"null".equalsIgnoreCase(json.trim()) && !"[]".equals(json.trim());
    }

    private static void mergeSlashHits(Map<String, Long> counts, String json) {
        if (!hasSlashHitsPayload(json)) {
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
                counts.merge(token, 1L, Long::sum);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static void mergeSlashHitsWithDims(
            Map<String, Long> counts,
            Map<String, Set<String>> usersByToken,
            Map<String, Set<Long>> sessionsByToken,
            String json,
            String userCode,
            Long sessionId) {
        if (!hasSlashHitsPayload(json)) {
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
                counts.merge(token, 1L, Long::sum);
                if (userCode != null && !userCode.isBlank()) {
                    usersByToken.computeIfAbsent(token, k -> new HashSet<>()).add(userCode);
                }
                if (sessionId != null) {
                    sessionsByToken.computeIfAbsent(token, k -> new HashSet<>()).add(sessionId);
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static List<PeopleDetailDto.NameValuePair> toNameValuePairs(Map<String, Long> counts, int topN) {
        List<PeopleDetailDto.NameValuePair> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed())
                .limit(topN)
                .forEach(e -> out.add(new PeopleDetailDto.NameValuePair(e.getKey(), e.getValue())));
        return out;
    }

    private static String stringify(Object cell) {
        return cell == null ? null : cell.toString();
    }
}
