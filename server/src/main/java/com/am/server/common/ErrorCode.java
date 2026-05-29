package com.am.server.common;

/**
 * 业务错误码常量。10000 段为 Agent 安全相关，20000 段为业务校验，50000 段为系统错误
 * gz
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    public static final int INVALID_SIGNATURE = 10001;
    public static final int NONCE_REPLAY = 10002;
    public static final int TIMESTAMP_OUT_OF_WINDOW = 10003;
    public static final int AGENT_NOT_FOUND = 10004;
    public static final int AGENT_VERSION_EXPIRED = 10005;
    public static final int AGENT_BINARY_HASH_MISMATCH = 10006;

    /** v2.0.3 起：管理类 /api/v1/admin/** 端点未携带 / 携带错误的 X-Admin-Token */
    public static final int ADMIN_UNAUTHORIZED = 10101;

    public static final int PARAM_INVALID = 20001;
    public static final int RESOURCE_NOT_FOUND = 20002;
    public static final int OPERATION_NOT_ALLOWED = 20003;

    public static final int INTERNAL_ERROR = 50000;
}
