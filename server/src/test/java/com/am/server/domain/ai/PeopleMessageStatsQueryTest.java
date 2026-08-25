package com.am.server.domain.ai;

import com.am.server.Application;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 员工数据把「问答比」「Slash 合计」「Slash 按天」三个数合并成条件聚合，一次扫描出结果。
 * 合并靠 {@code SUM(CASE WHEN ...)}，算错了页面会静默显示错数字——比慢更糟——所以这里
 * 用一组已知数据把每一项钉死。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>role 大小写不敏感（客户端上报过 {@code "User"}）</li>
 *   <li>Slash 只算 user 消息；assistant / tool 行的计数字段一律不进合计</li>
 *   <li>invalid 会话、窗口外、非 active target_type 的消息一条都不进统计</li>
 *   <li>按天各项相加 == 整窗合计（详情页正是这么取窗口合计的）</li>
 * </ul>
 *
 * <p>另外钉住 {@code probeActiveEventAfter}：它取代了原先按天 {@code MAX(event_time)} 的全窗扫描，
 * 是 ensureFresh 判过期的唯一依据，判错要么让页面停在旧快照、要么每次访问都白算一遍。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class PeopleMessageStatsQueryTest {

    /** 用一个现实中不存在的 target_type 做隔离，避免与开发库里的既有数据串味。 */
    private static final String TT = "pplstat_test";
    private static final String TAG = "-pplstat";
    private static final String U1 = "u1" + TAG;
    private static final String U2 = "u2" + TAG;

    private static final LocalDate DAY1 = LocalDate.of(2019, 3, 4);
    private static final LocalDate DAY2 = LocalDate.of(2019, 3, 5);
    private static final LocalDateTime FROM = DAY1.atStartOfDay();
    private static final LocalDateTime TO = DAY2.plusDays(1).atStartOfDay();

    @Autowired
    private AiSessionRepository sessionRepository;

    @Autowired
    private AiSessionMessageRepository messageRepository;

    @Autowired
    private AiSessionEventRepository eventRepository;

    private AiSession ok1;
    private AiSession ok2;
    private AiSession bad;

    @BeforeEach
    void seed() {
        ok1 = sessionRepository.save(session("s1" + TAG, U1, null));
        ok2 = sessionRepository.save(session("s2" + TAG, U2, null));
        bad = sessionRepository.save(session("s3" + TAG, U1, "merged_subagent"));

        // U1 · DAY1：两条提问（其中一条带 1 command + 2 skill）+ 一条回复
        messageRepository.save(message(ok1, U1, "user", 1, DAY1.atTime(10, 0), 1, 2));
        messageRepository.save(message(ok1, U1, "user", 2, DAY1.atTime(10, 5), 0, 0));
        messageRepository.save(message(ok1, U1, "assistant", 3, DAY1.atTime(10, 6), 0, 0));
        // U1 · DAY2：大小写混写的 "User" 必须照样算提问；tool 行两边都不算
        messageRepository.save(message(ok1, U1, "User", 4, DAY2.atTime(9, 0), 0, 1));
        messageRepository.save(message(ok1, U1, "assistant", 5, DAY2.atTime(9, 1), 0, 0));
        messageRepository.save(message(ok1, U1, "tool", 6, DAY2.atTime(9, 2), 0, 0));
        // 窗口外
        messageRepository.save(message(ok1, U1, "user", 7, TO.plusHours(10), 9, 90));
        // invalid 会话
        messageRepository.save(message(bad, U1, "user", 1, DAY1.atTime(11, 0), 50, 0));

        // U2
        messageRepository.save(message(ok2, U2, "user", 1, DAY1.atTime(14, 0), 7, 0));
        messageRepository.save(message(ok2, U2, "assistant", 2, DAY1.atTime(14, 1), 0, 0));
        messageRepository.save(message(ok2, U2, "assistant", 3, DAY1.atTime(14, 2), 0, 0));
        messageRepository.flush();
    }

    @Test
    void aggregateByUser_splitsRolesAndCountsSlashOnUserRowsOnly() {
        Map<String, long[]> byUser = new HashMap<>();
        for (Object[] r : messageRepository.aggregatePeopleMessageStatsByUserInWindow(FROM, TO, List.of(TT))) {
            byUser.put((String) r[0], new long[]{num(r[1]), num(r[2]), num(r[3])});
        }

        assertThat(byUser.keySet()).containsExactlyInAnyOrder(U1, U2);
        assertThat(byUser.get(U1)[0]).as("U1 提问数：DAY1 两条 + DAY2 的 \"User\"，不含 invalid / 窗外").isEqualTo(3);
        assertThat(byUser.get(U1)[1]).as("U1 回复数：tool 行不算").isEqualTo(2);
        assertThat(byUser.get(U1)[2]).as("U1 Slash：(1+2) + 0 + (0+1)").isEqualTo(4);
        assertThat(byUser.get(U2)[0]).isEqualTo(1);
        assertThat(byUser.get(U2)[1]).isEqualTo(2);
        assertThat(byUser.get(U2)[2]).isEqualTo(7);
    }

    @Test
    void aggregateByUser_ignoresInactiveTargetTypes() {
        assertThat(messageRepository.aggregatePeopleMessageStatsByUserInWindow(FROM, TO, List.of("cursor")))
                .as("target_type 不在白名单里就一行都不该出").isEmpty();
    }

    @Test
    void aggregateByDay_sumsBackToTheWindowTotals() {
        Map<LocalDate, long[]> byDay = new HashMap<>();
        for (Object[] r : messageRepository
                .aggregatePeopleMessageStatsByDayForUserInWindow(U1, FROM, TO, List.of(TT))) {
            byDay.put(((Date) r[0]).toLocalDate(), new long[]{num(r[1]), num(r[2]), num(r[3])});
        }

        assertThat(byDay.keySet()).containsExactlyInAnyOrder(DAY1, DAY2);
        assertThat(byDay.get(DAY1)).containsExactly(2, 1, 3);
        assertThat(byDay.get(DAY2)).containsExactly(1, 1, 1);

        // 详情页的窗口合计就是逐日相加得来的，必须与整窗聚合逐项相等。
        long users = 0, assistants = 0, slash = 0;
        for (long[] v : byDay.values()) {
            users += v[0];
            assistants += v[1];
            slash += v[2];
        }
        assertThat(new long[]{users, assistants, slash}).containsExactly(3, 2, 4);
    }

    @Test
    void probeActiveEventAfter_onlyFiresForEventsNewerThanTheSnapshot() {
        eventRepository.save(event(ok1, DAY1.atTime(12, 0)));
        eventRepository.save(event(bad, DAY2.atTime(12, 0)));
        eventRepository.flush();

        LocalDateTime d1Start = DAY1.atStartOfDay();
        LocalDateTime d1End = DAY2.atStartOfDay();
        assertThat(eventRepository.probeActiveEventAfter(d1Start, d1End, DAY1.atTime(11, 0), List.of(TT)))
                .as("快照停在 11:00，12:00 还有事件 → 过期").isNotEmpty();
        assertThat(eventRepository.probeActiveEventAfter(d1Start, d1End, DAY1.atTime(13, 0), List.of(TT)))
                .as("快照已晚于当日最后一条事件 → 不过期").isEmpty();

        LocalDateTime d2Start = DAY2.atStartOfDay();
        assertThat(eventRepository.probeActiveEventAfter(
                d2Start, d2Start.plusDays(1), d2Start.minusDays(1), List.of(TT)))
                .as("当日只有 invalid 会话的事件 → 视同当日无事件").isEmpty();
    }

    private static long num(Object o) {
        return o == null ? -1L : ((Number) o).longValue();
    }

    private AiSession session(String ext, String userCode, String invalidReason) {
        AiSession s = new AiSession();
        s.setTargetType(TT);
        s.setExternalSessionId(ext);
        s.setAgentId("agent" + TAG);
        s.setUserCode(userCode);
        s.setHostHash("h" + TAG);
        s.setProjectName("proj" + TAG);
        s.setStatus("idle");
        s.setStartedAt(FROM);
        s.setLastActivity(FROM);
        s.setInvalidReason(invalidReason);
        return s;
    }

    private AiSessionMessage message(AiSession s, String userCode, String role, int seq,
                                     LocalDateTime at, int slashCommands, int slashSkills) {
        AiSessionMessage m = new AiSessionMessage();
        m.setAiSessionId(s.getId());
        m.setTargetType(TT);
        m.setUserCode(userCode);
        m.setExternalMessageId(s.getExternalSessionId() + "#" + seq);
        m.setRole(role);
        m.setSequenceNo(seq);
        m.setMessageTime(at);
        m.setSlashCommandCount(slashCommands);
        m.setSlashSkillCount(slashSkills);
        return m;
    }

    private AiSessionEvent event(AiSession s, LocalDateTime at) {
        AiSessionEvent e = new AiSessionEvent();
        e.setAiSessionId(s.getId());
        e.setTargetType(TT);
        e.setUserCode(s.getUserCode());
        e.setEventType("MESSAGE_DELTA");
        e.setEventTime(at);
        return e;
    }
}
