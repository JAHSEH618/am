package com.am.server.system;

import com.am.server.aggregator.CapabilityDailyAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;

/**
 * capability_daily 历史全量回溯（《管理后台-产出归因与能力使用分析 v1.0》§3.3）：
 * 从最早的 message / event 时间起逐日重算（整日 delete + insert，天然幂等）。
 * 数据已在库、纯服务端回算，不依赖 agent 发版。
 *
 * <p><b>可续跑 + 容错</b>（骨架见 {@link ResumableBackfill}）：进度游标落 sys_config
 * {@code capability.backfill_v2.progress}，重启从游标续跑；单日失败记 WARN + 记入
 * {@code capability.backfill_v2.failed}、跳过继续；整轮跑完写完成标记（{@value #MARKER_KEY}）；
 * 之后每次启动只重试失败日，最多 {@value ResumableBackfill#MAX_RETRY_ATTEMPTS} 次。
 * 此前“一天失败就不写标记、每次重启整段重算”。
 *
 * <p><b>走 per-date 锁</b>：每天经 {@link CapabilityDailyAggregator#aggregateUnderDateLock} 重算，与整点任务、
 * 每日任务、view-time 刷新共用同一把锁——此前直接调 {@code aggregate}，回溯到 today / yesterday 时与整点任务
 * 对同一天并发 delete + insert，撞唯一键，标记永远写不上。拿不到锁的日子按失败处理（原地重试，仍不行则记录、下次启动重试）。
 *
 * <p>回算在后台 daemon 线程执行（最早日期的两条 MIN 查询也在线程里），不阻塞启动。
 * gz
 */
@Configuration
public class CapabilityDailyBackfillPatch {

    private static final Logger log = LoggerFactory.getLogger(CapabilityDailyBackfillPatch.class);

    /** v2：codex skill 口径改为 {@code <skill>} 执行信封（SlashAnnotationBackfillPatch v3）后的整表重算。 */
    static final String MARKER_KEY = "capability.backfill_v2";

    static final String MARKER_DESCRIPTION = "capability_daily 历史全量回溯完成标记";

    /** 回溯等一把 per-date 锁的上限：整点任务重算一天只要秒级，等不到说明有别的异常，记失败留待下次。 */
    static final Duration DATE_LOCK_WAIT = Duration.ofSeconds(30);

    /** 晚于 CapabilityDailySchemaPatches（@Order(120)）与 SlashAnnotationBackfillPatch（@Order(880)，slash 重刷须先落库）：先建表、刷完 hits 再回溯。 */
    @Bean
    @Order(900)
    ApplicationRunner backfillCapabilityDaily(DataSource dataSource, CapabilityDailyAggregator aggregator) {
        return args -> {
            ResumableBackfill state = new ResumableBackfill(
                    ResumableBackfill.Store.jdbc(dataSource), MARKER_KEY, MARKER_DESCRIPTION);
            try {
                if (!state.hasPendingWork()) {
                    return;
                }
            } catch (SQLException e) {
                log.warn("capability daily backfill skipped: {}", e.getMessage());
                return;
            }
            Thread t = new Thread(() -> run(dataSource, state, aggregator), "capability-backfill");
            t.setDaemon(true);
            t.start();
        };
    }

    private static void run(DataSource dataSource, ResumableBackfill state, CapabilityDailyAggregator aggregator) {
        try {
            if (state.isDone()) {
                ResumableBackfill.RetryResult r = state.retryFailed(label -> aggregateDay(aggregator, label));
                if (r.retried() > 0) {
                    log.info("capability daily backfill retry: retried={} recovered={} remaining={}",
                            r.retried(), r.recovered(), r.remaining());
                }
                return;
            }
            LocalDate earliest;
            try (Connection c = dataSource.getConnection()) {
                earliest = earliestDataDate(c);
            }
            if (earliest == null) {
                state.loadFailed();
                state.complete();
                return;
            }
            LocalDate to = LocalDate.now();
            ResumableBackfill.PassResult r = backfill(state, earliest, to, aggregator);
            log.info("capability daily backfill {}: from={} to={} ok={} failed={}",
                    r.status().name().toLowerCase(), earliest, to, r.ok(), r.failed());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("capability daily backfill aborted (will resume next boot): {}", e.toString());
        }
    }

    /** 一整轮日期回填（可测入口：状态与聚合器都由调用方注入）。 */
    static ResumableBackfill.PassResult backfill(ResumableBackfill state, LocalDate earliest, LocalDate to,
                                                 CapabilityDailyAggregator aggregator) throws InterruptedException {
        return state.runDayPass(earliest, to, label -> aggregateDay(aggregator, label));
    }

    /** 单日：拿 per-date 锁重算；拿不到锁抛异常（由骨架计入失败）。 */
    static void aggregateDay(CapabilityDailyAggregator aggregator, String isoDate) throws InterruptedException {
        LocalDate day = LocalDate.parse(isoDate);
        int rows = aggregator.aggregateUnderDateLock(day, DATE_LOCK_WAIT);
        if (rows == CapabilityDailyAggregator.LOCK_NOT_ACQUIRED) {
            throw new IllegalStateException("per-date lock busy for " + day + " (waited " + DATE_LOCK_WAIT + ")");
        }
    }

    /** 最早的 event_time / message_time（取更早者）；两表皆空返回 null。 */
    private static LocalDate earliestDataDate(Connection c) throws SQLException {
        LocalDate minEvent = minDate(c, "SELECT MIN(event_time) FROM ai_session_event");
        LocalDate minMessage = minDate(c, "SELECT MIN(message_time) FROM ai_session_message");
        if (minEvent == null) {
            return minMessage;
        }
        if (minMessage == null) {
            return minEvent;
        }
        return minEvent.isBefore(minMessage) ? minEvent : minMessage;
    }

    private static LocalDate minDate(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            Timestamp ts = rs.getTimestamp(1);
            return ts == null ? null : ts.toLocalDateTime().toLocalDate();
        }
    }
}
