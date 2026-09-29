package com.am.server.notify;

import com.am.server.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动宽限的起点必须真的接到 Spring 的 ApplicationReadyEvent（全部 ApplicationRunner 跑完之后才发布）：
 * 监听没挂上，告警器就会永远停在“未就绪”而从不发信。真起一次上下文验证接线。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class OfflineDeviceAlerterReadyEventTest {

    @Autowired
    private OfflineDeviceAlerter alerter;

    @Test
    void becomesReadyWhenTheApplicationHasFinishedStarting() {
        assertThat(alerter.isReady()).isTrue();
    }
}
