package com.am.server.domain.ai;

import com.am.server.Application;
import com.am.server.insight.aggregate.NlSkillInvocationDetector;
import com.am.server.insight.aggregate.SlashHitsJsonSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NL skill 增量归因依赖的两条区间查询打真库验一次（JPQL 的 LOWER(TRIM(role)) / limit 1 / 区间下界），
 * 顺带验证 MySQL JSON 列回读的规范化形态不会被判成"有变更"。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class MessageSequenceRangeQueryTest {

    private static final long SESSION_ID = -998866L;

    @Autowired
    private AiSessionMessageRepository repository;

    @Autowired
    private EntityManager em;

    @Test
    void userSequenceBefore_andRangeFrom_useSequenceBounds() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
        repository.save(message("user", 1, t));
        repository.save(message("assistant", 2, t));
        repository.save(message(" User ", 3, t));   // 判据与 Java 侧 equalsIgnoreCase(trim) 对齐
        repository.save(message("tool", 4, t));
        repository.save(message("tool", 5, t));
        em.flush();

        assertThat(repository.findUserSequenceNosBefore(SESSION_ID, 5, PageRequest.of(0, 1))).containsExactly(3);
        assertThat(repository.findUserSequenceNosBefore(SESSION_ID, 3, PageRequest.of(0, 1))).containsExactly(1);
        assertThat(repository.findUserSequenceNosBefore(SESSION_ID, 1, PageRequest.of(0, 1))).isEmpty();

        assertThat(repository.findByAiSessionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(SESSION_ID, 3))
                .extracting(AiSessionMessage::getSequenceNo)
                .containsExactly(3, 4, 5);
    }

    @Test
    void replaceNlSkills_seesMysqlNormalizedJsonAsUnchanged() {
        AiSessionMessage m = message("user", 1, LocalDateTime.of(2026, 9, 1, 10, 0, 0));
        m.setSlashHitsJson("[{\"token\":\"/fix\",\"kind\":\"command\"},{\"token\":\"/skills/deploy\",\"kind\":\"nl_skill\"}]");
        m.setSlashCommandCount(1);
        m.setSlashSkillCount(1);
        Long id = repository.save(m).getId();
        em.flush();
        em.clear();

        AiSessionMessage reread = repository.findById(id).orElseThrow();
        boolean changed = SlashHitsJsonSupport.replaceNlSkills(reread,
                List.of(new NlSkillInvocationDetector.NlSkillHit("/skills/deploy")));

        assertThat(changed).as("回读值 %s", reread.getSlashHitsJson()).isFalse();
    }

    private AiSessionMessage message(String role, int seq, LocalDateTime time) {
        AiSessionMessage m = new AiSessionMessage();
        m.setAiSessionId(SESSION_ID);
        m.setTargetType("cursor");
        m.setUserCode("U-seqtest");
        m.setRole(role);
        m.setSequenceNo(seq);
        m.setExternalMessageId("seq-" + seq);
        m.setMessageTime(time);
        m.setContentKind("text_only");
        return m;
    }
}
