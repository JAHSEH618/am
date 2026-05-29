package com.am.server.agent.ingest;

import com.am.server.agent.api.dto.MonitorSnapshotDto;
import com.am.server.agent.security.SignatureContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 监控快照路由器
 * 根据 type_code 把 MonitorSnapshotDto 派发给对应的 MonitorIngestor
 * 未注册类型只记录警告，不阻断整体上报
 * gz
 */
@Service
@RequiredArgsConstructor
public class MonitorRouter {

    private static final Logger log = LoggerFactory.getLogger(MonitorRouter.class);

    private final List<MonitorIngestor> ingestors;

    public IngestResult dispatch(MonitorSnapshotDto snapshot, SignatureContext ctx) {
        if (snapshot == null || snapshot.getType() == null) {
            return IngestResult.empty();
        }
        for (MonitorIngestor ingestor : ingestors) {
            if (ingestor.supports(snapshot.getType())) {
                return ingestor.ingest(snapshot, ctx);
            }
        }
        log.warn("no ingestor registered for monitor type: {}", snapshot.getType());
        return IngestResult.empty();
    }
}
