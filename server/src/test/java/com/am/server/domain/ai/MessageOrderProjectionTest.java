package com.am.server.domain.ai;

import com.am.server.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findMessageOrderByAiSessionId} 是带别名的接口投影——别名和 getter 对不上时
 * 不会编译报错，只会在运行时炸或者拿到 null。ingest 主链路依赖它，所以打真库验一次。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class MessageOrderProjectionTest {

    private static final long SESSION_ID = -998877L;

    @Autowired
    private AiSessionMessageRepository repository;

    @Test
    void findMessageOrder_returnsAllThreeFieldsIncludingNulls() {
        LocalDateTime t = LocalDateTime.of(2026, 7, 28, 10, 0, 0);
        repository.save(message("ext-a", 3, t));
        // conversation_order 可空：ingest 靠"现值为 NULL"判断需要首次补序号，投影必须如实回传 null。
        repository.save(message("ext-b", null, t.plusMinutes(1)));

        List<AiSessionMessageRepository.MessageOrderRow> rows =
                repository.findMessageOrderByAiSessionId(SESSION_ID);

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getExternalMessageId()).isEqualTo("ext-a");
            assertThat(r.getConversationOrder()).isEqualTo(3);
            assertThat(r.getMessageTime()).isEqualTo(t);
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getExternalMessageId()).isEqualTo("ext-b");
            assertThat(r.getConversationOrder()).isNull();
        });
    }

    private AiSessionMessage message(String externalId, Integer order, LocalDateTime time) {
        AiSessionMessage m = new AiSessionMessage();
        m.setAiSessionId(SESSION_ID);
        m.setTargetType("cursor");
        m.setUserCode("U-projtest");
        m.setRole("user");
        m.setSequenceNo(Math.abs(externalId.hashCode() % 1000));
        m.setExternalMessageId(externalId);
        m.setConversationOrder(order);
        m.setMessageTime(time);
        m.setContentKind("text_only");
        return m;
    }
}
