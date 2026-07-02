package com.am.server.insight.audit;

import com.am.server.insight.config.InsightProperties.JudgeConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 OpenAI 兼容协议 JudgeClient —— provider=openai-compatible 时启用。
 *
 * <p>同时覆盖：
 * <ul>
 *   <li>自建 vLLM / TGI / Ollama 等暴露 {@code POST /v1/chat/completions} 的本地服务</li>
 *   <li>OpenAI 官方 / Azure OpenAI / DeepSeek / Moonshot / Qwen DashScope 兼容模式</li>
 * </ul>
 *
 * <p>请求 JSON Schema 强制 LLM 输出固定字段，避免 free-form 自然语言解析风险。
 * 返回的 JSON 字符串由 {@link #parseResult(String, String)} 解析为 {@link JudgeResult}。
 * gz
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleJudgeClient implements JudgeClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public JudgeResult judge(JudgeConfig config, String prompt) {
        return parseResult(config.getModel(), completeRaw(config, prompt));
    }

    /** 发一次 /chat/completions，返回原始 content 文本（叙事等非 rubric 场景复用）。 */
    public String completeRaw(JudgeConfig config, String prompt) {
        if (config.getEndpoint() == null || config.getEndpoint().isBlank()) {
            throw new JudgeException("openai-compatible judge: endpoint is empty");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", prompt
        )));
        body.put("temperature", 0.0);
        // 不带 response_format:部分网关 / vLLM / Anthropic 兼容层不支持 object 形态参数会 400。
        // 改为在 parseResult 里做 <think> 清洗 + JSON 提取,更通用。
        body.put("max_tokens", 4096);

        String url = config.getEndpoint().replaceAll("/+$", "") + "/chat/completions";
        int timeoutMs = config.getTimeoutMs() <= 0 ? 60_000 : config.getTimeoutMs();

        java.net.http.HttpRequest.Builder reqBuilder;
        try {
            reqBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new JudgeException("openai-compatible judge: cannot serialize request body", e);
        }
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + config.getApiKey());
        }

        java.net.http.HttpResponse<String> resp;
        try {
            resp = HTTP.send(reqBuilder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // 含 HttpTimeoutException;交由上层 DualJudgeService 重试
            throw new JudgeException("openai-compatible judge call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JudgeException("openai-compatible judge call interrupted", e);
        }

        if (resp.statusCode() / 100 != 2) {
            throw new JudgeException("openai-compatible judge: HTTP " + resp.statusCode());
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(resp.body());
        } catch (JsonProcessingException e) {
            throw new JudgeException("openai-compatible judge: response not JSON", e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new JudgeException("openai-compatible judge: no choices");
        }
        String content = choices.get(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new JudgeException("openai-compatible judge: empty content");
        }
        return content;
    }

    /** 解析 LLM JSON 输出；任何字段缺失 / 越界都会抛 JudgeException 让 orchestrator 走重试链路。 */
    JudgeResult parseResult(String judgeModel, String content) {
        String cleaned = extractJson(content);
        JsonNode root;
        try {
            root = MAPPER.readTree(cleaned);
        } catch (JsonProcessingException e) {
            String previewClean = cleaned.length() > 500 ? cleaned.substring(0, 500) + "...[truncated]" : cleaned;
            String previewRaw = content == null ? "<null>" :
                    (content.length() > 800 ? content.substring(0, 800) + "...[truncated]" : content);
            throw new JudgeException(
                    "openai-compatible judge: invalid JSON after cleanup. cleaned=" + previewClean
                            + "\n--- raw response ---\n" + previewRaw, e);
        }

        int difficulty = requireScore(root, "difficulty");
        String outcome = normalizeOutcome(root.path("outcome").asText("").trim().toLowerCase());
        String mode = normalizeMode(root.path("mode").asText("").trim().toLowerCase());

        JsonNode cap = root.path("capabilities");
        if (cap.isMissingNode() || cap.isNull() || !cap.isObject()) {
            throw new JudgeException(
                    "openai-compatible judge: missing/invalid required 'capabilities' object", null);
        }
        int problem = requireScore(cap, "problem_decomposition");
        int context = requireScore(cap, "context_management");
        int debugging = requireScore(cap, "debugging_skill");
        int tool = requireScore(cap, "tool_orchestration");
        int self = requireScore(cap, "self_correction");

        String reason = root.path("reason").asText("");
        if (reason.length() > 500) {
            reason = reason.substring(0, 500);
        }

        return JudgeResult.builder()
                .judgeModel(judgeModel)
                .difficulty(difficulty)
                .outcome(outcome)
                .mode(mode)
                .capProblemDecomposition(problem)
                .capContextManagement(context)
                .capDebuggingSkill(debugging)
                .capToolOrchestration(tool)
                .capSelfCorrection(self)
                .reason(reason)
                .build();
    }

    private static String normalizeOutcome(String v) {
        return switch (v) {
            case "completed", "partial", "abandoned" -> v;
            default -> "partial"; // 未知值降级为最中性的 partial
        };
    }

    private static String normalizeMode(String v) {
        return switch (v) {
            case "leverage", "learning", "dependent", "exploratory", "debugging" -> v;
            default -> "exploratory";
        };
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 必填评分字段:缺失 / null / 非整数一律抛 JudgeException(触发上层重试),不再静默降级为 1。 */
    private static int requireScore(JsonNode parent, String field) {
        JsonNode v = parent.path(field);
        if (v.isMissingNode() || v.isNull() || !v.canConvertToInt()) {
            throw new JudgeException(
                    "openai-compatible judge: missing/invalid required score field '" + field + "'", null);
        }
        return clamp(v.asInt(), 1, 5);
    }

    /**
     * 清洗 LLM 原始 content 提取 JSON 对象。覆盖三类常见污染：
     * <ol>
     *   <li>reasoning 模型（minimax / deepseek-r / qwq 等）前缀的 {@code <think>...</think>} 块</li>
     *   <li>模型自作主张包的 markdown 代码块（{@code ```json ... ```} / {@code ``` ... ```}）</li>
     *   <li>JSON 前后的解释性文字 / 空白</li>
     * </ol>
     *
     * <p>定位策略：先剥 think + fence，再用"第一个 '{' 到最后一个 '}'"取出 JSON 主体。
     * 不做完整括号配对（实测下来配对所需的状态机对解析没增益，反而把这函数复杂化）。
     */
    public static String extractJson(String raw) {
        if (raw == null) return "";
        String s = raw;

        // 1. 剥 reasoning model 的 <think>...</think>（多段、含换行；非贪婪）
        s = THINK_PATTERN.matcher(s).replaceAll("");

        // 2. 剥 markdown code fence： ```json ... ``` 或 ``` ... ```
        s = FENCE_PATTERN.matcher(s).replaceAll("$1");

        // 3. 取第一个 '{' 到最后一个 '}' 之间的内容
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1).trim();
        }
        return s.trim();
    }

    private static final java.util.regex.Pattern THINK_PATTERN =
            java.util.regex.Pattern.compile("<think>[\\s\\S]*?</think>", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern FENCE_PATTERN =
            java.util.regex.Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", java.util.regex.Pattern.CASE_INSENSITIVE);
}
