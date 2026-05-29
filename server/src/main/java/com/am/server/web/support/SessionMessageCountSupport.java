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
