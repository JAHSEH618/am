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
 * DailySummary token 护栏的"逐会话复核"查询：只返回 [id, input, output]，
 * 且与整日 SUM 用同一组过滤条件（用户 / last_activity 窗口 / target_type / 有效会话）。
 * 跑在真实 MySQL（test profile），事务回滚。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class AiSessionTokenSliceQueryTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2031, 3, 5, 0, 0);

    @Autowired
    private AiSessionRepository repository;

    @Test
    void slicesMatchTheSumQueryFilters() {
        AiSession a = repository.save(session("tok-slice-a", "U-slice", "codex", DAY.plusHours(3), 2_070_054_000_000L, 10_246_000_000L));
        AiSession b = repository.save(session("tok-slice-b", "U-slice", "codex", DAY.plusHours(5), 300_000_000L, 1_500_000L));
        // 应被过滤掉的：别的用户 / 窗口外 / 未启用的 target_type / invalid
        repository.save(session("tok-slice-other-user", "U-other", "codex", DAY.plusHours(4), 111L, 111L));
        repository.save(session("tok-slice-yesterday", "U-slice", "codex", DAY.minusHours(1), 222L, 222L));
        repository.save(session("tok-slice-disabled-type", "U-slice", "hermes", DAY.plusHours(6), 333L, 333L));
        AiSession invalid = session("tok-slice-invalid", "U-slice", "codex", DAY.plusHours(7), 444L, 444L);
        invalid.setInvalidReason("no_assistant_reply");
        repository.save(invalid);

        List<Object[]> rows = repository.findTokenSlicesByUserAndLastActivityWindowAndTargetTypeIn(
                "U-slice", DAY, DAY.plusDays(1), List.of("codex"));

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> ((Number) r[0]).longValue()).containsExactlyInAnyOrder(a.getId(), b.getId());
        long inputSum = rows.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
        long outputSum = rows.stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();

        // 与整日 SUM 查询一致（护栏的快速路径 / 复核路径看到的是同一批会话）
        List<Object[]> sum = repository.sumTokensByUserInLastActivityWindowAndTargetTypeIn(
                "U-slice", DAY, DAY.plusDays(1), List.of("codex"));
        assertThat(((Number) sum.get(0)[0]).longValue()).isEqualTo(inputSum);
        assertThat(((Number) sum.get(0)[1]).longValue()).isEqualTo(outputSum);
    }

    private static AiSession session(String ext, String user, String type, LocalDateTime lastActivity, long in, long out) {
        AiSession s = new AiSession();
        s.setTargetType(type);
        s.setExternalSessionId(ext);
        s.setAgentId("a1-tokslice");
        s.setUserCode(user);
        s.setHostHash("h1");
        s.setStatus("idle");
        s.setStartedAt(lastActivity.minusHours(1));
        s.setLastActivity(lastActivity);
        s.setInputTokens(in);
        s.setOutputTokens(out);
        return s;
    }
}
