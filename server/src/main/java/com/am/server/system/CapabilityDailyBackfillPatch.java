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
import java.time.LocalDate;

/**
 * capability_daily 历史全量回溯（《管理后台-产出归因与能力使用分析 v1.0》§3.3）：
 * 从最早的 message / event 时间起逐日调 {@link CapabilityDailyAggregator#aggregate}
 * （整日 delete + insert，天然幂等）。数据已在库、纯服务端回算，不依赖 agent 发版。
 *
 * <p>完成后写 sys_config marker（{@value #MARKER_KEY}），后续启动直接跳过；
 * 途中有失败日则不写 marker，下次启动整段重跑（模式同 SlashAnnotationBackfillPatch）。
 * 回算在后台 daemon 线程执行，不阻塞启动。
 * gz
 */
@Configuration
public class CapabilityDailyBackfillPatch {

    private static final Logger log = LoggerFactory.getLogger(CapabilityDailyBackfillPatch.class);

    static final String MARKER_KEY = "capability.backfill_v1";

    private static final String MARKER_EXISTS = "SELECT 1 FROM sys_config WHERE config_key = ?";

    private static final String MARKER_INSERT = """
            INSERT IGNORE INTO sys_config
                (config_key, config_value, value_type, category, is_secret, description, updated_by, updated_time, created_time)
            VALUES (?, 'done', 'string', 'scheduling', 0, 'capability_daily 历史全量回溯完成标记', 'seed', NOW(), NOW())
            """;

    /** 晚于 CapabilityDailySchemaPatches（@Order(120)）：先建表再回溯。 */
    @Bean
    @Order(900)
    ApplicationRunner backfillCapabilityDaily(DataSource dataSource, CapabilityDailyAggregator aggregator) {
        return args -> {
            LocalDate minDate;
            try (Connection c = dataSource.getConnection()) {
                if (markerExists(c)) {
                    return;
                }
                minDate = earliestDataDate(c);
                if (minDate == null) {
                    writeMarker(c);
                    return;
                }
            } catch (SQLException e) {
                log.warn("capability daily backfill skipped: {}", e.getMessage());
                return;
            }
            LocalDate from = minDate;
            Thread t = new Thread(() -> runBackfill(dataSource, aggregator, from), "capability-backfill");
            t.setDaemon(true);
            t.start();
        };
    }

    private static void runBackfill(DataSource dataSource, CapabilityDailyAggregator aggregator, LocalDate from) {
        LocalDate to = LocalDate.now();
        int days = 0;
        int failed = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            try {
                aggregator.aggregate(d);
                days++;
            } catch (Exception ex) {
                failed++;
                log.warn("capability daily backfill: date={} failed: {}", d, ex.toString());
            }
        }
        if (failed > 0) {
            log.warn("capability daily backfill incomplete: from={} to={} ok={} failed={}; marker not written, will retry on next boot",
                    from, to, days, failed);
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            writeMarker(c);
        } catch (SQLException e) {
            log.warn("capability daily backfill marker write failed: {}", e.getMessage());
            return;
        }
        log.info("capability daily backfill done: from={} to={} days={}", from, to, days);
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

    private static boolean markerExists(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(MARKER_EXISTS)) {
            ps.setString(1, MARKER_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void writeMarker(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(MARKER_INSERT)) {
            ps.setString(1, MARKER_KEY);
            ps.executeUpdate();
        }
    }
}
