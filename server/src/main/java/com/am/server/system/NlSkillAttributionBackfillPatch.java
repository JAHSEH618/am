package com.am.server.system;

import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.insight.aggregate.NlSkillAttributionSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * 存量会话的 NL skill 归因一次性回填（原 insight.aggregate.NlSkillAttributionBackfillRunner）。
 *
 * <p>此前每次启动都在 ApplicationReadyEvent 里整表扫 ai_session_message（对 content_text / content_parts_json
 * 做 {@code LOWER(...) LIKE '%skill.md%'}，无索引可用），再逐会话整段重算；而现行 ingest 写入消息时已增量归因
 * （{@link NlSkillAttributionSupport#reconcileSessionFrom}），回填只服务历史数据。现改为 sys_config marker
 * （{@value #MARKER_KEY}）一次性：后台 daemon 线程按 message 主键区间分批找候选会话，逐会话调同一个
 * {@link NlSkillAttributionSupport#reconcileSession}（口径不变）；有会话失败则不写 marker，下次启动重跑（幂等）。
 * 归因规则变更需要重刷存量时，升 marker 版本号。
 * gz
 */
@Configuration
public class NlSkillAttributionBackfillPatch {

    private static final Logger log = LoggerFactory.getLogger(NlSkillAttributionBackfillPatch.class);

    static final String MARKER_KEY = "insight.nl_skill_backfill_v1";

    /** 批间停顿：一次性全表扫，不和 ingest / 页面查询抢 IO。 */
    private static final long PAUSE_MS = 20L;

    /**
     * 晚于 SlashAnnotationBackfillPatch（@Order(880)，同步重刷 command/skill 行、保留 nl_skill 行），
     * 先于 CapabilityDailyBackfillPatch（@Order(900)）起线程。
     */
    @Bean
    @Order(890)
    ApplicationRunner backfillNlSkillAttribution(DataSource dataSource, AiSessionMessageRepository messageRepository) {
        return args -> {
            long[] bounds;
            try {
                if (OneShotBackfillSupport.markerExists(dataSource, MARKER_KEY)) {
                    return;
                }
                bounds = OneShotBackfillSupport.idBounds(dataSource, "ai_session_message");
                if (bounds == null) {
                    writeMarker(dataSource);
                    return;
                }
            } catch (SQLException e) {
                log.warn("NL skill attribution backfill skipped: {}", e.getMessage());
                return;
            }
            Thread t = new Thread(() -> run(dataSource, messageRepository, bounds), "nl-skill-backfill");
            t.setDaemon(true);
            t.start();
        };
    }

    private static void run(DataSource dataSource, AiSessionMessageRepository messageRepository, long[] bounds) {
        Result r;
        try {
            r = backfill(messageRepository, bounds, PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            log.warn("NL skill attribution backfill aborted (will retry next boot): {}", e.toString());
            return;
        }
        if (r.failedSessions() > 0) {
            log.warn("NL skill attribution backfill incomplete: sessions={} failed={}; marker not written, will retry on next boot",
                    r.scannedSessions(), r.failedSessions());
            return;
        }
        try {
            writeMarker(dataSource);
        } catch (SQLException e) {
            log.warn("NL skill attribution backfill marker write failed: {}", e.getMessage());
            return;
        }
        log.info("NL skill attribution backfill done: {} user messages updated across {} sessions (scanned {} sessions)",
                r.updatedUserMessages(), r.updatedSessions(), r.scannedSessions());
    }

    record Result(int scannedSessions, int updatedSessions, int updatedUserMessages, int failedSessions) {}

    static Result backfill(AiSessionMessageRepository messageRepository, long[] bounds, long pauseMs)
            throws InterruptedException {
        Set<Long> done = new HashSet<>();
        int sessions = 0;
        int userMsgs = 0;
        int failed = 0;
        for (long[] range : OneShotBackfillSupport.ranges(bounds[0], bounds[1], OneShotBackfillSupport.CHILD_ID_STEP)) {
            for (Long sid : messageRepository.findSessionIdsWithSkillMdToolReadsInIdRange(range[0], range[1])) {
                // 同一会话的 SKILL.md 读可能跨多个区间：整段重算一次即可
                if (sid == null || !done.add(sid)) {
                    continue;
                }
                try {
                    int n = NlSkillAttributionSupport.reconcileSession(messageRepository, sid);
                    if (n > 0) {
                        sessions++;
                        userMsgs += n;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("NL skill attribution backfill failed session={} err={}", sid, e.toString());
                }
            }
            if (pauseMs > 0) {
                Thread.sleep(pauseMs);
            }
        }
        return new Result(done.size(), sessions, userMsgs, failed);
    }

    private static void writeMarker(DataSource dataSource) throws SQLException {
        OneShotBackfillSupport.writeMarker(dataSource, MARKER_KEY, "存量会话 NL skill 归因回填完成标记");
    }
}
