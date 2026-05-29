package com.am.server.web.support;

import com.am.server.web.dto.SlashInvocationDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量合并多条 user 消息的 {@code slash_hits_json}，按会话维度去重（token + kind），保留首次出现顺序。
 * gz
 */
public final class SlashInvocationMergeSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SlashInvocationMergeSupport() {}

    /**
     * @param rows native 查询行：[ai_session_id, slash_hits_json]
     */
    public static Map<Long, List<SlashInvocationDto>> mergeHitsBySession(List<Object[]> rows) {
        Map<Long, LinkedHashMap<String, SlashInvocationDto>> acc = new HashMap<>();
        if (rows == null) {
            return Map.of();
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            long sid = ((Number) row[0]).longValue();
            String json = row[1] != null ? row[1].toString() : null;
            if (json == null || json.isBlank()) {
                continue;
            }
            LinkedHashMap<String, SlashInvocationDto> m =
                    acc.computeIfAbsent(sid, k -> new LinkedHashMap<>());
            mergeJsonInto(m, json);
        }
        Map<Long, List<SlashInvocationDto>> out = new HashMap<>(acc.size());
        for (var e : acc.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        }
        return out;
    }

    private static void mergeJsonInto(LinkedHashMap<String, SlashInvocationDto> dedupe, String json) {
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) {
                return;
            }
            for (JsonNode n : arr) {
                String token = n.path("token").asText("");
                String kind = n.path("kind").asText("command");
                if (token.isEmpty()) {
                    continue;
                }
                String key = token + '\u0000' + kind;
                dedupe.putIfAbsent(key, new SlashInvocationDto(token, kind));
            }
        } catch (Exception ignored) {
            // best-effort：单条 JSON 坏不影响其它行
        }
    }
}
