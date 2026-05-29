package com.am.server.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent 注册请求 DTO
 * gz
 */
@Data
public class AgentRegisterRequest {

    @NotBlank
    @Size(max = 64)
    private String userCode;

    /**
     * 员工自助注册时由安装命令携带（v2.3 起）：employee 不存在则按此创建一条 ACTIVE 记录。
     * 已存在的员工不会被此字段覆盖（保护 HR 已录入信息）。
     */
    @Size(max = 64)
    private String userName;

    /**
     * 部门，自助注册场景写入 employee.department。已存在员工不覆盖。
     */
    @Size(max = 128)
    private String department;

    @NotBlank
    @Size(max = 128)
    private String hostHash;

    @Size(max = 128)
    private String hostname;

    @Size(max = 32)
    private String osType;

    @Size(max = 32)
    private String agentVersion;

    @Size(max = 128)
    private String binaryHash;

    /** 局域网 IPv4，注册时上报，方便管理员快速定位设备 */
    @Size(max = 64)
    private String localIp;

    /** git config --global user.name */
    @Size(max = 128)
    private String gitUserName;

    /** git config --global user.email */
    @Size(max = 128)
    private String gitUserEmail;
}
