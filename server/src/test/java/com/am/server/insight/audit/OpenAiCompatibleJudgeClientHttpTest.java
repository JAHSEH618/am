package com.am.server.insight.audit;

import com.am.server.insight.config.InsightProperties.JudgeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleJudgeClientHttpTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static HttpServer server;
    private static int port;
    private static final List<String> receivedAuth = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            receivedAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            // 内层判官 JSON(合法 5 维评分),用 Jackson 生成外层信封避免转义错误
            String judgeJson = M.writeValueAsString(Map.of(
                    "difficulty", 3,
                    "outcome", "completed",
                    "mode", "leverage",
                    "capabilities", Map.of(
                            "problem_decomposition", 3,
                            "context_management", 3,
                            "debugging_skill", 3,
                            "tool_orchestration", 3,
                            "self_correction", 3),
                    "reason", "ok"));
            String envelope = M.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", judgeJson)))));
            byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void postsBearerAndParsesResultAndReusesClientAcrossCalls() {
        OpenAiCompatibleJudgeClient client = new OpenAiCompatibleJudgeClient();
        JudgeConfig cfg = new JudgeConfig();
        cfg.setEndpoint("http://127.0.0.1:" + port + "/v1");
        cfg.setApiKey("secret-key");
        cfg.setModel("test-model");
        cfg.setTimeoutMs(5000);

        JudgeResult r1 = client.judge(cfg, "prompt-1");
        JudgeResult r2 = client.judge(cfg, "prompt-2"); // 第二次调用:同一 client 复用

        assertThat(r1.getDifficulty()).isBetween(1, 5);
        assertThat(r1.getOutcome()).isEqualTo("completed");
        assertThat(r2).isNotNull();
        assertThat(receivedAuth).contains("Bearer secret-key");
    }
}
