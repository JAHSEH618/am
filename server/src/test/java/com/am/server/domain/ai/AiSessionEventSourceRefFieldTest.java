package com.am.server.domain.ai;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class AiSessionEventSourceRefFieldTest {

    @Test
    void hasSourceRefColumnField() throws Exception {
        Field f = AiSessionEvent.class.getDeclaredField("sourceRef");
        assertThat(f.getType()).isEqualTo(String.class);
        Column col = f.getAnnotation(Column.class);
        assertThat(col).isNotNull();
        assertThat(col.name()).isEqualTo("source_ref");
    }
}
