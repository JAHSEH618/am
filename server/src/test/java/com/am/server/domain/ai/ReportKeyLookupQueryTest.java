package com.am.server.domain.ai;

import com.am.server.Application;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ingest 去重只按本次上报里的 key 反查已存行：两条 IN 查询打真库验一次（别名投影 / 原生 IN 展开）。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class ReportKeyLookupQueryTest {

    private static final long SESSION_ID = -998855L;
    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 1, 10, 0, 0);

    @Autowired
    private AiSessionMessageRepository messageRepository;

    @Autowired
    private AiSessionEventRepository eventRepository;

    @Autowired
    private EntityManager em;

    @Test
    void messageOrderByExternalIds_returnsOnlyRequestedIds() {
        messageRepository.save(message("ext-a", 1, 3));
        messageRepository.save(message("ext-b", 2, null));
        messageRepository.save(message("ext-c", 3, 5));
        em.flush();

        List<AiSessionMessageRepository.MessageOrderRow> rows =
                messageRepository.findMessageOrderByAiSessionIdAndExternalMessageIdIn(
                        SESSION_ID, List.of("ext-b", "ext-c", "ext-missing"));

        assertThat(rows).extracting(AiSessionMessageRepository.MessageOrderRow::getExternalMessageId)
                .containsExactlyInAnyOrder("ext-b", "ext-c");
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getExternalMessageId()).isEqualTo("ext-b");
            assertThat(r.getConversationOrder()).isNull();
            assertThat(r.getMessageTime()).isEqualTo(T);
        });
    }

    @Test
    void sourceRefsIn_returnsOnlyMaterializedRequestedRefs() {
        eventRepository.save(event("ref-1"));
        eventRepository.save(event("ref-1:msg"));
        eventRepository.save(event("ref-2"));
        eventRepository.save(event(null));
        em.flush();

        assertThat(eventRepository.findSourceRefsByAiSessionIdAndSourceRefIn(
                SESSION_ID, List.of("ref-1", "ref-1:msg", "ref-3", "ref-3:msg")))
                .containsExactlyInAnyOrder("ref-1", "ref-1:msg");
    }

    private AiSessionMessage message(String externalId, int seq, Integer order) {
        AiSessionMessage m = new AiSessionMessage();
        m.setAiSessionId(SESSION_ID);
        m.setTargetType("cursor");
        m.setUserCode("U-keylookup");
        m.setRole("assistant");
        m.setSequenceNo(seq);
        m.setExternalMessageId(externalId);
        m.setConversationOrder(order);
        m.setMessageTime(T);
        m.setContentKind("text_only");
        return m;
    }

    private AiSessionEvent event(String sourceRef) {
        AiSessionEvent e = new AiSessionEvent();
        e.setAiSessionId(SESSION_ID);
        e.setTargetType("cursor");
        e.setUserCode("U-keylookup");
        e.setEventType("MESSAGE_DELTA");
        e.setEventTime(T);
        e.setInputTokensDelta(0L);
        e.setOutputTokensDelta(0L);
        e.setTokensDelta(0L);
        e.setMessagesDelta(1);
        e.setSourceRef(sourceRef);
        return e;
    }
}
