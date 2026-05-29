package com.am.server.domain.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 工作会话 Repository
 * gz
 */
public interface WorkSessionRepository extends JpaRepository<WorkSession, Long> {

    Optional<WorkSession> findFirstByAgentIdAndStatusOrderByStartTimeDesc(String agentId, String status);

    /**
     * 批量：每个 agent_id 取 status 匹配且 start_time 最新的一条 OPEN 会话（大盘 online 表用）。
     */
    @Query(value = """
            SELECT w.* FROM work_session w
            INNER JOIN (
                SELECT agent_id, MAX(start_time) AS max_start
                FROM work_session
                WHERE agent_id IN (:agentIds) AND status = :status
                GROUP BY agent_id
            ) t ON w.agent_id = t.agent_id AND w.start_time = t.max_start AND w.status = :status
            """, nativeQuery = true)
    List<WorkSession> findLatestOpenByAgentIdIn(
            @org.springframework.data.repository.query.Param("agentIds") Collection<String> agentIds,
            @org.springframework.data.repository.query.Param("status") String status);

    List<WorkSession> findByStatusAndUpdatedTimeBefore(String status, LocalDateTime before);

    /**
     * 大盘：今日总在线 / 总活跃秒数。
     *
     * 取所有"今天有更新过"的 work_session 累计值，
     * 用 updatedTime 而非 startTime，以覆盖跨天延续的会话。
     *
     * 返回结果按 [duration, active] 顺序。返回 List 是为了避免 Spring Data
     * 对 native Object[] 单行结果做错误的拆包。
     */
    @Query("""
        SELECT COALESCE(SUM(w.durationSeconds), 0),
               COALESCE(SUM(w.activeSeconds), 0)
        FROM WorkSession w
        WHERE w.updatedTime >= :since
        """)
    List<Object[]> aggregateTodaySecondsRaw(LocalDateTime since);

    default Object[] aggregateTodaySeconds(LocalDateTime since) {
        List<Object[]> rows = aggregateTodaySecondsRaw(since);
        if (rows == null || rows.isEmpty()) {
            return new Object[]{0L, 0L};
        }
        return rows.get(0);
    }
}
