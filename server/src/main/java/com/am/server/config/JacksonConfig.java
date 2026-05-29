package com.am.server.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置
 * - JSON 字段 snake_case，与 Go Agent 端序列化形式保持一致
 * - LocalDateTime 用 ISO-8601（不带时区，由请求/响应上下文规约时区为 +08:00）
 * - 反序列化遇到未知字段不报错，方便平台升级时向前兼容旧 Agent
 * gz
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .modules(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .simpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
    }
}
