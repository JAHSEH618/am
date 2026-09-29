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
 * {@link NlSkillAttributionSupport#reconcileSession}（口径不变）。归因规则变更需要重刷存量时，升 marker 版本号。
 *
 * <p><b>可续跑 + 容错</b>（骨架见 {@link ResumableBackfill}）：区间游标落 {@code insight.nl_skill_backfill_v1.progress}，
 * 重启从游标续跑；单会话失败记 WARN + 记入 {@code .failed}、跳过继续（重算幂等）；整轮跑完写完成标记；
 * 之后每次启动只重试失败会话，最多 {@value ResumableBackfill#MAX_RETRY_ATTEMPTS} 次。
 * 此前“一个会话失败就不写标记、每次重启整表重扫”。区间级查询失败（库不可用）仍中止本轮，下次从游标续跑。
 * gz
 */
@Configuration
public class NlSkillAttributionBackfillPatch {

    private static final Logger log = LoggerFactory.getLogger(NlSkillAttributionBackfillPatch.class);

    static final String MARKER_KEY = "insight.nl_skill_backfill_v1";

    static final String MARKER_DESCRIPTION = "存量会话 NL skill 归因回填完成标记";

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
            ResumableBackfill state = new ResumableBackfill(
                    ResumableBackfill.Store.jdbc(dataSource), MARKER_KEY, MARKER_DESCRIPTION);
            try {
                if (!state.hasPendingWork()) {
                    return;
                }
            } catch (SQLException e) {
                log.warn("NL skill attribution backfill skipped: {}", e.getMessage());
                return;
            }
            Thread t = new Thread(() -> run(dataSource, state, messageRepository), "nl-skill-backfill");
            t.setDaemon(true);
            t.start();
        };
    }

    private static void run(DataSource dataSource, ResumableBackfill state, AiSessionMessageRepository messageRepository) {
        try {
            if (state.isDone()) {
                ResumableBackfill.RetryResult r = state.retryFailed(
                        label -> NlSkillAttributionSupport.reconcileSession(messageRepository, Long.parseLong(label)));
                if (r.retried() > 0) {
                    log.info("NL skill attribution backfill retry: retried={} recovered={} remaining={}",
                            r.retried(), r.recovered(), r.remaining());
                }
                return;
            }
            long[] bounds = OneShotBackfillSupport.idBounds(dataSource, "ai_session_message");
            if (bounds == null) {
                state.loadFailed();
                state.complete();
                return;
            }
            Result r = backfill(state, messageRepository, bounds, PAUSE_MS);
            log.info("NL skill attribution backfill {}: {} user messages updated across {} sessions "
                            + "(scanned {} sessions, failed {})",
                    r.status().name().toLowerCase(), r.updatedUserMessages(), r.updatedSessions(),
                    r.scannedSessions(), r.failedSessions());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("NL skill attribution backfill aborted (will resume next boot): {}", e.toString());
        }
    }

    record Result(ResumableBackfill.PassStatus status, int scannedSessions, int updatedSessions,
                  int updatedUserMessages, int failedSessions) {}

    /** 一整轮区间回填（可测入口：状态与仓库都由调用方注入）。 */
    static Result backfill(ResumableBackfill state, AiSessionMessageRepository messageRepository,
                           long[] bounds, long pauseMs) throws InterruptedException {
        state.loadFailed();
        long scannedUpTo = ResumableBackfill.resumeId(state.readProgress(), bounds[0]);
        Set<Long> done = new HashSet<>();
        int sessions = 0;
        int userMsgs = 0;
        int failed = 0;
        for (long[] range : OneShotBackfillSupport.ranges(bounds[0], bounds[1], OneShotBackfillSupport.CHILD_ID_STEP)) {
            if (range[1] <= scannedUpTo) {
                continue; // 上一轮已扫完
            }
            for (Long sid : messageRepository.findSessionIdsWithSkillMdToolReadsInIdRange(range[0], range[1])) {
                // 同一会话的 SKILL.md 读可能跨多个区间：整段重算一次即可
                if (sid == null || !done.add(sid)) {
                    continue;
                }
                int[] updated = {0};
                ResumableBackfill.UnitResult r = state.runUnit(String.valueOf(sid),
                        () -> updated[0] = NlSkillAttributionSupport.reconcileSession(messageRepository, sid));
                if (r == ResumableBackfill.UnitResult.OVERFLOW) {
                    return new Result(ResumableBackfill.PassStatus.ABORTED, done.size(), sessions, userMsgs, failed);
                }
                if (r == ResumableBackfill.UnitResult.FAILED) {
                    failed++;
                } else if (updated[0] > 0) {
                    sessions++;
                    userMsgs += updated[0];
                }
            }
            state.checkpoint(String.valueOf(range[1]));
            if (pauseMs > 0) {
                Thread.sleep(pauseMs);
            }
        }
        state.complete();
        return new Result(ResumableBackfill.PassStatus.COMPLETED, done.size(), sessions, userMsgs, failed);
    }
}
