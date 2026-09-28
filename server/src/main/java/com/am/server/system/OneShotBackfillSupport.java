package com.am.server.system;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动期"存量一次性回填"的公共件：sys_config marker 判重 + 按主键区间分批 UPDATE。
 *
 * <p>为什么要分批：ApplicationRunner 在 Tomcat 已经开始接流量之后才执行；MySQL 默认 REPEATABLE READ 下，
 * 一条全表 {@code UPDATE…JOIN} 会对扫过的每一行及其间隙加 next-key 锁、对 JOIN / 子查询读到的行加共享锁，
 * 语句跑多久，ingest 的 {@code INSERT ai_session} 就等多久——超过 innodb_lock_wait_timeout（默认 50s）
 * 整包上报失败、客户端进 outbox 重发，负载再翻一倍。按主键区间切成小语句、autocommit 逐批提交，
 * 单批持锁是毫秒级。
 *
 * <p>为什么要 marker：这些回填只服务历史数据（现行 ingest 已自行写对），此前却每次启动都整表重扫。
 * gz
 */
final class OneShotBackfillSupport {

    /** 主键区间分批步长：按行宽取值，单批在毫秒到百毫秒级。 */
    static final int SESSION_ID_STEP = 2_000;
    static final int CHILD_ID_STEP = 5_000;

    private static final String MARKER_EXISTS = "SELECT 1 FROM sys_config WHERE config_key = ?";

    private static final String MARKER_INSERT = """
            INSERT IGNORE INTO sys_config
                (config_key, config_value, value_type, category, is_secret, description, updated_time, created_time)
            VALUES (?, 'done', 'string', 'system', 0, ?, NOW(), NOW())
            """;

    private OneShotBackfillSupport() {
    }

    static boolean markerExists(DataSource dataSource, String key) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(MARKER_EXISTS)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static void writeMarker(DataSource dataSource, String key, String description) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(MARKER_INSERT)) {
            ps.setString(1, key);
            ps.setString(2, description);
            ps.executeUpdate();
        }
    }

    /**
     * 按驱动表主键区间分批执行 {@code sql}，每批独立提交。
     *
     * @param table 驱动表名（取 MIN/MAX(id) 用；只传代码内常量）
     * @param sql   恰含两个占位符：驱动表主键下界（含）、上界（不含）
     * @return 累计影响行数
     */
    static long updateByIdRange(DataSource dataSource, String table, String sql, int step) throws SQLException {
        long total = 0;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            long[] bounds = minMaxId(c, table);
            if (bounds == null) {
                return 0;
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (long[] r : ranges(bounds[0], bounds[1], step)) {
                    ps.setLong(1, r[0]);
                    ps.setLong(2, r[1]);
                    total += ps.executeUpdate();
                }
            }
        }
        return total;
    }

    /** {@code [min, max]} 切成左闭右开的 {@code [lo, lo+step)} 区间，覆盖 max。 */
    static List<long[]> ranges(long min, long max, int step) {
        if (step <= 0) {
            throw new IllegalArgumentException("step must be positive: " + step);
        }
        List<long[]> out = new ArrayList<>();
        for (long lo = min; lo <= max; lo += step) {
            out.add(new long[]{lo, lo + step});
        }
        return out;
    }

    private static long[] minMaxId(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MIN(id), MAX(id) FROM " + table)) {
            if (!rs.next()) {
                return null;
            }
            long min = rs.getLong(1);
            if (rs.wasNull()) {
                return null;
            }
            return new long[]{min, rs.getLong(2)};
        }
    }
}
