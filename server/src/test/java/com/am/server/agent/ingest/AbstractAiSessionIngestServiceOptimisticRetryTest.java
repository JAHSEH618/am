package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionEventRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.web.sse.SseHub;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AbstractAiSessionIngestServiceOptimisticRetryTest {

    /** 最小具体子类:仅为拿到包级 executeWithOptimisticRetry(受保护构造 + 抽象 targetType)。 */
    private static class TestIngest extends AbstractAiSessionIngestService {
        TestIngest() {
            super(mock(AiSessionRepository.class), mock(AiSessionEventRepository.class),
                    mock(AiSessionMessageRepository.class), mock(SseHub.class));
        }
        @Override
        protected String targetType() {
            return "test";
        }
    }

    private final TestIngest ingest = new TestIngest();

    @Test
    void retriesOnceThenReturnsActionResult() {
        AtomicInteger calls = new AtomicInteger();
        String r = ingest.executeWithOptimisticRetry(() -> {
            if (calls.getAndIncrement() == 0) {
                throw new ObjectOptimisticLockingFailureException("ai_session", 1L);
            }
            return "ok";
        }, () -> "exhausted", "sess-1");
        assertThat(calls.get()).isEqualTo(2);   // 首次冲突 + 重试成功
        assertThat(r).isEqualTo("ok");
    }

    @Test
    void givesUpAfterMaxAttemptsReturningOnExhausted() {
        AtomicInteger calls = new AtomicInteger();
        String r = ingest.executeWithOptimisticRetry(() -> {
            calls.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("ai_session", 1L);
        }, () -> "exhausted", "sess-2");
        assertThat(calls.get()).isEqualTo(3);   // MAX_OPTIMISTIC_ATTEMPTS
        assertThat(r).isEqualTo("exhausted");
    }
}
