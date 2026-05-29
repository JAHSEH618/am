package com.am.server.agent.service;

import com.am.server.agent.api.dto.AgentReportRequest;
import com.am.server.agent.api.dto.DeviceStateDto;
import com.am.server.agent.api.dto.MonitorAgentPolicyDto;
import com.am.server.agent.api.dto.MonitorSnapshotDto;
import com.am.server.agent.ingest.IngestResult;
import com.am.server.agent.ingest.MonitorRouter;
import com.am.server.agent.security.SignatureContext;
import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.system.ActiveTargetTypesProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 通用上报处理服务
 * 流程：身份对账 → 设备状态更新 → 监控快照分发 → 工作会话推进
 * gz
 */
@Service
@RequiredArgsConstructor
public class AgentReportService {

    private static final Logger log = LoggerFactory.getLogger(AgentReportService.class);

    private final AgentDeviceRepository deviceRepository;
    private final MonitorRouter monitorRouter;
    private final WorkSessionService workSessionService;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;

    @Autowired
    @Lazy
    private AgentReportService self;

    public ReportSummary handle(AgentReportRequest request, SignatureContext ctx) {
        if (request.getAgentId() != null && !request.getAgentId().equals(ctx.getAgentId())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "body.agent_id mismatch with X-Agent-Id");
        }

        AgentDevice device = deviceRepository.findByAgentId(ctx.getAgentId())
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_NOT_FOUND,
                        "agent not found: " + ctx.getAgentId()));

        LocalDateTime capturedAt = request.getCapturedAt() != null
                ? request.getCapturedAt()
                : LocalDateTime.now();
        LocalDateTime prevSeen = device.getLastSeenTime();

        self.persistDeviceHeartbeat(device, request, capturedAt);

        boolean anyActive = false;
        int totalSessions = 0;
        int totalEvents = 0;
        int totalMessages = 0;

        List<MonitorSnapshotDto> monitors = request.getMonitors();
        if (monitors != null) {
            for (MonitorSnapshotDto snapshot : monitors) {
                IngestResult r = monitorRouter.dispatch(snapshot, ctx);
                anyActive |= r.isHasActiveSession();
                totalSessions += r.getSessionsTouched();
                totalEvents += r.getEventsWritten();
                totalMessages += r.getMessagesWritten();
            }
        }

        workSessionService.advance(device, prevSeen, capturedAt, anyActive);

        log.debug("report accepted: agentId={} sessions={} events={} messages={} active={}",
                ctx.getAgentId(), totalSessions, totalEvents, totalMessages, anyActive);

        return new ReportSummary(
                totalSessions,
                totalEvents,
                totalMessages,
                anyActive,
                activeTargetTypesProvider.snapshotAgentPolicy());
    }

    public record ReportSummary(
            int sessions,
            int events,
            int messages,
            boolean active,
            MonitorAgentPolicyDto monitorPolicy) {
    }

    /** 设备心跳单独短事务；monitor ingest 按会话独立提交，避免 bootstrap 大包长时间占锁。 */
    @Transactional
    void persistDeviceHeartbeat(AgentDevice device, AgentReportRequest request, LocalDateTime capturedAt) {
        if (request.getAgentVersion() != null) {
            device.setAgentVersion(request.getAgentVersion());
        }
        if (request.getBinaryHash() != null) {
            device.setBinaryHash(request.getBinaryHash());
        }
        if (request.getDeviceState() != null) {
            DeviceStateDto state = request.getDeviceState();
            if (state.getOsType() != null) {
                device.setOsType(state.getOsType());
            }
            if (state.getHostname() != null) {
                device.setHostname(state.getHostname());
            }
            // 仅在 agent 真正上报到非空值时覆盖；保持 register 时写入的旧值，避免重启时被空值清掉
            if (notBlank(state.getLocalIp())) {
                device.setLocalIp(state.getLocalIp());
            }
            if (notBlank(state.getGitUserName())) {
                device.setGitUserName(state.getGitUserName());
            }
            if (notBlank(state.getGitUserEmail())) {
                device.setGitUserEmail(state.getGitUserEmail());
            }
            if (notBlank(state.getCursorEmail())) {
                device.setCursorEmail(state.getCursorEmail());
            }
            if (notBlank(state.getCursorMembershipType())) {
                device.setCursorMembershipType(state.getCursorMembershipType());
            }
            if (notBlank(state.getCursorSubscriptionStatus())) {
                device.setCursorSubscriptionStatus(state.getCursorSubscriptionStatus());
            }
            if (notBlank(state.getCursorSignupType())) {
                device.setCursorSignupType(state.getCursorSignupType());
            }
        }
        device.setLastSeenTime(capturedAt);
        deviceRepository.save(device);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
