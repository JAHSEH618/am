package com.am.server.web.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内 SSE 广播中心
 *
 * 为多个并发的 dashboard 浏览器维护 SseEmitter 集合，
 * 后端事件通过 publish(...) 推送给所有订阅者。失败连接会被自动剔除。
 *
 * <p>publish 只在调用线程上序列化，逐连接的 socket 写交给单个 {@code sse-sender} 线程：调用方是 ingest
 * 线程（占着舱壁名额和 DB 连接），此前同步逐个 send，一个卡住的浏览器就能把 ingest 线程挂在 socket 写上。
 * 发送队列有界（{@value #SEND_QUEUE_CAPACITY} 帧），满了直接丢弃并计数——实时推送本就 best-effort，
 * 前端会在下一帧 / 刷新时追上。
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

    /** 待发帧上限：单帧是已序列化的一条事件，满了说明某个连接写不动，继续堆只会吃堆。 */
    static final int SEND_QUEUE_CAPACITY = 256;

    /** 丢帧 debug 日志最小间隔，避免持续拥塞时刷屏；总数见 {@link #droppedFrames()}。 */
    private static final long DROP_LOG_INTERVAL_MS = 10_000L;

    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "sse-sender");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastDropLogAt = new AtomicLong();

    public SseEmitter subscribe() {
        long id = sequence.incrementAndGet();
        SseEmitter emitter = newEmitter();
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

    /** 测试缝：替换 emitter 实现。 */
    SseEmitter newEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MS);
    }

    /**
     * 把事件广播给所有订阅者。调用线程只做序列化 + 入队，从不碰 socket；队列满则丢弃本帧。
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
        try {
            sender.execute(() -> fanOut(name, json));
        } catch (RejectedExecutionException e) {
            onDropped(name);
        }
    }

    /** 在 sse-sender 线程上逐连接发送；写失败的连接剔除。 */
    private void fanOut(String name, String json) {
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

    private void onDropped(String name) {
        long total = dropped.incrementAndGet();
        if (sender.isShutdown()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastDropLogAt.get();
        if (now - last >= DROP_LOG_INTERVAL_MS && lastDropLogAt.compareAndSet(last, now)) {
            log.debug("sse send queue full, frame dropped: name={} droppedTotal={} subscribers={}",
                    name, total, emitters.size());
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

    /** 自启动以来因发送队列满（或已关闭）而丢弃的帧数。 */
    long droppedFrames() {
        return dropped.get();
    }

    @PreDestroy
    void shutdown() {
        sender.shutdown();
        try {
            if (!sender.awaitTermination(2, TimeUnit.SECONDS)) {
                sender.shutdownNow();
            }
        } catch (InterruptedException e) {
            sender.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
