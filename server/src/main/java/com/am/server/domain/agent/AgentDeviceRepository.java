package com.am.server.domain.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Agent 设备 Repository
 * gz
 */
public interface AgentDeviceRepository extends JpaRepository<AgentDevice, Long> {

    Optional<AgentDevice> findByAgentId(String agentId);

    Optional<AgentDevice> findByUserCodeAndHostHash(String userCode, String hostHash);

    List<AgentDevice> findByStatusAndLastSeenTimeAfter(String status, LocalDateTime since);

    long countByStatusAndLastSeenTimeAfter(String status, LocalDateTime since);

    long countByStatus(String status);

    /**
     * 只更新“离线提醒邮件已发”这一个告警列，不碰 {@code last_seen_time} 等其它列。
     *
     * <p>离线告警任务先按 last_seen 筛出设备、逐台发信（SMTP 慢 I/O，一批可能数十秒），最后回写发信时间。
     * 此前回写用整行 {@code save(device)}：merge 会把<b>筛选时刻</b>读到的 last_seen / agent_version 等全部列写回，
     * 若设备恰好在发信期间恢复上报，刚更新的 {@code last_seen} 被旧值覆盖，设备又被判成离线、再收一封提醒。
     *
     * <p>按 agent_id（唯一）定位；{@code flushAutomatically} 与 {@code clearAutomatically} <b>必须成对</b>
     * （见根 CLAUDE.md：只开 clear 会把持久化上下文里尚未 flush 的写入静默丢掉）。
     *
     * @return 受影响行数（0 = 设备已被删除）
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE AgentDevice d SET d.lastOfflineEmailTime = :sentAt WHERE d.agentId = :agentId")
    int markOfflineEmailSent(@Param("agentId") String agentId, @Param("sentAt") LocalDateTime sentAt);

    /**
     * ACTIVE 设备且心跳早于在线窗口上沿（含从未上报 last_seen=null）——与 {@link #findByStatusAndLastSeenTimeAfter}
     * 互斥覆盖全量 ACTIVE 台账。
     */
    @Query("""
            SELECT d FROM AgentDevice d
            WHERE d.status = :status
              AND (d.lastSeenTime IS NULL OR d.lastSeenTime <= :cutoff)
            ORDER BY d.lastSeenTime ASC NULLS FIRST""")
    List<AgentDevice> findByStatusAndOfflineBefore(
            @Param("status") String status,
            @Param("cutoff") LocalDateTime cutoff);
}
