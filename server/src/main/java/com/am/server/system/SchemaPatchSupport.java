package com.am.server.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 各 {@code *SchemaPatches} 共用的“先查后改”DDL 工具。
 *
 * <p><b>为什么不再“直接 ALTER，靠报错判重”</b>：MySQL 8.0 不支持 {@code ADD COLUMN IF NOT EXISTS}
 * （那是 MariaDB 语法），旧写法每列先吃一次语法错误、再发一条不带 IF NOT EXISTS 的真 ALTER，靠“列重复”报错判断
 * “已经有了”。稳态启动 45 列 = 90 条语句，其中 45 条是真 ALTER：每条都要先拿表的元数据锁（MDL）才能解析出“重复列”，
 * 而 MySQL 默认 {@code lock_wait_timeout} 是<b>一年</b>——夜间在线建索引（{@link CoveringIndexBuilder}）
 * 持有 {@code ai_session_event} / {@code ai_session_message} 的 MDL 数小时，或有长事务挡在前面时，
 * 启动补丁就一直卡在这里，而健康检查照样 UP。现在：
 * <ul>
 *   <li><b>先查 information_schema</b>（列 / 索引 / 表，每张表一条查询，复用同一条连接），确实缺才发 DDL；
 *       稳态启动 = 零 DDL；</li>
 *   <li>DDL 前 {@code SET SESSION lock_wait_timeout = }{@value #DDL_LOCK_WAIT_SECONDS}，DDL 后<b>还原</b>
 *       （连接来自池，会话变量不还原会污染后续业务语句）；拿不到 MDL → WARN 并<b>继续启动</b>，下次启动再补，
 *       同一张表本轮后续 DDL 直接跳过（不再逐条各等 {@value #DDL_LOCK_WAIT_SECONDS}s）；</li>
 *   <li>加 / 删列显式 {@code ALGORITHM=INSTANT, LOCK=NONE}（8.0.12+，仅改元数据）；服务端明确拒绝时才回退
 *       {@code ALGORITHM=INPLACE, LOCK=NONE}（WARN，可能重建表）——<b>永不</b>悄悄退成 COPY；</li>
 *   <li>本类的方法<b>不抛异常</b>：单个补丁失败只记日志，不中断其它补丁，也不阻断启动。</li>
 * </ul>
 * “决定是否 / 如何 ALTER”全部是纯函数（{@link #missingColumns}、{@link #classify}、{@code *Sql}），
 * 单测直接覆盖；JDBC 那一层用假 DataSource 验证 SQL 序列。
 */
final class SchemaPatchSupport {

    private static final Logger log = LoggerFactory.getLogger(SchemaPatchSupport.class);

    /** DDL 期间会话级 MDL 等待上限（秒）。MySQL 默认 lock_wait_timeout = 31536000（一年）。 */
    static final int DDL_LOCK_WAIT_SECONDS = 5;

    /**
     * DDL 连接的 JDBC 网络超时（毫秒）。prod 的 socketTimeout（默认 10 分钟）只用来兜「网络黑洞」，
     * 而旧库缺列时 INSTANT 被拒回退 INPLACE、大表建索引都可能跑很久——不抬高会被它当成断网误杀。
     * HikariCP 在连接归还时会复位 networkTimeout，不会污染池。
     */
    static final int DDL_NETWORK_TIMEOUT_MS = 4 * 60 * 60 * 1000;

    // MySQL 服务端错误码（SQLException#getErrorCode）
    private static final int ER_TABLE_EXISTS = 1050;
    private static final int ER_DUP_FIELDNAME = 1060;
    private static final int ER_DUP_KEYNAME = 1061;
    private static final int ER_PARSE_ERROR = 1064;
    private static final int ER_CANT_DROP_FIELD_OR_KEY = 1091;
    private static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    private static final int ER_LOCK_DEADLOCK = 1213;
    private static final int ER_UNKNOWN_ALTER_ALGORITHM = 1800;
    private static final int ER_ALTER_OPERATION_NOT_SUPPORTED = 1845;
    private static final int ER_ALTER_OPERATION_NOT_SUPPORTED_REASON = 1846;
    private static final int ER_INNODB_MAX_ROW_VERSION = 4092;

    private SchemaPatchSupport() {
    }

    // ==================================================================================
    // 规格
    // ==================================================================================

    /** @param ddl 列定义片段（类型 + 约束 + COMMENT），不含列名，如 {@code INT NOT NULL DEFAULT 0 COMMENT 'x'} */
    record ColumnSpec(String table, String column, String ddl) {}

    /** @param columns 索引列清单，如 {@code target_type, event_time} */
    record IndexSpec(String table, String name, String columns) {}

    /** @param createDdl 完整的 {@code CREATE TABLE IF NOT EXISTS …} 语句 */
    record TableSpec(String table, String createDdl) {}

    record ColumnRef(String table, String column) {}

    record IndexRef(String table, String name) {}

    /** 一次 DDL 的执行结果。 */
    enum Outcome {
        /** 本次执行成功。 */
        APPLIED,
        /** 库里已经是目标状态（并发 / 另一实例先做了）。 */
        ALREADY,
        /** 失败（已 WARN），不影响其它步骤。 */
        FAILED,
        /** 拿不到元数据锁 / 死锁：该表本轮不再尝试。 */
        LOCK_TIMEOUT
    }

    /** {@link #classify} 对 DDL 异常的归类。 */
    enum Failure {
        /** 重复列 / 重复索引 / 表已存在 / 要删的东西本来就没有：等价于成功。 */
        ALREADY_IN_DESIRED_STATE,
        /** MDL 等待超时或死锁。 */
        LOCK_TIMEOUT,
        /** 该 ALGORITHM 不适用（或服务端太老不认识）：可以换下一个 ALGORITHM。 */
        ALGORITHM_UNSUPPORTED,
        OTHER
    }

    // ==================================================================================
    // 纯函数
    // ==================================================================================

    /**
     * 想要的列里，库里确实缺的那些（保持入参顺序）。
     *
     * @param existing 表名(小写) → 该表现有列名(小写)。<b>没有键</b>（查询失败）或<b>空集</b>（表不存在）的表一律跳过：
     *                 无法判断 / 无表可改，宁可不发 DDL。
     */
    static List<ColumnSpec> missingColumns(List<ColumnSpec> wanted, Map<String, Set<String>> existing) {
        List<ColumnSpec> out = new ArrayList<>();
        for (ColumnSpec c : wanted) {
            Set<String> cols = existing.get(lower(c.table()));
            if (cols == null || cols.isEmpty()) {
                continue;
            }
            if (!cols.contains(lower(c.column()))) {
                out.add(c);
            }
        }
        return out;
    }

    static List<IndexSpec> missingIndexes(List<IndexSpec> wanted, Map<String, Set<String>> existing) {
        List<IndexSpec> out = new ArrayList<>();
        for (IndexSpec i : wanted) {
            Set<String> idx = existing.get(lower(i.table()));
            if (idx == null) {
                continue;
            }
            if (!idx.contains(lower(i.name()))) {
                out.add(i);
            }
        }
        return out;
    }

    static List<TableSpec> missingTables(List<TableSpec> wanted, Set<String> existingTables) {
        List<TableSpec> out = new ArrayList<>();
        for (TableSpec t : wanted) {
            if (!existingTables.contains(lower(t.table()))) {
                out.add(t);
            }
        }
        return out;
    }

    static List<ColumnRef> presentColumns(List<ColumnRef> candidates, Map<String, Set<String>> existing) {
        List<ColumnRef> out = new ArrayList<>();
        for (ColumnRef c : candidates) {
            Set<String> cols = existing.get(lower(c.table()));
            if (cols != null && cols.contains(lower(c.column()))) {
                out.add(c);
            }
        }
        return out;
    }

    static List<IndexRef> presentIndexes(List<IndexRef> candidates, Map<String, Set<String>> existing) {
        List<IndexRef> out = new ArrayList<>();
        for (IndexRef i : candidates) {
            Set<String> idx = existing.get(lower(i.table()));
            if (idx != null && idx.contains(lower(i.name()))) {
                out.add(i);
            }
        }
        return out;
    }

    static String addColumnSql(ColumnSpec c, String algorithm) {
        return "ALTER TABLE " + c.table() + " ADD COLUMN " + c.column() + " " + c.ddl()
                + ", ALGORITHM=" + algorithm + ", LOCK=NONE";
    }

    static String dropColumnSql(ColumnRef c, String algorithm) {
        return "ALTER TABLE " + c.table() + " DROP COLUMN " + c.column()
                + ", ALGORITHM=" + algorithm + ", LOCK=NONE";
    }

    static String addIndexSql(IndexSpec i) {
        return "ALTER TABLE " + i.table() + " ADD INDEX " + i.name() + " (" + i.columns() + ")"
                + ", ALGORITHM=INPLACE, LOCK=NONE";
    }

    static String dropIndexSql(IndexRef i) {
        return "ALTER TABLE " + i.table() + " DROP INDEX " + i.name() + ", ALGORITHM=INPLACE, LOCK=NONE";
    }

    /** 对 DDL 抛出的 SQLException 归类（先看 MySQL 错误码，再看消息文本兜底）。 */
    static Failure classify(SQLException e) {
        int code = e.getErrorCode();
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        switch (code) {
            case ER_DUP_FIELDNAME, ER_DUP_KEYNAME, ER_TABLE_EXISTS, ER_CANT_DROP_FIELD_OR_KEY:
                return Failure.ALREADY_IN_DESIRED_STATE;
            case ER_LOCK_WAIT_TIMEOUT, ER_LOCK_DEADLOCK:
                return Failure.LOCK_TIMEOUT;
            case ER_UNKNOWN_ALTER_ALGORITHM, ER_ALTER_OPERATION_NOT_SUPPORTED,
                 ER_ALTER_OPERATION_NOT_SUPPORTED_REASON, ER_INNODB_MAX_ROW_VERSION, ER_PARSE_ERROR:
                return Failure.ALGORITHM_UNSUPPORTED;
            default:
                break;
        }
        if (msg.contains("duplicate column") || msg.contains("duplicate key name")
                || (msg.contains("already exists") && msg.contains("table"))) {
            return Failure.ALREADY_IN_DESIRED_STATE;
        }
        if (msg.contains("lock wait timeout") || msg.contains("deadlock found")) {
            return Failure.LOCK_TIMEOUT;
        }
        if (msg.contains("algorithm=") || msg.contains("unknown algorithm")) {
            return Failure.ALGORITHM_UNSUPPORTED;
        }
        return Failure.OTHER;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    // ==================================================================================
    // 对外入口（均不抛异常）
    // ==================================================================================

    /** 缺哪列补哪列。返回本次<b>实际执行成功</b>的列数；稳态（都已存在）零 DDL、每表一条 information_schema 查询。 */
    static int ensureColumns(DataSource dataSource, List<ColumnSpec> specs) {
        if (specs.isEmpty()) {
            return 0;
        }
        try (Connection c = dataSource.getConnection()) {
            Map<String, Set<String>> existing = loadColumns(c, tablesOf(specs, ColumnSpec::table));
            reportMissingTables(existing, tablesOf(specs, ColumnSpec::table), "columns");
            List<Step> steps = new ArrayList<>();
            for (ColumnSpec s : missingColumns(specs, existing)) {
                steps.add(new Step(s.table(), "add column " + s.table() + "." + s.column(),
                        List.of(addColumnSql(s, "INSTANT"), addColumnSql(s, "INPLACE"))));
            }
            return execute(c, steps);
        } catch (SQLException e) {
            log.warn("schema patch: ensure columns skipped: {}", e.getMessage());
            return 0;
        }
    }

    static int ensureColumn(DataSource dataSource, String table, String column, String ddl) {
        return ensureColumns(dataSource, List.of(new ColumnSpec(table, column, ddl)));
    }

    /** 缺哪个索引补哪个（{@code ALGORITHM=INPLACE, LOCK=NONE}）。返回本次实际建成的个数。 */
    static int ensureIndexes(DataSource dataSource, List<IndexSpec> specs) {
        if (specs.isEmpty()) {
            return 0;
        }
        try (Connection c = dataSource.getConnection()) {
            Map<String, Set<String>> existing = loadIndexes(c, tablesOf(specs, IndexSpec::table));
            List<Step> steps = new ArrayList<>();
            for (IndexSpec s : missingIndexes(specs, existing)) {
                steps.add(new Step(s.table(), "add index " + s.table() + "." + s.name(), List.of(addIndexSql(s))));
            }
            return execute(c, steps);
        } catch (SQLException e) {
            log.warn("schema patch: ensure indexes skipped: {}", e.getMessage());
            return 0;
        }
    }

    static int ensureIndex(DataSource dataSource, String table, String name, String columns) {
        return ensureIndexes(dataSource, List.of(new IndexSpec(table, name, columns)));
    }

    /** 表不存在才执行 {@code CREATE TABLE IF NOT EXISTS}（稳态零 DDL：该语句对已存在的表同样要抢表名 MDL）。 */
    static int ensureTables(DataSource dataSource, List<TableSpec> specs) {
        if (specs.isEmpty()) {
            return 0;
        }
        try (Connection c = dataSource.getConnection()) {
            Set<String> existing = loadTables(c, tablesOf(specs, TableSpec::table));
            List<Step> steps = new ArrayList<>();
            for (TableSpec s : missingTables(specs, existing)) {
                steps.add(new Step(s.table(), "create table " + s.table(), List.of(s.createDdl())));
            }
            return execute(c, steps);
        } catch (SQLException e) {
            log.warn("schema patch: ensure tables skipped: {}", e.getMessage());
            return 0;
        }
    }

    static int ensureTable(DataSource dataSource, String table, String createDdl) {
        return ensureTables(dataSource, List.of(new TableSpec(table, createDdl)));
    }

    /** 索引存在才删（先删索引再删列，顺序由调用方保证）。 */
    static int dropIndexesIfPresent(DataSource dataSource, List<IndexRef> refs) {
        if (refs.isEmpty()) {
            return 0;
        }
        try (Connection c = dataSource.getConnection()) {
            Map<String, Set<String>> existing = loadIndexes(c, tablesOf(refs, IndexRef::table));
            List<Step> steps = new ArrayList<>();
            for (IndexRef r : presentIndexes(refs, existing)) {
                steps.add(new Step(r.table(), "drop index " + r.table() + "." + r.name(), List.of(dropIndexSql(r))));
            }
            return execute(c, steps);
        } catch (SQLException e) {
            log.warn("schema patch: drop indexes skipped: {}", e.getMessage());
            return 0;
        }
    }

    /** 列存在才删。 */
    static int dropColumnsIfPresent(DataSource dataSource, List<ColumnRef> refs) {
        if (refs.isEmpty()) {
            return 0;
        }
        try (Connection c = dataSource.getConnection()) {
            Map<String, Set<String>> existing = loadColumns(c, tablesOf(refs, ColumnRef::table));
            List<Step> steps = new ArrayList<>();
            for (ColumnRef r : presentColumns(refs, existing)) {
                steps.add(new Step(r.table(), "drop column " + r.table() + "." + r.column(),
                        List.of(dropColumnSql(r, "INSTANT"), dropColumnSql(r, "INPLACE"))));
            }
            return execute(c, steps);
        } catch (SQLException e) {
            log.warn("schema patch: drop columns skipped: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 在 {@code seconds} 秒 MDL 等待上限下执行 {@code body}，结束后把会话变量还原（连接来自池，不还原会污染业务语句）。
     * 供 {@link CoveringIndexBuilder} 这类自带 DDL 的调用方复用。
     */
    static void withLockWaitTimeout(Connection c, int seconds, SqlBody body) throws SQLException {
        long original = readSessionLockWaitTimeout(c);
        try (Statement st = c.createStatement()) {
            st.execute("SET SESSION lock_wait_timeout = " + seconds);
        }
        try {
            body.run(c);
        } finally {
            try (Statement st = c.createStatement()) {
                st.execute("SET SESSION lock_wait_timeout = " + original);
            } catch (SQLException e) {
                log.debug("schema patch: restore lock_wait_timeout failed: {}", e.getMessage());
            }
        }
    }

    @FunctionalInterface
    interface SqlBody {
        void run(Connection c) throws SQLException;
    }

    // ==================================================================================
    // 执行
    // ==================================================================================

    /** 一次 DDL：{@code sqls} 按序尝试，仅在 {@link Failure#ALGORITHM_UNSUPPORTED} 时才换下一条。 */
    private record Step(String table, String label, List<String> sqls) {}

    /** 顺序执行 DDL；返回 {@link Outcome#APPLIED} 的步数。无步骤时不发任何语句（含 SET SESSION）。 */
    private static int execute(Connection c, List<Step> steps) {
        if (steps.isEmpty()) {
            return 0;
        }
        int applied = 0;
        Set<String> lockedOut = new HashSet<>();
        raiseNetworkTimeout(c);
        try {
            long original = readSessionLockWaitTimeout(c);
            try (Statement st = c.createStatement()) {
                st.execute("SET SESSION lock_wait_timeout = " + DDL_LOCK_WAIT_SECONDS);
            }
            try {
                for (Step step : steps) {
                    if (lockedOut.contains(lower(step.table()))) {
                        log.warn("schema patch: {} skipped (metadata lock on {} unavailable earlier this run; will retry next boot)",
                                step.label(), step.table());
                        continue;
                    }
                    Outcome o = runStep(c, step);
                    if (o == Outcome.APPLIED) {
                        applied++;
                    } else if (o == Outcome.LOCK_TIMEOUT) {
                        lockedOut.add(lower(step.table()));
                    }
                }
            } finally {
                try (Statement st = c.createStatement()) {
                    st.execute("SET SESSION lock_wait_timeout = " + original);
                } catch (SQLException e) {
                    log.debug("schema patch: restore lock_wait_timeout failed: {}", e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("schema patch: DDL batch aborted: {}", e.getMessage());
        }
        return applied;
    }

    /** 尽力而为：驱动 / 代理不支持时保持原超时，不影响 DDL 本身。 */
    private static void raiseNetworkTimeout(Connection c) {
        try {
            c.setNetworkTimeout(Runnable::run, DDL_NETWORK_TIMEOUT_MS);
        } catch (SQLException | RuntimeException e) {
            log.debug("schema patch: setNetworkTimeout unsupported: {}", e.toString());
        }
    }

    private static Outcome runStep(Connection c, Step step) {
        for (int i = 0; i < step.sqls().size(); i++) {
            String sql = step.sqls().get(i);
            try (Statement st = c.createStatement()) {
                st.execute(sql);
                if (i > 0) {
                    log.warn("schema patch: {} done via fallback (table may have been rebuilt): {}", step.label(), sql);
                } else {
                    log.info("schema patch: {} done", step.label());
                }
                return Outcome.APPLIED;
            } catch (SQLException e) {
                switch (classify(e)) {
                    case ALREADY_IN_DESIRED_STATE:
                        log.debug("schema patch: {} already in place ({})", step.label(), e.getMessage());
                        return Outcome.ALREADY;
                    case LOCK_TIMEOUT:
                        log.warn("schema patch: {} not applied, metadata lock wait exceeded {}s "
                                        + "(online DDL / long transaction on {}?); continuing startup, will retry next boot: {}",
                                step.label(), DDL_LOCK_WAIT_SECONDS, step.table(), e.getMessage());
                        return Outcome.LOCK_TIMEOUT;
                    case ALGORITHM_UNSUPPORTED:
                        if (i + 1 < step.sqls().size()) {
                            log.info("schema patch: {} rejected by server ({}), falling back to next ALGORITHM",
                                    step.label(), e.getMessage());
                            continue;
                        }
                        log.warn("schema patch: {} failed: {}", step.label(), e.getMessage());
                        return Outcome.FAILED;
                    default:
                        log.warn("schema patch: {} failed: {}", step.label(), e.getMessage());
                        return Outcome.FAILED;
                }
            }
        }
        return Outcome.FAILED;
    }

    // ==================================================================================
    // information_schema 查询
    // ==================================================================================

    private static <T> Set<String> tablesOf(List<T> specs, java.util.function.Function<T, String> table) {
        Set<String> out = new LinkedHashSet<>();
        for (T s : specs) {
            out.add(lower(table.apply(s)));
        }
        return out;
    }

    /** 每张表一条查询；查询失败的表不放进结果（调用方据此“无法判断则不动”）。 */
    private static Map<String, Set<String>> loadColumns(Connection c, Set<String> tables) {
        return loadNames(c, tables, """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                """);
    }

    private static Map<String, Set<String>> loadIndexes(Connection c, Set<String> tables) {
        return loadNames(c, tables, """
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ?
                """);
    }

    private static Map<String, Set<String>> loadNames(Connection c, Set<String> tables, String sql) {
        Map<String, Set<String>> out = new HashMap<>();
        for (String table : tables) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, table);
                Set<String> names = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(lower(rs.getString(1)));
                    }
                }
                out.put(table, names);
            } catch (SQLException e) {
                log.warn("schema patch: information_schema lookup failed for {} (skipping, not touching it): {}",
                        table, e.getMessage());
            }
        }
        return out;
    }

    private static Set<String> loadTables(Connection c, Set<String> tables) throws SQLException {
        Set<String> out = new HashSet<>();
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) {
            in.append(i == 0 ? "?" : ", ?");
        }
        String sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name IN (" + in + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String t : tables) {
                ps.setString(i++, t);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(lower(rs.getString(1)));
                }
            }
        }
        return out;
    }

    /** 表本身不存在 = 该表列集合为空：给个 WARN，避免静默。 */
    private static void reportMissingTables(Map<String, Set<String>> existing, Set<String> wantedTables, String what) {
        for (String t : wantedTables) {
            Set<String> cols = existing.get(t);
            if (cols != null && cols.isEmpty()) {
                log.warn("schema patch: table {} not found, skipping its {}", t, what);
            }
        }
    }

    private static long readSessionLockWaitTimeout(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT @@SESSION.lock_wait_timeout")) {
            if (rs.next()) {
                long v = rs.getLong(1);
                if (v > 0) {
                    return v;
                }
            }
        }
        return 31_536_000L; // MySQL 默认值
    }
}
