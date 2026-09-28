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

    /** rubric v3.2 让 judge 先写 evidence 再打分：该字段不入库，解析必须忽略它而不是报错。 */
    @Test
    void ignoresLeadingEvidenceFieldFromRubricV32() {
        String content = "<think>先找主任务</think>{\"evidence\":\"主任务是修复「连接池打满」，开发者贴了线程栈并指出可疑位置\","
                + "\"difficulty\":4,\"outcome\":\"completed\",\"mode\":\"debugging\","
                + "\"capabilities\":{\"problem_decomposition\":3,\"context_management\":4,"
                + "\"debugging_skill\":4,\"tool_orchestration\":3,\"self_correction\":3},"
                + "\"reason\":\"定位并修复连接池打满问题，主动缩小排查范围\"}";
        JudgeResult r = client.parseResult("m", OpenAiCompatibleJudgeClient.extractJson(content));
        assertThat(r.getMode()).isEqualTo("debugging");
        assertThat(r.getCapDebuggingSkill()).isEqualTo(4);
        assertThat(r.getReason()).startsWith("定位并修复");
    }
}
