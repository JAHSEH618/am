package com.am.server.web.support;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 慢接口清单（性能治理 §5.3「测量先行」）：ApiTimingFilter 把超阈值请求记进本环形缓冲，
 * PerfController 暴露给管理员——拿真实 Top 慢接口，不凭体感修。
 * 内存态、重启即清（测量数据无需持久化）；容量固定防涨。
 * gz
 */
@Component
public class SlowApiRecorder {

    private static final int CAPACITY = 200;

    /** 一条慢请求记录。 */
    public record SlowApiEntry(LocalDateTime time, String method, String path,
                               String query, long durationMs, int status) {}

    private final ArrayDeque<SlowApiEntry> buffer = new ArrayDeque<>(CAPACITY);

    public synchronized void record(SlowApiEntry entry) {
        if (buffer.size() >= CAPACITY) {
            buffer.pollFirst();
        }
        buffer.addLast(entry);
    }

    /** 快照，最新在前。 */
    public synchronized List<SlowApiEntry> snapshot() {
        List<SlowApiEntry> out = new ArrayList<>(buffer);
        java.util.Collections.reverse(out);
        return out;
    }

    public synchronized void clear() {
        buffer.clear();
    }
}
