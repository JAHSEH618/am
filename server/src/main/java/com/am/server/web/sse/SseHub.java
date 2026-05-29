package com.am.server.web.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内 SSE 广播中心
 *
 * 为多个并发的 dashboard 浏览器维护 SseEmitter 集合，
 * 后端事件通过 publish(...) 推送给所有订阅者。失败连接会被自动剔除。
 *
 * gz
 */
@Component
@RequiredArgsConstructor
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final ObjectMapper objectMapper;

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /** SSE 默认超时（毫秒）。设置为很大的值，依赖前端断开/心跳触发清理。 */
    private static final long EMITTER_TIMEOUT_MS = 60L * 60L * 1000L;

    public SseEmitter subscribe() {
        long id = sequence.incrementAndGet();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(id));
        emitter.onTimeout(() -> emitters.remove(id));
        emitter.onError(t -> emitters.remove(id));
        emitters.put(id, emitter);
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of("subscriberId", id)));
        } catch (IOException e) {
            emitters.remove(id);
        }
        log.debug("sse subscribe: id={} total={}", id, emitters.size());
        return emitter;
    }

    /**
     * 把事件广播给所有订阅者。为避免单个慢消费者拖慢整体，仅 best-effort send。
     */
    public void publish(String name, Object payload) {
        if (emitters.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("sse marshal failed: {}", e.getMessage());
            return;
        }
        Iterator<Map.Entry<Long, SseEmitter>> it = emitters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, SseEmitter> entry = it.next();
            try {
                entry.getValue().send(SseEmitter.event().name(name).data(json));
            } catch (Exception e) {
                it.remove();
            }
        }
    }

    /**
     * 心跳：定期发送 ping，触发 emitter 自身的 IO 错误回调以清理已断开的连接。
     */
    public void heartbeat() {
        publish("ping", Map.of("ts", System.currentTimeMillis()));
    }

    public int size() {
        return emitters.size();
    }
}
