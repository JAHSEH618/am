package com.am.server.domain.agent;

/**
 * Agent 告警类型常量（v1.2 + v1.3）
 * gz
 */
public final class AlertType {

    private AlertType() {
    }

    public static final String AGENT_OFFLINE = "AGENT_OFFLINE";
    public static final String VERSION_EXPIRED = "VERSION_EXPIRED";
    public static final String BINARY_HASH_MISMATCH = "BINARY_HASH_MISMATCH";
    public static final String SIGNATURE_INVALID = "SIGNATURE_INVALID";
    public static final String NONCE_REPLAY = "NONCE_REPLAY";
    public static final String DEVICE_CHANGED = "DEVICE_CHANGED";
    public static final String UNKNOWN_PROJECT = "UNKNOWN_PROJECT";
    public static final String MULTI_DEVICE_ONLINE = "MULTI_DEVICE_ONLINE";

    public static final String AI_SESSION_STUCK = "AI_SESSION_STUCK";
    public static final String COST_SPIKE = "COST_SPIKE";
    public static final String MODEL_NOT_ALLOWED = "MODEL_NOT_ALLOWED";
    public static final String TOKEN_TAMPER = "TOKEN_TAMPER";

    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_ERROR = "ERROR";
}
