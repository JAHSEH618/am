package com.am.server.system;

import com.am.server.system.SchemaPatchSupport.ColumnRef;
import com.am.server.system.SchemaPatchSupport.ColumnSpec;
import com.am.server.system.SchemaPatchSupport.Failure;
import com.am.server.system.SchemaPatchSupport.IndexRef;
import com.am.server.system.SchemaPatchSupport.IndexSpec;
import com.am.server.system.SchemaPatchSupport.TableSpec;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SchemaPatchSupport：纯决策函数 + 假 JDBC 下的 SQL 序列。
 * 关键断言：稳态启动零 DDL（连 SET SESSION 都不发）；缺才 ALTER；INSTANT 被拒才回退 INPLACE 且永不 COPY；
 * 锁等待超时只 WARN、不重试本表后续 DDL、其它表照常；会话变量用完还原。
 */
class SchemaPatchSupportTest {

    private static final String SET_5 = "SET SESSION lock_wait_timeout = 5";
    private static final String RESTORE = "SET SESSION lock_wait_timeout = 31536000";

    /** 假 MySQL：information_schema 三视图 + DDL 副作用 + 可编排的失败。 */
    static final class FakeMysql implements FakeJdbc.Handler {
        final Map<String, Set<String>> columns = new HashMap<>();
        final Map<String, Set<String>> indexes = new HashMap<>();
        final Set<String> tables = new HashSet<>();
        /** SQL 含该子串时抛出（按 sql 计算异常，便于按 ALGORITHM 区分）。 */
        final List<Function<String, SQLException>> failures = new ArrayList<>();
        SQLException infoSchemaFailure;

        FakeMysql withColumns(String table, String... cols) {
            columns.computeIfAbsent(table, k -> new HashSet<>()).addAll(List.of(cols));
            tables.add(table);
            return this;
        }

        FakeMysql withIndexes(String table, String... idx) {
            indexes.computeIfAbsent(table, k -> new HashSet<>()).addAll(List.of(idx));
            return this;
        }

        @Override
        public List<Object[]> query(String sql, List<Object> params) throws SQLException {
            String s = sql.toLowerCase();
            if (s.contains("@@session.lock_wait_timeout")) {
                return List.<Object[]>of(new Object[]{31_536_000L});
            }
            if (infoSchemaFailure != null && s.contains("information_schema")) {
                throw infoSchemaFailure;
            }
            if (s.contains("information_schema.columns")) {
                return columns.getOrDefault(params.get(0).toString(), Set.of()).stream()
                        .map(c -> new Object[]{c}).toList();
            }
            if (s.contains("information_schema.statistics")) {
                return indexes.getOrDefault(params.get(0).toString(), Set.of()).stream()
                        .map(c -> new Object[]{c}).toList();
            }
            if (s.contains("information_schema.tables")) {
                return params.stream().filter(p -> tables.contains(p.toString()))
                        .map(p -> new Object[]{p}).toList();
            }
            return List.of();
        }

        @Override
        public int update(String sql, List<Object> params) throws SQLException {
            for (Function<String, SQLException> f : failures) {
                SQLException e = f.apply(sql);
                if (e != null) {
                    throw e;
                }
            }
            return 0;
        }
    }

    private static Function<String, SQLException> failWhenContains(String needle, int code, String msg) {
        return sql -> sql.contains(needle) ? new SQLException(msg, "HY000", code) : null;
    }

    private static List<ColumnSpec> cols(String table, String... names) {
        List<ColumnSpec> out = new ArrayList<>();
        for (String n : names) {
            out.add(new ColumnSpec(table, n, "INT NOT NULL DEFAULT 0 COMMENT 'x'"));
        }
        return out;
    }

    // ---------------------------------------------------------------- 稳态

    @Test
    void steadyStateRunsNoDdlAndNoSessionChangeJustOneQueryPerTable() {
        FakeMysql db = new FakeMysql()
                .withColumns("analysis_report", "a", "b")
                .withColumns("analysis_report_user", "c", "d", "e");
        FakeJdbc jdbc = new FakeJdbc(db);
        List<ColumnSpec> specs = new ArrayList<>();
        specs.addAll(cols("analysis_report", "a", "b"));
        specs.addAll(cols("analysis_report_user", "c", "d", "e"));

        int added = SchemaPatchSupport.ensureColumns(jdbc.dataSource(), specs);

        assertThat(added).isZero();
        assertThat(jdbc.updates).as("稳态必须零 DDL（连 SET SESSION 也不发）").isEmpty();
        assertThat(jdbc.executed).hasSize(2); // 每张表恰好一条 information_schema 查询
        assertThat(jdbc.executed).allMatch(s -> s.toLowerCase().contains("information_schema.columns"));
    }

    @Test
    void steadyStateForIndexesTablesAndDropsAlsoRunsNoDdl() {
        FakeMysql db = new FakeMysql()
                .withColumns("git_commit", "id", "path_stats_json")
                .withIndexes("git_commit", "idx_commit_time")
                .withColumns("git_commit_file", "id");
        FakeJdbc jdbc = new FakeJdbc(db);

        assertThat(SchemaPatchSupport.ensureIndexes(jdbc.dataSource(),
                List.of(new IndexSpec("git_commit", "idx_commit_time", "commit_time")))).isZero();
        assertThat(SchemaPatchSupport.ensureTables(jdbc.dataSource(),
                List.of(new TableSpec("git_commit_file", "CREATE TABLE IF NOT EXISTS git_commit_file (id INT)")))).isZero();
        assertThat(SchemaPatchSupport.dropIndexesIfPresent(jdbc.dataSource(),
                List.of(new IndexRef("git_commit", "idx_ai_assisted")))).isZero();
        assertThat(SchemaPatchSupport.dropColumnsIfPresent(jdbc.dataSource(),
                List.of(new ColumnRef("git_commit", "ai_assisted")))).isZero();

        assertThat(jdbc.updates).isEmpty();
    }

    // ---------------------------------------------------------------- 缺才 ALTER

    @Test
    void missingColumnIsAddedWithInstantAndSessionLockWaitIsSetThenRestored() {
        FakeMysql db = new FakeMysql().withColumns("ai_session", "id");
        FakeJdbc jdbc = new FakeJdbc(db);

        int added = SchemaPatchSupport.ensureColumn(jdbc.dataSource(), "ai_session", "version",
                "BIGINT NOT NULL DEFAULT 0 COMMENT 'v'");

        assertThat(added).isEqualTo(1);
        assertThat(jdbc.updates).containsExactly(
                SET_5,
                "ALTER TABLE ai_session ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT 'v', ALGORITHM=INSTANT, LOCK=NONE",
                RESTORE);
    }

    @Test
    void neverIssuesTheMariaDbOnlyIfNotExistsSyntax() {
        FakeMysql db = new FakeMysql().withColumns("t", "id");
        FakeJdbc jdbc = new FakeJdbc(db);

        SchemaPatchSupport.ensureColumns(jdbc.dataSource(), cols("t", "a", "b"));

        assertThat(jdbc.updates).noneMatch(s -> s.toUpperCase().contains("IF NOT EXISTS"));
    }

    @Test
    void onlyTheMissingOnesAreAlteredInInputOrder() {
        FakeMysql db = new FakeMysql().withColumns("t", "id", "b");
        FakeJdbc jdbc = new FakeJdbc(db);

        int added = SchemaPatchSupport.ensureColumns(jdbc.dataSource(), cols("t", "a", "b", "c"));

        assertThat(added).isEqualTo(2);
        List<String> alters = jdbc.updates.stream().filter(s -> s.startsWith("ALTER")).toList();
        assertThat(alters).hasSize(2);
        assertThat(alters.get(0)).contains("ADD COLUMN a ");
        assertThat(alters.get(1)).contains("ADD COLUMN c ");
    }

    @Test
    void missingIndexIsAddedInplaceWithoutLocking() {
        FakeMysql db = new FakeMysql().withIndexes("ai_session", "PRIMARY");
        FakeJdbc jdbc = new FakeJdbc(db);

        int n = SchemaPatchSupport.ensureIndex(jdbc.dataSource(), "ai_session", "idx_agent_last",
                "agent_id, last_activity");

        assertThat(n).isEqualTo(1);
        assertThat(jdbc.updates).contains(
                "ALTER TABLE ai_session ADD INDEX idx_agent_last (agent_id, last_activity), ALGORITHM=INPLACE, LOCK=NONE");
    }

    @Test
    void missingTableIsCreatedExistingTableIsNot() {
        FakeMysql db = new FakeMysql().withColumns("have", "id");
        FakeJdbc jdbc = new FakeJdbc(db);

        int n = SchemaPatchSupport.ensureTables(jdbc.dataSource(), List.of(
                new TableSpec("have", "CREATE TABLE IF NOT EXISTS have (id INT)"),
                new TableSpec("missing", "CREATE TABLE IF NOT EXISTS missing (id INT)")));

        assertThat(n).isEqualTo(1);
        assertThat(jdbc.updates).contains("CREATE TABLE IF NOT EXISTS missing (id INT)")
                .doesNotContain("CREATE TABLE IF NOT EXISTS have (id INT)");
    }

    @Test
    void dropsIndexBeforeColumnsAndOnlyWhatIsPresent() {
        FakeMysql db = new FakeMysql()
                .withColumns("git_commit", "id", "ai_assisted", "ai_session_ids")
                .withIndexes("git_commit", "idx_ai_assisted");
        FakeJdbc jdbc = new FakeJdbc(db);

        SchemaPatchSupport.dropIndexesIfPresent(jdbc.dataSource(), List.of(new IndexRef("git_commit", "idx_ai_assisted")));
        SchemaPatchSupport.dropColumnsIfPresent(jdbc.dataSource(), List.of(
                new ColumnRef("git_commit", "ai_session_ids"),
                new ColumnRef("git_commit", "ai_assist_confidence"),   // 本来就没有：不发
                new ColumnRef("git_commit", "ai_assisted")));

        List<String> alters = jdbc.updates.stream().filter(s -> s.startsWith("ALTER")).toList();
        assertThat(alters).containsExactly(
                "ALTER TABLE git_commit DROP INDEX idx_ai_assisted, ALGORITHM=INPLACE, LOCK=NONE",
                "ALTER TABLE git_commit DROP COLUMN ai_session_ids, ALGORITHM=INSTANT, LOCK=NONE",
                "ALTER TABLE git_commit DROP COLUMN ai_assisted, ALGORITHM=INSTANT, LOCK=NONE");
    }

    // ---------------------------------------------------------------- 算法回退

    @Test
    void instantRejectedByServerFallsBackToInplaceNeverCopy() {
        FakeMysql db = new FakeMysql().withColumns("t", "id");
        db.failures.add(failWhenContains("ALGORITHM=INSTANT", 1845, "ALGORITHM=INSTANT is not supported."));
        FakeJdbc jdbc = new FakeJdbc(db);

        int added = SchemaPatchSupport.ensureColumns(jdbc.dataSource(), cols("t", "a"));

        assertThat(added).isEqualTo(1);
        List<String> alters = jdbc.updates.stream().filter(s -> s.startsWith("ALTER")).toList();
        assertThat(alters).hasSize(2);
        assertThat(alters.get(0)).contains("ALGORITHM=INSTANT");
        assertThat(alters.get(1)).contains("ALGORITHM=INPLACE").contains("LOCK=NONE");
        assertThat(jdbc.updates).noneMatch(s -> s.contains("ALGORITHM=COPY"));
    }

    @Test
    void bothAlgorithmsRejectedGivesUpWithoutCopyAndOtherColumnsStillTried() {
        FakeMysql db = new FakeMysql().withColumns("t", "id");
        db.failures.add(failWhenContains("ADD COLUMN a ", 1846, "ALGORITHM=INPLACE is not supported. Reason: x"));
        FakeJdbc jdbc = new FakeJdbc(db);

        int added = SchemaPatchSupport.ensureColumns(jdbc.dataSource(), cols("t", "a", "b"));

        assertThat(added).as("a 失败、b 成功").isEqualTo(1);
        assertThat(jdbc.updates).noneMatch(s -> s.contains("ALGORITHM=COPY"));
        assertThat(jdbc.updates).anyMatch(s -> s.contains("ADD COLUMN b ") && s.contains("INSTANT"));
    }

    @Test
    void duplicateColumnErrorFromAConcurrentBootIsSuccessNotFallback() {
        FakeMysql db = new FakeMysql().withColumns("t", "id");
        db.failures.add(failWhenContains("ADD COLUMN a ", 1060, "Duplicate column name 'a'"));
        FakeJdbc jdbc = new FakeJdbc(db);

        SchemaPatchSupport.ensureColumns(jdbc.dataSource(), cols("t", "a"));

        assertThat(jdbc.updates.stream().filter(s -> s.startsWith("ALTER"))).hasSize(1); // 没有再去试 INPLACE
    }

    // ---------------------------------------------------------------- 锁等待

    @Test
    void lockWaitTimeoutOnATableStopsFurtherDdlOnThatTableButNotOthers() {
        FakeMysql db = new FakeMysql().withColumns("busy", "id").withColumns("free", "id");
        db.failures.add(failWhenContains("ALTER TABLE busy", 1205, "Lock wait timeout exceeded; try restarting transaction"));
        FakeJdbc jdbc = new FakeJdbc(db);
        List<ColumnSpec> specs = new ArrayList<>();
        specs.addAll(cols("busy", "a", "b", "c"));
        specs.addAll(cols("free", "x"));

        int added = SchemaPatchSupport.ensureColumns(jdbc.dataSource(), specs);

        assertThat(added).isEqualTo(1); // 只有 free.x
        assertThat(jdbc.updates.stream().filter(s -> s.startsWith("ALTER TABLE busy")))
                .as("busy 表只试一次（不再逐列各等 5s），也不回退 INPLACE").hasSize(1);
        assertThat(jdbc.updates).anyMatch(s -> s.startsWith("ALTER TABLE free ADD COLUMN x"));
        assertThat(jdbc.updates).first().isEqualTo(SET_5);
        assertThat(jdbc.updates).last().as("异常路径也要还原会话变量").isEqualTo(RESTORE);
    }

    @Test
    void lockTimeoutNeverPropagatesOutOfTheHelper() {
        FakeMysql db = new FakeMysql().withColumns("t", "id");
        db.failures.add(failWhenContains("ALTER TABLE", 1205, "Lock wait timeout exceeded"));
        FakeJdbc jdbc = new FakeJdbc(db);

        int added = SchemaPatchSupport.ensureColumns(jdbc.dataSource(), cols("t", "a"));

        assertThat(added).isZero();
    }

    // ---------------------------------------------------------------- 无法判断则不动

    @Test
    void informationSchemaFailureMeansNoDdl() {
        FakeMysql db = new FakeMysql().withColumns("t", "id");
        db.infoSchemaFailure = new SQLException("boom", "HY000", 1142);
        FakeJdbc jdbc = new FakeJdbc(db);

        int added = SchemaPatchSupport.ensureColumns(jdbc.dataSource(), cols("t", "a"));

        assertThat(added).isZero();
        assertThat(jdbc.updates).isEmpty();
    }

    @Test
    void absentTableIsSkippedNotAltered() {
        FakeJdbc jdbc = new FakeJdbc(new FakeMysql());

        int added = SchemaPatchSupport.ensureColumns(jdbc.dataSource(), cols("no_such_table", "a"));

        assertThat(added).isZero();
        assertThat(jdbc.updates).isEmpty();
    }

    // ---------------------------------------------------------------- 纯函数

    @Test
    void missingColumnsIsCaseInsensitiveAndSkipsUnknownOrEmptyTables() {
        Map<String, Set<String>> existing = Map.of(
                "t", Set.of("id", "already"),
                "empty", Set.of());
        List<ColumnSpec> wanted = List.of(
                new ColumnSpec("T", "ALREADY", "INT"),
                new ColumnSpec("t", "fresh", "INT"),
                new ColumnSpec("empty", "x", "INT"),
                new ColumnSpec("unknown", "y", "INT"));

        assertThat(SchemaPatchSupport.missingColumns(wanted, existing))
                .extracting(ColumnSpec::column).containsExactly("fresh");
    }

    @Test
    void classifyMapsMysqlErrorCodesAndMessages() {
        assertThat(SchemaPatchSupport.classify(new SQLException("x", "42S21", 1060))).isEqualTo(Failure.ALREADY_IN_DESIRED_STATE);
        assertThat(SchemaPatchSupport.classify(new SQLException("x", "42000", 1061))).isEqualTo(Failure.ALREADY_IN_DESIRED_STATE);
        assertThat(SchemaPatchSupport.classify(new SQLException("x", "42000", 1091))).isEqualTo(Failure.ALREADY_IN_DESIRED_STATE);
        assertThat(SchemaPatchSupport.classify(new SQLException("x", "HY000", 1205))).isEqualTo(Failure.LOCK_TIMEOUT);
        assertThat(SchemaPatchSupport.classify(new SQLException("x", "40001", 1213))).isEqualTo(Failure.LOCK_TIMEOUT);
        assertThat(SchemaPatchSupport.classify(new SQLException("x", "0A000", 1845))).isEqualTo(Failure.ALGORITHM_UNSUPPORTED);
        assertThat(SchemaPatchSupport.classify(new SQLException("x", "0A000", 1846))).isEqualTo(Failure.ALGORITHM_UNSUPPORTED);
        assertThat(SchemaPatchSupport.classify(new SQLException("x", "HY000", 1800))).isEqualTo(Failure.ALGORITHM_UNSUPPORTED);
        assertThat(SchemaPatchSupport.classify(new SQLException("x", "HY000", 4092))).isEqualTo(Failure.ALGORITHM_UNSUPPORTED);
        assertThat(SchemaPatchSupport.classify(new SQLException("Access denied", "42000", 1142))).isEqualTo(Failure.OTHER);
        // 无错误码时看消息
        assertThat(SchemaPatchSupport.classify(new SQLException("Lock wait timeout exceeded"))).isEqualTo(Failure.LOCK_TIMEOUT);
        assertThat(SchemaPatchSupport.classify(new SQLException("Duplicate column name 'a'"))).isEqualTo(Failure.ALREADY_IN_DESIRED_STATE);
    }

    @Test
    void ddlBuildersAreExplicitAboutAlgorithmAndLock() {
        ColumnSpec c = new ColumnSpec("t", "c", "INT DEFAULT NULL");
        assertThat(SchemaPatchSupport.addColumnSql(c, "INSTANT"))
                .isEqualTo("ALTER TABLE t ADD COLUMN c INT DEFAULT NULL, ALGORITHM=INSTANT, LOCK=NONE");
        assertThat(SchemaPatchSupport.addColumnSql(c, "INPLACE")).endsWith("ALGORITHM=INPLACE, LOCK=NONE");
        assertThat(SchemaPatchSupport.addIndexSql(new IndexSpec("t", "i", "a, b")))
                .isEqualTo("ALTER TABLE t ADD INDEX i (a, b), ALGORITHM=INPLACE, LOCK=NONE");
    }
}
