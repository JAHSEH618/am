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
 * {@code aggregateOverviewWindow} 把 overview 原先的两条查询（窗内总览 + TOOL_CALL 计数）
 * 合并成一次扫描。合并靠的是条件聚合，算错了大盘会静默显示错数字——比慢更糟——所以这里
 * 用一组已知数据把七个返回值逐项钉死。
 *
 * <p>关键不变量：TOOL_CALL 行不得计入 token / 会话数 / 用户数 / 项目数，
 * 非 TOOL_CALL 行不得计入工具调用数。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class DashboardOverviewQueryTest {

    private static final String TT = "cursor";
    private static final String TAG = "-ovqtest";

    @Autowired
    private AiSessionRepository sessionRepository;

    @Autowired
    private AiSessionEventRepository eventRepository;

    @Test
    void aggregateOverviewWindow_splitsToolCallsFromTokenTotals() {
        LocalDateTime base = LocalDateTime.now().minusHours(1);
        LocalDateTime from = base.minusMinutes(30);
        LocalDateTime to = base.plusMinutes(30);

        AiSession s1 = sessionRepository.save(session("s1" + TAG, "u1" + TAG, "projA" + TAG, null));
        AiSession s2 = sessionRepository.save(session("s2" + TAG, "u2" + TAG, "projB" + TAG, null));
        // invalid 会话必须被 WHERE s.invalidReason IS NULL 排除，其事件一项都不该进统计。
        AiSession bad = sessionRepository.save(session("s3" + TAG, "u3" + TAG, "projC" + TAG, "merged_subagent"));

        eventRepository.save(event(s1, "TOKEN_DELTA", base, 100, 200, 2));
        eventRepository.save(event(s1, "MESSAGE_DELTA", base, 0, 0, 3));
        eventRepository.save(event(s2, "SESSION_OPEN", base, 50, 60, 1));
        // TOOL_CALL 带上非零 delta：合并查询必须把它从 token / message 里排掉，
        // 否则大盘 token 会凭空多出来。
        eventRepository.save(event(s1, "TOOL_CALL", base, 999, 999, 9));
        eventRepository.save(event(s1, "TOOL_CALL", base, 0, 0, 0));
        eventRepository.save(event(s2, "TOOL_CALL", base, 0, 0, 0));
        eventRepository.save(event(bad, "TOKEN_DELTA", base, 777, 777, 7));
        eventRepository.save(event(bad, "TOOL_CALL", base, 0, 0, 0));
        // 窗口外的行同样不得计入。
        eventRepository.save(event(s1, "TOKEN_DELTA", to.plusMinutes(5), 500, 500, 5));

        List<Object[]> rows = eventRepository.aggregateOverviewWindow(from, to, List.of(TT));
        assertThat(rows).hasSize(1);
        Object[] r = rows.get(0);

        assertThat(num(r[0])).as("input tokens：只数非 TOOL_CALL 且有效会话的窗内行").isEqualTo(150);
        assertThat(num(r[1])).as("output tokens").isEqualTo(260);
        assertThat(num(r[2])).as("messages").isEqualTo(6);
        assertThat(num(r[3])).as("去重会话数：s1 + s2，不含 invalid 的 s3").isEqualTo(2);
        assertThat(num(r[4])).as("去重用户数").isEqualTo(2);
        assertThat(num(r[5])).as("去重项目数").isEqualTo(2);
        assertThat(num(r[6])).as("工具调用数：s1 两条 + s2 一条，不含 invalid 会话的那条").isEqualTo(3);
    }

    @Test
    void aggregateOverviewWindow_returnsZerosWhenWindowEmpty() {
        LocalDateTime from = LocalDateTime.now().plusDays(30);
        List<Object[]> rows = eventRepository.aggregateOverviewWindow(from, from.plusHours(1), List.of(TT));

        assertThat(rows).hasSize(1);
        // 空窗口下 SUM 返回 NULL，COALESCE 必须兜成 0，否则 controller 的 toLong 拿到 null。
        for (Object v : rows.get(0)) {
            assertThat(num(v)).isZero();
        }
    }

    private static long num(Object o) {
        return o == null ? -1L : ((Number) o).longValue();
    }

    private AiSession session(String ext, String userCode, String project, String invalidReason) {
        AiSession s = new AiSession();
        s.setTargetType(TT);
        s.setExternalSessionId(ext);
        s.setAgentId("agent" + TAG);
        s.setUserCode(userCode);
        s.setHostHash("h" + TAG);
        s.setProjectName(project);
        s.setStatus("idle");
        s.setStartedAt(LocalDateTime.now());
        s.setLastActivity(LocalDateTime.now());
        s.setInvalidReason(invalidReason);
        return s;
    }

    private AiSessionEvent event(AiSession s, String type, LocalDateTime at,
                                 long inputDelta, long outputDelta, int messagesDelta) {
        AiSessionEvent e = new AiSessionEvent();
        e.setAiSessionId(s.getId());
        e.setTargetType(TT);
        e.setUserCode(s.getUserCode());
        e.setEventType(type);
        e.setEventTime(at);
        e.setInputTokensDelta(inputDelta);
        e.setOutputTokensDelta(outputDelta);
        e.setTokensDelta(inputDelta + outputDelta);
        e.setMessagesDelta(messagesDelta);
        return e;
    }
}
