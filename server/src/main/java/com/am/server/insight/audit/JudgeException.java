package com.am.server.insight.audit;

/**
 * JudgeClient 调用失败 / 返回不可解析时抛出。
 *
 * <p>orchestrator 会捕获后做退避重试（默认 3 次），全部失败该 session 标记审计跳过，
 * 不阻塞整个报告生成。
 * gz
 */
public class JudgeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JudgeException(String message) {
        super(message);
    }

    public JudgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
