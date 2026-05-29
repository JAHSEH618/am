package com.am.server.agent.service;

import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.ai.AiSession;
import com.am.server.domain.ai.AiSessionRepository;
import com.am.server.domain.session.WorkSession;
import com.am.server.domain.session.WorkSessionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 工作会话管理服务（v1.3 语义）
 *
 * - online_seconds：每次收到上报，按 (capturedAt - prevSeen) 累加，单次封顶 perReportCapSeconds
 * - active_seconds：当本次上报存在非 idle 且 last_activity 在 activityWindow 内的 ai_session 时同样累加
 * - 跨 180s 间隔 = 新会话；旧会话关闭
 *
 * gz
 */
@Service
@RequiredArgsConstructor
public class WorkSessionService {

    private static final Logger log = LoggerFactory.getLogger(WorkSessionService.class);

    private static final long GAP_SECONDS = 180;

    private final WorkSessionRepository repository;
    private final AiSessionRepository aiSessionRepository;
    private final AgentProperties agentProperties;

    @Transactional
    public void advance(AgentDevice device,
                        LocalDateTime prevSeen,
                        LocalDateTime capturedAt,
                        boolean hasActiveSession) {
        if (capturedAt == null) {
            capturedAt = LocalDateTime.now();
        }

        WorkSession current = repository
                .findFirstByAgentIdAndStatusOrderByStartTimeDesc(
                        device.getAgentId(), WorkSession.STATUS_OPEN)
                .orElse(null);

        long deltaSeconds = prevSeen == null ? 0L
                : Math.max(0L, Duration.between(prevSeen, capturedAt).getSeconds());

        if (deltaSeconds > GAP_SECONDS) {
            if (current != null) {
                current.setStatus(WorkSession.STATUS_CLOSED);
                current.setEndTime(prevSeen);
                repository.save(current);
                log.debug("work session closed by gap: agentId={} duration={}s active={}s",
                        device.getAgentId(), current.getDurationSeconds(), current.getActiveSeconds());
            }
            WorkSession ws = openNew(device, capturedAt);
            attachActiveProject(ws, device.getAgentId());
            repository.save(ws);
            return;
        }

        if (current == null) {
            WorkSession ws = openNew(device, capturedAt);
            attachActiveProject(ws, device.getAgentId());
            repository.save(ws);
            return;
        }

        long credit = Math.min(deltaSeconds, perReportCapSeconds());
        current.setDurationSeconds(nz(current.getDurationSeconds()) + credit);
        if (hasActiveSession) {
            current.setActiveSeconds(nz(current.getActiveSeconds()) + credit);
        }
        attachActiveProject(current, device.getAgentId());
        repository.save(current);
    }

    /** 把该 agent 最近一条活跃 ai_session 的 project / repo / branch 写到 work_session（仅更新非空值）。 */
    private void attachActiveProject(WorkSession ws, String agentId) {
        Optional<AiSession> latest = aiSessionRepository.findFirstByAgentIdOrderByLastActivityDesc(agentId);
        if (latest.isEmpty()) {
            return;
        }
        AiSession s = latest.get();
        if (s.getProjectName() != null) {
            ws.setProjectName(s.getProjectName());
        }
        if (s.getRepoUrl() != null) {
            ws.setRepoUrl(s.getRepoUrl());
        }
        if (s.getGitBranch() != null) {
            ws.setBranchName(s.getGitBranch());
        }
    }

    private WorkSession openNew(AgentDevice device, LocalDateTime capturedAt) {
        WorkSession s = new WorkSession();
        s.setAgentId(device.getAgentId());
        s.setUserCode(device.getUserCode());
        s.setHostHash(device.getHostHash());
        s.setStartTime(capturedAt);
        s.setStatus(WorkSession.STATUS_OPEN);
        s.setDurationSeconds(0L);
        s.setActiveSeconds(0L);
        return s;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private long perReportCapSeconds() {
        return agentProperties != null ? agentProperties.getPerReportCapSeconds() : 120L;
    }
}
