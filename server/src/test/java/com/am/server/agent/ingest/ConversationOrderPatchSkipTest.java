package com.am.server.agent.ingest;

import com.am.server.domain.ai.AiSessionMessageRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ingest 对每条**已存**消息都会考虑发一条 conversation_order 补丁。Cursor 单次上报最多带
 * 1000 条历史消息，逐条发就是每个会话每一拍上千次空往返，所以现在先在内存里比对。
 *
 * <p>这个跳过判据必须和 {@code AiSessionMessageRepository#patchConversationOrderAndTime}
 * 的 WHERE 严格互为反面：漏跳只是白跑一次（无害），**错跳会让排序永远修不回来**（有害），
 * 所以四种情形逐个钉死。
 */
class ConversationOrderPatchSkipTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 7, 28, 10, 0, 0);

    @Test
    void skips_whenStoredValuesIdentical() {
        assertTrue(AbstractAiSessionIngestService.conversationOrderUnchanged(row(5, T), 5, T));
    }

    @Test
    void doesNotSkip_whenStoredOrderIsNull() {
        // SQL 侧 `m.conversationOrder IS NULL` 分支：首次补序号必须能落库。
        assertFalse(AbstractAiSessionIngestService.conversationOrderUnchanged(row(null, T), 5, T));
    }

    @Test
    void doesNotSkip_whenOrderDiffers() {
        assertFalse(AbstractAiSessionIngestService.conversationOrderUnchanged(row(4, T), 5, T));
    }

    @Test
    void doesNotSkip_whenMessageTimeDiffers() {
        assertFalse(AbstractAiSessionIngestService.conversationOrderUnchanged(row(5, T.plusSeconds(1)), 5, T));
    }

    @Test
    void doesNotSkip_whenRowMissing() {
        assertFalse(AbstractAiSessionIngestService.conversationOrderUnchanged(null, 5, T));
    }

    private static AiSessionMessageRepository.MessageOrderRow row(Integer order, LocalDateTime time) {
        return new AiSessionMessageRepository.MessageOrderRow() {
            @Override
            public String getExternalMessageId() {
                return "ext-1";
            }

            @Override
            public Integer getConversationOrder() {
                return order;
            }

            @Override
            public LocalDateTime getMessageTime() {
                return time;
            }
        };
    }
}
