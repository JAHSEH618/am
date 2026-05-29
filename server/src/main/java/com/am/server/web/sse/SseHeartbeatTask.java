package com.am.server.web.sse;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每 25 秒广播一次 SSE ping，触发死连接的 onError 回调清理 emitter
 * gz
 */
@Component
@RequiredArgsConstructor
public class SseHeartbeatTask {

    private final SseHub hub;

    @Scheduled(fixedRate = 25_000L)
    public void heartbeat() {
        hub.heartbeat();
    }
}
