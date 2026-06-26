package com.am.server.agent.service;

import com.am.server.agent.api.AgentRegisterRequest;
import com.am.server.agent.api.AgentRegisterResponse;
import com.am.server.common.BizException;
import com.am.server.common.ErrorCode;
import com.am.server.config.AgentProperties;
import com.am.server.domain.agent.AgentDevice;
import com.am.server.domain.agent.AgentDeviceRepository;
import com.am.server.domain.employee.Employee;
import com.am.server.domain.employee.EmployeeRepository;
import com.am.server.service.EmployeeDisplayService;
import com.am.server.system.ActiveTargetTypesProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Agent 注册服务
 * 幂等：相同 (user_code, host_hash) 重复调用复用旧 agent_id 与 agent_secret，仅刷新元信息。
 *
 * <p>v2.3 起支持员工自助注册：
 * 安装命令携带 user_name + department，employee 表无该 user_code 时自动创建一条 ACTIVE 记录；
 * 已存在的员工不会被本次请求覆盖（保护 HR 已录入的姓名 / 部门）。
 *
 * gz
 */
@Service
@RequiredArgsConstructor
public class AgentRegisterService {

    private static final Logger log = LoggerFactory.getLogger(AgentRegisterService.class);

    /**
     * 上报间隔（空闲基线 / 活跃快报）改由 {@link AgentProperties} 承载，ops 可经
     * {@code aiwatch.agent.report-interval-ms} / {@code active-report-interval-ms} 调而无需重新编译。
     * register 与每次 /report 响应都下发当前值，客户端动态 honor（见 reporter.go 的 mergeReportCadence）。
     */
    private static final long DEFAULT_TIMESTAMP_WINDOW_MS = 300_000L;

    private final EmployeeRepository employeeRepository;
    private final AgentDeviceRepository deviceRepository;
    private final EmployeeDisplayService employeeDisplayService;
    private final ActiveTargetTypesProvider activeTargetTypesProvider;
    private final AgentProperties agentProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public AgentRegisterResponse register(AgentRegisterRequest req) {
        Employee employee = employeeRepository.findByUserCode(req.getUserCode()).orElse(null);
        if (employee == null) {
            // 自助注册：员工首次安装时由命令行携带姓名/部门创建 employee 记录
            String name = trimOrNull(req.getUserName());
            if (name == null) {
                throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                        "employee not found and user_name missing: " + req.getUserCode()
                                + "（请在安装命令里加上 --user-name / --department，或联系管理员录入员工信息）");
            }
            employee = new Employee();
            employee.setUserCode(req.getUserCode());
            employee.setUserName(name);
            employee.setDepartment(trimOrNull(req.getDepartment()));
            employee.setStatus(Employee.STATUS_ACTIVE);
            employee = employeeRepository.save(employee);
            log.info("employee self-registered: userCode={} userName={} department={}",
                    employee.getUserCode(), employee.getUserName(), employee.getDepartment());
            // 让 EmployeeDisplayService 立刻看到新员工，画像 / 会话列表里就能展示"姓名|工号"
            employeeDisplayService.invalidate();
        } else if (!Employee.STATUS_ACTIVE.equals(employee.getStatus())) {
            throw new BizException(ErrorCode.OPERATION_NOT_ALLOWED,
                    "employee inactive: " + req.getUserCode());
        }
        // 注意：员工已存在时不会用 req.userName/department 覆盖已有数据——HR 校准的姓名/部门是事实来源

        AgentDevice device = deviceRepository
                .findByUserCodeAndHostHash(req.getUserCode(), req.getHostHash())
                .orElse(null);

        if (device == null) {
            device = new AgentDevice();
            device.setAgentId("agent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            device.setUserCode(req.getUserCode());
            device.setHostHash(req.getHostHash());
            device.setAgentSecret(generateSecret());
            device.setStatus(AgentDevice.STATUS_ACTIVE);
            log.info("agent registered: agentId={} userCode={} host={}",
                    device.getAgentId(), req.getUserCode(), req.getHostname());
        } else {
            device.setStatus(AgentDevice.STATUS_ACTIVE);
            log.info("agent re-registered: agentId={} userCode={}",
                    device.getAgentId(), req.getUserCode());
        }
        device.setHostname(req.getHostname());
        device.setOsType(req.getOsType());
        device.setAgentVersion(req.getAgentVersion());
        device.setBinaryHash(req.getBinaryHash());
        if (req.getLocalIp() != null) {
            device.setLocalIp(req.getLocalIp());
        }
        if (req.getGitUserName() != null) {
            device.setGitUserName(req.getGitUserName());
        }
        if (req.getGitUserEmail() != null) {
            device.setGitUserEmail(req.getGitUserEmail());
        }
        device.setLastSeenTime(LocalDateTime.now());
        deviceRepository.save(device);

        return AgentRegisterResponse.builder()
                .agentId(device.getAgentId())
                .agentSecret(device.getAgentSecret())
                .reportIntervalMs(agentProperties.getReportIntervalMs())
                .activeReportIntervalMs(agentProperties.getActiveReportIntervalMs())
                .timestampWindowMs(DEFAULT_TIMESTAMP_WINDOW_MS)
                .monitorPolicy(activeTargetTypesProvider.snapshotAgentPolicy())
                .build();
    }

    private String generateSecret() {
        byte[] buf = new byte[32];
        secureRandom.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String trimOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
