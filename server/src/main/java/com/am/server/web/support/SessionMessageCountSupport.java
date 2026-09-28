package com.am.server.web.support;

import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.web.dto.AiSessionDto;

import java.util.List;
import java.util.Locale;

/**
 * 会话消息计数对齐：Agent 快照的 user/assistant 可能与 DB 已入库行数不一致
 * （Cursor fast path 缓存、增量回填等），详情页与列表以 message 表为准。
 */
public final class SessionMessageCountSupport {

    private SessionMessageCountSupport() {
    }

    public record RoleCounts(int user, int assistant, int subagent, int conversation) {
    }

    public static RoleCounts loadRoleCounts(AiSessionMessageRepository repo, Long sessionId) {
        if (sessionId == null) {
            return new RoleCounts(0, 0, 0, 0);
        }
        int user = 0;
        int assistant = 0;
        int subagent = 0;
        List<Object[]> rows = repo.countGroupedByRoleForSession(sessionId);
        if (rows != null) {
            for (Object[] row : rows) {
                if (row == null || row.length < 2 || row[0] == null) {
                    continue;
                }
                String role = row[0].toString().toLowerCase(Locale.ROOT);
                int cnt = ((Number) row[1]).intValue();
                switch (role) {
                    case "user" -> user = cnt;
                    case "assistant" -> assistant = cnt;
                    case "subagent" -> subagent = cnt;
                    default -> { }
                }
            }
        }
        return new RoleCounts(user, assistant, subagent, user + assistant + subagent);
    }

    /** 已入库消息存在时，将会话级 user/assistant/total 与 message 表对齐。 */
    public static void reconcileSessionEntity(AiSession session, AiSessionMessageRepository repo) {
        if (session == null || session.getId() == null) {
            return;
        }
        Integer stored = repo.countByAiSessionId(session.getId());
        if (stored == null || stored <= 0) {
            return;
        }
        RoleCounts c = loadRoleCounts(repo, session.getId());
        session.setUserMessages(c.user());
        session.setAssistantMessages(c.assistant());
        session.setTotalMessages(c.user() + c.assistant());
    }

    /** ingest 用 Agent 快照覆写前，会话上的 user / assistant / total 计数（即上一拍对齐后的值）。 */
    public record SessionCounters(Integer user, Integer assistant, Integer total) {
        public static SessionCounters of(AiSession s) {
            return new SessionCounters(s.getUserMessages(), s.getAssistantMessages(), s.getTotalMessages());
        }

        boolean complete() {
            return user != null && assistant != null && total != null;
        }
    }

    /**
     * ingest 路径的计数对齐，结果与 {@link #reconcileSessionEntity} 一致（库里有消息 → 以 message 表为准；
     * 没有 → 保留 Agent 快照），但不再每拍对每个会话把 message 表数两遍（COUNT + 不走覆盖索引的
     * GROUP BY LOWER(role)）——每 tick 里绝大多数会话是"没有新消息"的：
     * <ul>
     *   <li>本拍写入了消息：一次按 role 分组计数，total 取 user + assistant 之和；</li>
     *   <li>本拍没写、库里已有消息：插入 message 行的只有 ingest，而每次插入都在同一事务里按 message 表对齐过
     *       计数，所以覆写前的值就是现在的表内计数——直接恢复，零查询（覆写前计数不全时退回查表）；</li>
     *   <li>库里没有消息：保留 upsert 写入的快照值。</li>
     * </ul>
     *
     * @param storedBeforeWrite 本拍写入前该会话的 message 行数（新会话为 0）
     * @param written           本拍新插入的 message 行数
     * @param before            upsert 覆写前的计数；新会话传 null
     */
    public static void reconcileAfterIngest(AiSession session, AiSessionMessageRepository repo,
                                            int storedBeforeWrite, int written, SessionCounters before) {
        if (session == null || session.getId() == null) {
            return;
        }
        if (written <= 0 && storedBeforeWrite <= 0) {
            return;
        }
        if (written <= 0 && before != null && before.complete()) {
            session.setUserMessages(before.user());
            session.setAssistantMessages(before.assistant());
            session.setTotalMessages(before.total());
            return;
        }
        RoleCounts c = loadRoleCounts(repo, session.getId());
        session.setUserMessages(c.user());
        session.setAssistantMessages(c.assistant());
        session.setTotalMessages(c.user() + c.assistant());
    }

    public static void attachStoredRoleCounts(AiSessionDto dto, AiSession session, AiSessionMessageRepository repo) {
        if (dto == null || session == null || session.getId() == null) {
            return;
        }
        Integer stored = dto.getStoredMessageCount();
        if (stored == null || stored <= 0) {
            return;
        }
        RoleCounts c = loadRoleCounts(repo, session.getId());
        dto.setStoredUserMessages(c.user());
        dto.setStoredAssistantMessages(c.assistant());
        dto.setStoredConversationCount(c.conversation());
        // 详情页主字段与 DB 对齐，避免 Agent 快照（Cursor fast path 缓存）覆盖展示。
        dto.setUserMessages(c.user());
        dto.setAssistantMessages(c.assistant());
        dto.setTotalMessages(c.user() + c.assistant());
    }
}
