package com.am.server.domain.monitor;

import com.am.server.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * monitor_target 字典 Repository 烟雾测试
 * 仅做只读校验：schema.sql 自动导入后应有一条 type_code='cursor' 的字典数据
 * gz
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class MonitorTargetRepositoryTest {

    @Autowired
    private MonitorTargetRepository repository;

    @Test
    void cursorDictionarySeeded() {
        Optional<MonitorTarget> cursor = repository.findByTypeCode("cursor");
        assertThat(cursor).isPresent();
        assertThat(cursor.get().getTypeName()).isEqualTo("Cursor");
        assertThat(cursor.get().getEnabled()).isEqualTo(1);
    }
}
