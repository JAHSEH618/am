package com.am.server.domain.ai;

/**
 * AI 会话活动状态枚举（10 态，对齐 lazyagent ResolveActivity）
 * 数据库以 String 存储，便于未来扩展无需改 enum 顺序
 * gz
 */
public enum AiSessionStatus {

    IDLE("idle"),
    WAITING("waiting"),
    THINKING("thinking"),
    COMPACTING("compacting"),
    READING("reading"),
    WRITING("writing"),
    RUNNING("running"),
    SEARCHING("searching"),
    BROWSING("browsing"),
    SPAWNING("spawning");

    private final String code;

    AiSessionStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static AiSessionStatus of(String code) {
        if (code == null) {
            return IDLE;
        }
        for (AiSessionStatus s : values()) {
            if (s.code.equalsIgnoreCase(code)) {
                return s;
            }
        }
        return IDLE;
    }
}
