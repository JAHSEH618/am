package com.am.server.agent.api.dto;

import lombok.Data;

/**
 * 设备状态上报片段
 * 替代 v1.2 心跳中的进程/前台标志
 * gz
 */
@Data
public class DeviceStateDto {

    private String osType;
    private String hostname;
    private String hostHash;
    /** 局域网 IPv4（每次上报刷新，便于运维当下定位机器） */
    private String localIp;
    /** git config --global user.name */
    private String gitUserName;
    /** git config --global user.email */
    private String gitUserEmail;
    /** Cursor 登录邮箱 */
    private String cursorEmail;
    /** Cursor 付费档：free / pro / pro_plus / business / ultra / enterprise */
    private String cursorMembershipType;
    /** Cursor 订阅状态：active / canceled / past_due / trialing */
    private String cursorSubscriptionStatus;
    /** Cursor 注册渠道：Auth_0 / Google / GitHub / Email */
    private String cursorSignupType;
    private String foregroundApp;
    private Integer idleSeconds;
    private Integer battery;
}
