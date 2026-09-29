package com.am.server.domain.ai;

import com.am.server.Application;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slash Commands 排行的索引下推版本 {@code loadCountedSlashHitsJsonInWindowGlobal} 与原查询
 * {@code loadSlashHitsJsonOnlyInWindowGlobal} 的差别只能是"计数和为 0 的行"——那些行的 JSON 只剩 noise，
 * 本来就不进排行。其余过滤（role 大小写、invalid 会话、窗口、target_type 白名单、JSON 判空）逐项一致。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class SlashRankingQueryTest {

    private static final String TT = "slashrank_test";
    private static final String TAG = "-slashrank";

    private static final LocalDate DAY = LocalDate.of(2019, 4, 8);
    private static final LocalDateTime FROM = DAY.atStartOfDay();
    private static final LocalDateTime TO = DAY.plusDays(1).atStartOfDay();

    @Autowired
    private AiSessionRepository sessionRepository;

    @Autowired
    private AiSessionMessageRepository messageRepository;

    @BeforeEach
    void seed() {
        AiSession ok = sessionRepository.save(session("s1" + TAG, null));
        AiSession bad = sessionRepository.save(session("s2" + TAG, "merged_subagent"));
        int seq = 0;
        // 进排行：command / nl_skill / 大小写混写的 "User"
        save(ok, "user", ++seq, DAY.atTime(9, 0), 1, 0, hits("/cmd", "command"));
        save(ok, "user", ++seq, DAY.atTime(9, 1), 0, 1, hits("nl-x", "nl_skill"));
        save(ok, "User", ++seq, DAY.atTime(9, 2), 0, 1, hits("/usercap", "skill"));
        // 只有 noise：计数 0，原查询会读出来但排行不数它
        save(ok, "user", ++seq, DAY.atTime(9, 3), 0, 0, hits("/usage", "noise"));
        // 计数 > 0 但 JSON 为空、assistant 行、invalid 会话、窗外：两条查询都不该出
        save(ok, "user", ++seq, DAY.atTime(9, 4), 1, 0, null);
        save(ok, "assistant", ++seq, DAY.atTime(9, 5), 1, 0, hits("/asst", "command"));
        save(bad, "user", ++seq, DAY.atTime(9, 6), 1, 0, hits("/invalid", "command"));
        save(ok, "user", ++seq, TO.plusHours(1), 1, 0, hits("/late", "command"));
        messageRepository.flush();
    }

    @Test
    void countedQueryDropsOnlyZeroCountRows() {
        List<Object[]> legacy = messageRepository.loadSlashHitsJsonOnlyInWindowGlobal(FROM, TO, List.of(TT));
        List<Object[]> counted = messageRepository.loadCountedSlashHitsJsonInWindowGlobal(FROM, TO, List.of(TT));

        assertThat(tokens(legacy)).containsExactlyInAnyOrder("/cmd", "nl-x", "/usercap", "/usage");
        assertThat(tokens(counted)).containsExactlyInAnyOrder("/cmd", "nl-x", "/usercap");
        for (Object[] r : counted) {
            assertThat(r[0]).isEqualTo("u" + TAG);
            assertThat(r[3]).isEqualTo(TT);
        }
    }

    @Test
    void countedQueryHonoursTargetTypeWhitelist() {
        assertThat(messageRepository.loadCountedSlashHitsJsonInWindowGlobal(FROM, TO, List.of("cursor"))).isEmpty();
    }

    private static List<String> tokens(List<Object[]> rows) {
        return rows.stream()
                .map(r -> r[2].toString().replaceAll(".*\"token\":\\s*\"([^\"]+)\".*", "$1"))
                .toList();
    }

    private static String hits(String token, String kind) {
        return "[{\"token\":\"" + token + "\",\"kind\":\"" + kind + "\"}]";
    }

    private void save(AiSession s, String role, int seq, LocalDateTime at, int cmd, int sk, String json) {
        AiSessionMessage m = new AiSessionMessage();
        m.setAiSessionId(s.getId());
        m.setTargetType(TT);
        m.setUserCode(s.getUserCode());
        m.setExternalMessageId(s.getExternalSessionId() + "#" + seq);
        m.setRole(role);
        m.setSequenceNo(seq);
        m.setMessageTime(at);
        m.setSlashCommandCount(cmd);
        m.setSlashSkillCount(sk);
        m.setSlashHitsJson(json);
        messageRepository.save(m);
    }

    private static AiSession session(String ext, String invalidReason) {
        AiSession s = new AiSession();
        s.setTargetType(TT);
        s.setExternalSessionId(ext);
        s.setAgentId("agent" + TAG);
        s.setUserCode("u" + TAG);
        s.setHostHash("h" + TAG);
        s.setProjectName("proj" + TAG);
        s.setStatus("idle");
        s.setStartedAt(FROM);
        s.setLastActivity(FROM);
        s.setInvalidReason(invalidReason);
        return s;
    }
}
