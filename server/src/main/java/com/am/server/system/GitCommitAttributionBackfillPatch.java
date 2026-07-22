package com.am.server.system;

import com.am.server.aggregator.GitCommitAttributionEngine;
import com.am.server.domain.git.GitCommitRepository;
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
import java.time.LocalDate;

/**
 * git_commit_attribution 历史全量回溯（《管理后台-产出归因与能力使用分析 v1.0》§2.7）：
 * 从最早 commit_time 起按天调 {@link GitCommitAttributionEngine#attributeWindow}，
 * 回溯行打 backfilled=1（前端趋势图对回溯区间注明「上线前数据为回溯推算」）。
 * B 档回溯（trailer 文本匹配）可信；A 档回溯依赖历史会话事件完整性。
 *
 * <p>完成后写 sys_config marker（{@value #MARKER_KEY}）；途中有失败日则不写 marker，
 * 下次启动整段重跑（delete+insert 幂等）。后台 daemon 线程执行，不阻塞启动。
 * gz
 */
@Configuration
public class GitCommitAttributionBackfillPatch {

    private static final Logger log = LoggerFactory.getLogger(GitCommitAttributionBackfillPatch.class);

    static final String MARKER_KEY = "attribution.backfill_v1";

    private static final String MARKER_EXISTS = "SELECT 1 FROM sys_config WHERE config_key = ?";

    private static final String MARKER_INSERT = """
            INSERT IGNORE INTO sys_config
                (config_key, config_value, value_type, category, is_secret, description, updated_by, updated_time, created_time)
            VALUES (?, 'done', 'string', 'attribution', 0, 'git_commit_attribution 历史全量回溯完成标记', 'seed', NOW(), NOW())
            """;

    /** 晚于 GitCommitAttributionSchemaPatches（@Order(130)）：先建表再回溯。 */
    @Bean
    @Order(910)
    ApplicationRunner backfillGitCommitAttribution(DataSource dataSource,
                                                   GitCommitAttributionEngine engine,
                                                   GitCommitRepository gitCommitRepository) {
        return args -> {
            try (Connection c = dataSource.getConnection()) {
                if (markerExists(c)) {
                    return;
                }
            } catch (SQLException e) {
                log.warn("git attribution backfill skipped: {}", e.getMessage());
                return;
            }
            var minCommitTime = gitCommitRepository.findMinCommitTime();
            if (minCommitTime == null) {
                try (Connection c = dataSource.getConnection()) {
                    writeMarker(c);
                } catch (SQLException e) {
                    log.warn("git attribution backfill marker write failed: {}", e.getMessage());
                }
                return;
            }
            LocalDate from = minCommitTime.toLocalDate();
            Thread t = new Thread(() -> runBackfill(dataSource, engine, from), "git-attribution-backfill");
            t.setDaemon(true);
            t.start();
        };
    }

    private static void runBackfill(DataSource dataSource, GitCommitAttributionEngine engine, LocalDate from) {
        LocalDate to = LocalDate.now();
        int days = 0;
        int failed = 0;
        long commits = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            try {
                commits += engine.attributeWindow(d.atStartOfDay(), d.plusDays(1).atStartOfDay(), true);
                days++;
            } catch (Exception ex) {
                failed++;
                log.warn("git attribution backfill: date={} failed: {}", d, ex.toString());
            }
        }
        if (failed > 0) {
            log.warn("git attribution backfill incomplete: from={} to={} ok={} failed={}; marker not written, will retry on next boot",
                    from, to, days, failed);
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            writeMarker(c);
        } catch (SQLException e) {
            log.warn("git attribution backfill marker write failed: {}", e.getMessage());
            return;
        }
        log.info("git attribution backfill done: from={} to={} days={} commits={}", from, to, days, commits);
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
