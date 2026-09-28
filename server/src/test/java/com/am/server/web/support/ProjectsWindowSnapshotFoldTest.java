package com.am.server.web.support;

import com.am.server.web.dto.ProjectSummaryDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** (project, model, user) 一次分组折叠 == 原 byProject / modelMatrix / meta 三条 SQL 的口径。 */
class ProjectsWindowSnapshotFoldTest {

    private static Object[] row(String project, String model, String user, long tokens, long msgs, long sessions,
                                LocalDateTime last, String repo) {
        return new Object[]{project, model, user, tokens, msgs, sessions, last, repo};
    }

    @Test
    void foldsSumsDistinctUsersTopModelAndMeta() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 2, 9, 0);
        var folded = ProjectsWindowSnapshotCache.foldProjectRows(List.of(
                row("p1", "opus", "alice", 100, 3, 1, t1, "git@x/p1.git"),
                row("p1", "sonnet", "alice", 300, 5, 2, t2, "git@x/p1.git"),
                row("p1", "opus", "bob", 50, 1, 1, t1, null),
                row("p1", null, "bob", 10, 1, 1, t1, null),
                row("p2", "opus", "carol", 7, 1, 1, t1, null)));

        ProjectSummaryDto p1 = folded.summaries().get("p1");
        assertThat(p1.getTotalTokens()).isEqualTo(460);
        assertThat(p1.getMessageCount()).isEqualTo(10);
        assertThat(p1.getSessionCount()).as("会话只落在一组，可直接求和").isEqualTo(5);
        assertThat(p1.getUserCount()).as("用户跨模型去重").isEqualTo(2);
        assertThat(p1.getTopModel()).as("非空 model 中 tokens 最大者：sonnet 300 > opus 150").isEqualTo("sonnet");
        assertThat(p1.getLastActivity()).isEqualTo(t2);
        assertThat(p1.getRepoUrl()).isEqualTo("git@x/p1.git");
        assertThat(folded.meta().get("p1").lastActivity()).isEqualTo(t2);

        ProjectSummaryDto p2 = folded.summaries().get("p2");
        assertThat(p2.getUserCount()).isEqualTo(1);
        assertThat(p2.getRepoUrl()).isNull();
    }

    @Test
    void emptyRowsYieldEmptySnapshot() {
        var folded = ProjectsWindowSnapshotCache.foldProjectRows(List.of());
        assertThat(folded.summaries()).isEmpty();
        assertThat(folded.meta()).isEmpty();
    }
}
