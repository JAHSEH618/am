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

    /**
     * agent 通道上一切「服务过载 / 暂时不可用」类失败（HTTP 503）：并发舱壁 / 字节预算已满、启动期补丁尚未跑完（未就绪）、
     * 同 agent 已有重请求在途、连接池取不到连接、锁等待超时 / 死锁、事务或语句超时……
     * 客户端不必落 outbox，下个 tick 重报即可（{@code Retry-After} 给退让提示）。
     */
    public static final int SERVER_BUSY = 50301;

    /**
     * agent 请求体超过服务端上限（HTTP 413）：线上字节 &gt; {@code aiwatch.agent.max-body-bytes}，或 gzip 解压后 &gt;
     * {@code max-decoded-body-bytes} / 压缩比超限。与 503 不同，这是<b>确定性失败</b>——原样重发永远 413，
     * 客户端应缩小批量 / 丢弃对应 outbox 文件，而不是重试。
     */
    public static final int PAYLOAD_TOO_LARGE = 41301;

    /** 控制台查询超过服务端执行上限被 MySQL 中止（MAX_EXECUTION_TIME）：提示缩小时间窗口重试。 */
    public static final int QUERY_TIMEOUT = 50401;
}
