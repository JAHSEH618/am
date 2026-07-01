package com.am.server.domain.ai;

import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class AiSessionVersionTest {

    @Test
    void hasVersionFieldAnnotatedWithVersion() throws Exception {
        Field f = AiSession.class.getDeclaredField("version");
        assertThat(f.isAnnotationPresent(Version.class))
                .as("AiSession 必须有 @Version 乐观锁字段").isTrue();
        assertThat(f.getType()).isEqualTo(Long.class);
    }
}
