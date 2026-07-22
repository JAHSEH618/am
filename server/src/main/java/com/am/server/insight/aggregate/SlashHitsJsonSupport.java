package com.am.server.insight.aggregate;

import com.am.server.domain.ai.AiSessionMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 合并、重算 {@code ai_session_message.slash_hits_json} 与 {@code slash_*_count}。
 * gz
 */
public final class SlashHitsJsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SlashHitsJsonSupport() {}

    /**
     * 用新的 NL skill 列表替换 user 消息上全部 {@code nl_skill} 行（保留 command/skill/noise）。
     *
     * @return 是否有变更
     */
    public static boolean replaceNlSkills(
            AiSessionMessage userMsg, List<NlSkillInvocationDetector.NlSkillHit> hits) {
        if (userMsg == null) {
            return false;
        }
        List<Map<String, String>> rows = parseHits(userMsg.getSlashHitsJson());
        List<Map<String, String>> kept = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (!"nl_skill".equals(row.getOrDefault("kind", ""))) {
                kept.add(row);
            }
        }
        List<NlSkillInvocationDetector.NlSkillHit> toAdd =
                hits == null ? List.of() : hits;
        Map<String, Map<String, String>> byToken = new LinkedHashMap<>();
        for (Map<String, String> row : kept) {
            String token = row.get("token");
            if (token != null && !token.isBlank()) {
                byToken.put(token.toLowerCase(Locale.ROOT), row);
            }
        }
        for (NlSkillInvocationDetector.NlSkillHit hit : toAdd) {
            if (hit == null || hit.token() == null || hit.token().isBlank()) {
                continue;
            }
            String key = hit.token().toLowerCase(Locale.ROOT);
            if (byToken.containsKey(key)) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("token", key);
            row.put("kind", "nl_skill");
            byToken.put(key, row);
        }
        List<Map<String, String>> merged = new ArrayList<>(byToken.values());
        String before = userMsg.getSlashHitsJson();
        try {
            String after = merged.isEmpty() ? null : MAPPER.writeValueAsString(merged);
            if (jsonEquivalent(before, after)) {
                return false;
            }
            userMsg.setSlashHitsJson(after);
        } catch (JsonProcessingException e) {
            return false;
        }
        recount(userMsg, merged);
        return true;
    }

    /** {@link #recomputeExtracted} 的结果：重算后的三列值；{@code changed=false} 表示与现值等价、无需 UPDATE。 */
    public record ExtractedRecompute(String hitsJson, int commandCount, int skillCount, boolean changed) {}

    /**
     * 用新鲜提取结果替换 {@code command/skill/noise} 行、保留既有 {@code nl_skill} 行
     * （同 token 时新行优先，与 {@link #replaceNlSkills} 的去重方向一致），并按现行语义重记数。
     * 存量回填（{@code SlashAnnotationBackfillPatch}）在 JDBC 行上调用，故收发都是普通值而非实体。
     */
    public static ExtractedRecompute recomputeExtracted(
            String oldHitsJson, int oldCommandCount, int oldSkillCount,
            UserSlashInvocationExtractor.Annotation fresh) {
        List<Map<String, String>> freshRows =
                parseHits(fresh == null ? null : fresh.slashHitsJson());
        Map<String, Map<String, String>> byToken = new LinkedHashMap<>();
        for (Map<String, String> row : freshRows) {
            String token = row.get("token");
            if (token != null && !token.isBlank()) {
                byToken.put(token.toLowerCase(Locale.ROOT), row);
            }
        }
        for (Map<String, String> row : parseHits(oldHitsJson)) {
            if (!"nl_skill".equals(row.getOrDefault("kind", ""))) {
                continue;
            }
            String token = row.get("token");
            if (token == null || token.isBlank()) {
                continue;
            }
            byToken.putIfAbsent(token.toLowerCase(Locale.ROOT), row);
        }
        List<Map<String, String>> merged = new ArrayList<>(byToken.values());
        int[] counts = countKinds(merged);
        String json;
        try {
            json = merged.isEmpty() ? null : MAPPER.writeValueAsString(merged);
        } catch (JsonProcessingException e) {
            return new ExtractedRecompute(oldHitsJson, oldCommandCount, oldSkillCount, false);
        }
        boolean changed = counts[0] != oldCommandCount || counts[1] != oldSkillCount
                || !parseHits(oldHitsJson).equals(merged);
        return new ExtractedRecompute(json, counts[0], counts[1], changed);
    }

    private static boolean jsonEquivalent(String a, String b) {
        if (a == null || a.isBlank() || "null".equalsIgnoreCase(a.trim())) {
            return b == null || b.isBlank() || "null".equalsIgnoreCase(String.valueOf(b).trim());
        }
        if (b == null || b.isBlank() || "null".equalsIgnoreCase(b.trim())) {
            return false;
        }
        return a.trim().equals(b.trim());
    }

    /**
     * 将 NL skill 命中写入 user 消息；按 token 去重（与已有 command/skill/nl_skill 任一 kind 均不重复添加）。
     *
     * @return 是否有变更
     */
    public static boolean mergeNlSkills(AiSessionMessage userMsg, List<NlSkillInvocationDetector.NlSkillHit> hits) {
        if (userMsg == null || hits == null || hits.isEmpty()) {
            return false;
        }
        List<Map<String, String>> rows = parseHits(userMsg.getSlashHitsJson());
        Map<String, Map<String, String>> byToken = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String token = row.get("token");
            if (token != null && !token.isBlank()) {
                byToken.put(token.toLowerCase(Locale.ROOT), row);
            }
        }
        boolean changed = false;
        for (NlSkillInvocationDetector.NlSkillHit hit : hits) {
            if (hit == null || hit.token() == null || hit.token().isBlank()) {
                continue;
            }
            String key = hit.token().toLowerCase(Locale.ROOT);
            if (byToken.containsKey(key)) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("token", hit.token().toLowerCase(Locale.ROOT));
            row.put("kind", "nl_skill");
            byToken.put(key, row);
            changed = true;
        }
        if (!changed) {
            return false;
        }
        List<Map<String, String>> merged = new ArrayList<>(byToken.values());
        try {
            userMsg.setSlashHitsJson(MAPPER.writeValueAsString(merged));
        } catch (JsonProcessingException e) {
            return false;
        }
        recount(userMsg, merged);
        return true;
    }

    /**
     * 解析 {@code slash_hits_json} 为 {@code [{token, kind}, ...]} 行；kind 缺省按 {@code command}。
     * 容错：非数组 / 解析失败返回空列表。公开给 CapabilityDailyAggregator 等聚合方复用。
     */
    public static List<Map<String, String>> parseHits(String json) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (json == null || json.isBlank() || "null".equalsIgnoreCase(json.trim()) || "[]".equals(json.trim())) {
            return rows;
        }
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) {
                return rows;
            }
            for (JsonNode n : arr) {
                String token = n.path("token").asText("");
                String kind = n.path("kind").asText("");
                if (token.isEmpty()) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("token", token);
                row.put("kind", kind.isEmpty() ? "command" : kind);
                rows.add(row);
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return rows;
    }

    private static int[] countKinds(List<Map<String, String>> rows) {
        int cmd = 0;
        int sk = 0;
        for (Map<String, String> row : rows) {
            String kind = row.getOrDefault("kind", "");
            if ("command".equals(kind)) {
                cmd++;
            } else if ("skill".equals(kind) || "nl_skill".equals(kind)) {
                sk++;
            }
        }
        return new int[]{cmd, sk};
    }

    private static void recount(AiSessionMessage userMsg, List<Map<String, String>> merged) {
        int[] counts = countKinds(merged);
        userMsg.setSlashCommandCount(counts[0]);
        userMsg.setSlashSkillCount(counts[1]);
    }

    /** 测试 / 回算用：从 hits 列表写回实体字段 */
    static void applyHits(AiSessionMessage userMsg, ArrayNode hits) throws JsonProcessingException {
        userMsg.setSlashHitsJson(MAPPER.writeValueAsString(hits));
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode n : hits) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("token", n.path("token").asText(""));
            row.put("kind", n.path("kind").asText("command"));
            rows.add(row);
        }
        recount(userMsg, rows);
    }

    static ObjectNode hitObject(String token, String kind) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("token", token);
        o.put("kind", kind);
        return o;
    }
}
