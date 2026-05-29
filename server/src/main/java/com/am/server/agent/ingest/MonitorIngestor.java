package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.MonitorSnapshotDto;
import com.am.server.agent.security.SignatureContext;

/**
 * 监控目标快照消费者接口
 * 每种 Provider（cursor / claude / codex / ...）实现一份；
 * 通过 supports(type) 决定能否处理。
 * gz
 */
public interface MonitorIngestor {

    boolean supports(String typeCode);

    /**
     * 将 Provider 上报的快照入库（对会话做 upsert，对消息按 external_message_id 去重）
     */
    IngestResult ingest(MonitorSnapshotDto snapshot, SignatureContext context);
}
