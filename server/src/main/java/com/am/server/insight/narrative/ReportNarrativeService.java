package com.am.server.insight.narrative;

import com.am.server.insight.audit.OpenAiCompatibleJudgeClient;
import com.am.server.insight.audit.SecretRedactor;
import com.am.server.insight.config.InsightProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 报告叙事：个人评语 + 团队总评。用 judgeA 配置各调一次 LLM；
 * 任何失败返回 null（报告照常 COMPLETED），见设计文档 §4.3。gz
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportNarrativeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRY = 3;
    private static final long[] BACKOFF_MS = {500L, 1500L, 4500L};
    private static final List<String> USER_KEYS =
            List.of("level_summary", "evidence", "strengths", "weaknesses", "suggestions");
    private static final List<String> TEAM_KEYS =
            List.of("overview", "highlights", "risks", "recommendations");

    private final InsightProperties properties;
    private final NarrativePromptLoader promptLoader;
    private final NarrativeCompletionClient client;

    /** @return 校验通过的规范化 JSON；生成/解析失败返回 null */
    public String generateUserNarrative(String payloadJson) {
        return generate(promptLoader.userPrompt(), payloadJson, USER_KEYS);
    }

    public String generateTeamNarrative(String payloadJson) {
        return generate(promptLoader.teamPrompt(), payloadJson, TEAM_KEYS);
    }

    private String generate(String template, String payloadJson, List<String> requiredKeys) {
        InsightProperties.JudgeConfig cfg = properties.getJudgeA();
        if (!"openai-compatible".equals(cfg.getProvider())) {
            log.info("narrative skipped: judgeA provider={} 不支持叙事", cfg.getProvider());
            return null;
        }
        String prompt = template + "\n\n【输入数据】\n"
                + SecretRedactor.redact(payloadJson)
                + "\n\n请直接输出 JSON。";
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                String raw = client.complete(cfg, prompt);
                String json = OpenAiCompatibleJudgeClient.extractJson(raw);
                JsonNode node = MAPPER.readTree(json);
                for (String k : requiredKeys) {
                    if (!node.hasNonNull(k) || node.path(k).asText().isBlank()) {
                        throw new IllegalStateException("missing key: " + k);
                    }
                }
                return MAPPER.writeValueAsString(node);
            } catch (Exception e) {
                log.warn("narrative attempt {}/{} failed: {}", attempt + 1, MAX_RETRY, e.getMessage());
                if (attempt < MAX_RETRY - 1) {
                    try {
                        Thread.sleep(BACKOFF_MS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
