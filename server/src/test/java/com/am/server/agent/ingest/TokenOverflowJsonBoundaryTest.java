package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.MonitorSessionDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「溢出 Long 的值」到底会在哪一层被挡住：JSON 数值超出 long 范围时，Jackson 在反序列化 {@code Long} 字段时就抛错
 * （整份上报 400），根本到不了 ingest；能到达 ingest 的最大值就是 {@code Long.MAX_VALUE}，由 TokenSanityGuard 判超限。
 * 这个测试把这条边界钉住，避免以后有人把 DTO 字段改成 {@code BigDecimal} / 关掉严格解析而失去这道保护。
 */
class TokenOverflowJsonBoundaryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void integerBeyondLongRangeIsRejectedAtParseTime() {
        assertThatThrownBy(() -> mapper.readValue(
                "{\"sessionId\":\"s\",\"inputTokens\":9223372036854775808}", MonitorSessionDto.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void longMaxValueParsesAndIsLeftToTheGuard() throws Exception {
        MonitorSessionDto dto = mapper.readValue(
                "{\"sessionId\":\"s\",\"inputTokens\":9223372036854775807}", MonitorSessionDto.class);
        assertThat(dto.getInputTokens()).isEqualTo(Long.MAX_VALUE);
        TokenSanityGuard guard = TokenSanityGuard.withDefaults();
        assertThat(guard.acceptSessionTotal(null, "claude", "s", "input_tokens", dto.getInputTokens(), 42L, false))
                .as("Long.MAX_VALUE 判超限，保留上一次的值").isEqualTo(42L);
    }
}
