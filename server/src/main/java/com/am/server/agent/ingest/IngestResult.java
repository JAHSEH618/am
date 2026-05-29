package com.am.server.agent.ingest;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Provider 入库结果汇总，用于上层决定 work_session 活跃判定与日志输出
 * gz
 */
@Data
@AllArgsConstructor
public class IngestResult {

    private int sessionsTouched;
    private int eventsWritten;
    private int messagesWritten;
    private boolean hasActiveSession;

    public static IngestResult empty() {
        return new IngestResult(0, 0, 0, false);
    }
}
