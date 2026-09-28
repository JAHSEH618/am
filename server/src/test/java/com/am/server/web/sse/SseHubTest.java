package com.am.server.web.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SseHubTest {

    private SseHub hub;

    @AfterEach
    void tearDown() {
        if (hub != null) {
            hub.shutdown();
        }
    }

    @Test
    void publish_neverBlocksCallerOnStalledSubscriber() throws Exception {
        // 浏览器 socket 写不动：send 一直挂着。publish 的调用方（ingest 线程）不得被拖住，溢出的帧丢弃并计数
        CountDownLatch release = new CountDownLatch(1);
        hub = hubWith(frame -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        hub.subscribe();

        long t0 = System.nanoTime();
        for (int i = 0; i < SseHub.SEND_QUEUE_CAPACITY + 100; i++) {
            hub.publish("session_changed", Map.of("i", i));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(elapsedMs).isLessThan(2_000);
        assertThat(hub.droppedFrames()).isGreaterThanOrEqualTo(99);
        release.countDown();
    }

    @Test
    void publish_deliversInOrderOnSenderThreadAndHeartbeatGoesSamePath() throws Exception {
        List<String> frames = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(3);
        hub = hubWith(frame -> {
            frames.add(frame);
            threads.add(Thread.currentThread().getName());
            delivered.countDown();
            return null;
        });
        hub.subscribe();

        hub.publish("session_event", Map.of("k", 1));
        hub.publish("session_changed", Map.of("k", 2));
        hub.heartbeat();

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(frames.get(0)).contains("event:session_event");
        assertThat(frames.get(1)).contains("event:session_changed");
        assertThat(frames.get(2)).contains("event:ping");
        assertThat(threads).containsOnly("sse-sender");
        assertThat(hub.droppedFrames()).isZero();
    }

    @Test
    void publish_evictsSubscriberWhoseWriteFails() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        hub = hubWith(frame -> {
            attempted.countDown();
            return new IOException("broken pipe");
        });
        hub.subscribe();
        assertThat(hub.size()).isEqualTo(1);

        hub.publish("session_changed", Map.of("k", 1));

        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 50 && hub.size() > 0; i++) {
            Thread.sleep(20);
        }
        assertThat(hub.size()).isZero();
    }

    /**
     * 订阅时的 hello 帧同步放行；之后每帧交给 {@code onFrame}（入参是帧文本，返回非 null 即作为 send 抛出的异常）。
     */
    private static SseHub hubWith(Function<String, IOException> onFrame) {
        return new SseHub(new ObjectMapper()) {
            @Override
            SseEmitter newEmitter() {
                return new SseEmitter(0L) {
                    private final AtomicInteger calls = new AtomicInteger();

                    @Override
                    public void send(SseEventBuilder builder) throws IOException {
                        if (calls.getAndIncrement() == 0) {
                            return;
                        }
                        String frame = builder.build().stream()
                                .map(d -> String.valueOf(d.getData()))
                                .collect(Collectors.joining());
                        IOException failure = onFrame.apply(frame);
                        if (failure != null) {
                            throw failure;
                        }
                    }
                };
            }
        };
    }
}
