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
 * Characterization test for AiSessionRepository#findAllNonIdle.
 * Verifies that idle sessions are excluded and non-idle sessions are returned.
 * Runs against real MySQL (test profile); transaction is rolled back after each test.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class AiSessionRepositoryNonIdleTest {

    @Autowired
    private AiSessionRepository repository;

    @Test
    void findAllNonIdleExcludesIdleStatus() {
        AiSession idle = repository.save(session("s-idle-nitest", "idle"));
        AiSession running = repository.save(session("s-running-nitest", "running"));
        AiSession thinking = repository.save(session("s-thinking-nitest", "thinking"));

        List<AiSession> out = repository.findAllNonIdle();

        assertThat(out).extracting(AiSession::getExternalSessionId)
                .contains("s-running-nitest", "s-thinking-nitest")
                .doesNotContain("s-idle-nitest");
    }

    private AiSession session(String ext, String status) {
        AiSession s = new AiSession();
        s.setTargetType("cursor");
        s.setExternalSessionId(ext);
        s.setAgentId("a1-nitest");
        s.setUserCode("U1");
        s.setHostHash("h1");
        s.setStatus(status);
        s.setStartedAt(LocalDateTime.now());
        s.setLastActivity(LocalDateTime.now());
        // isWorktree / message counts / tokens default to 0 from entity field initializers
        return s;
    }
}
