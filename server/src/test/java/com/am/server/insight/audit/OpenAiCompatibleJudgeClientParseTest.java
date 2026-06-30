package com.am.server.insight.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiCompatibleJudgeClientParseTest {

    private final OpenAiCompatibleJudgeClient client = mock(OpenAiCompatibleJudgeClient.class);

    OpenAiCompatibleJudgeClientParseTest() {
        when(client.parseResult(anyString(), anyString())).thenCallRealMethod();
    }

    @Test
    void throwsWhenDifficultyMissing() {
        String json = "{\"outcome\":\"completed\",\"mode\":\"leverage\","
                + "\"capabilities\":{\"problem_decomposition\":3,\"context_management\":3,"
                + "\"debugging_skill\":3,\"tool_orchestration\":3,\"self_correction\":3}}";
        assertThatThrownBy(() -> client.parseResult("m", json))
                .isInstanceOf(JudgeException.class);
    }

    @Test
    void throwsWhenCapabilitiesMissing() {
        String json = "{\"difficulty\":3,\"outcome\":\"completed\",\"mode\":\"leverage\"}";
        assertThatThrownBy(() -> client.parseResult("m", json))
                .isInstanceOf(JudgeException.class);
    }

    @Test
    void throwsWhenOneCapabilityFieldMissing() {
        String json = "{\"difficulty\":3,\"outcome\":\"completed\",\"mode\":\"leverage\","
                + "\"capabilities\":{\"problem_decomposition\":3,\"context_management\":3,"
                + "\"debugging_skill\":3,\"tool_orchestration\":3}}"; // 缺 self_correction
        assertThatThrownBy(() -> client.parseResult("m", json))
                .isInstanceOf(JudgeException.class);
    }

    @Test
    void parsesValidPayload() {
        String json = "{\"difficulty\":4,\"outcome\":\"completed\",\"mode\":\"leverage\","
                + "\"capabilities\":{\"problem_decomposition\":3,\"context_management\":4,"
                + "\"debugging_skill\":2,\"tool_orchestration\":5,\"self_correction\":3},"
                + "\"reason\":\"ok\"}";
        assertThat(client.parseResult("m", json).getDifficulty()).isEqualTo(4);
    }
}
