package com.am.server.system;

import com.am.server.Application;
import com.am.server.system.SchemaPatchSupport.ColumnRef;
import com.am.server.system.SchemaPatchSupport.ColumnSpec;
import com.am.server.system.SchemaPatchSupport.IndexRef;
import com.am.server.system.SchemaPatchSupport.IndexSpec;
import com.am.server.system.SchemaPatchSupport.TableSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 MySQL 上验证 SchemaPatchSupport：INSTANT 加列 / 删列、缺才建索引与表、稳态零 DDL，
 * 以及“别的会话占着表的元数据锁时，补丁在数秒内放弃而不是等一年（默认 lock_wait_timeout）”。
 * 用一张自建的临时表，不碰业务表。DDL 隐式提交，故不加 @Transactional，用例后自己 DROP。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class SchemaPatchSupportMysqlTest {

    private static final String T = "zz_schema_patch_probe";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void createProbeTable() {
        jdbc.execute("DROP TABLE IF EXISTS " + T);
        jdbc.execute("CREATE TABLE " + T + " (id BIGINT NOT NULL AUTO_INCREMENT, PRIMARY KEY (id)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO " + T + " () VALUES ()");
    }

    @AfterEach
    void dropProbeTable() {
        jdbc.execute("DROP TABLE IF EXISTS " + T);
    }

    private boolean hasColumn(String col) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM information_schema.columns WHERE table_schema = DATABASE()"
                        + " AND table_name = ? AND column_name = ?", Boolean.class, T, col));
    }

    private boolean hasIndex(String idx) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM information_schema.statistics WHERE table_schema = DATABASE()"
                        + " AND table_name = ? AND index_name = ?", Boolean.class, T, idx));
    }

    @Test
    void addsMissingColumnsOnRealMysqlThenIsANoOpTheSecondTime() {
        List<ColumnSpec> specs = List.of(
                new ColumnSpec(T, "c_json", "JSON DEFAULT NULL COMMENT 'j'"),
                new ColumnSpec(T, "c_int", "INT NOT NULL DEFAULT 0 COMMENT 'i'"),
                new ColumnSpec(T, "c_dt", "DATETIME(3) DEFAULT NULL"));

        assertThat(SchemaPatchSupport.ensureColumns(dataSource, specs)).isEqualTo(3);
        assertThat(hasColumn("c_json") && hasColumn("c_int") && hasColumn("c_dt")).isTrue();
        assertThat(jdbc.queryForObject("SELECT c_int FROM " + T, Integer.class))
                .as("存量行拿到 NOT NULL DEFAULT").isZero();

        assertThat(SchemaPatchSupport.ensureColumns(dataSource, specs)).as("稳态：零 DDL").isZero();
    }

    @Test
    void indexesTablesAndDropsOnRealMysql() {
        jdbc.execute("ALTER TABLE " + T + " ADD COLUMN a INT NULL, ADD COLUMN b INT NULL");

        assertThat(SchemaPatchSupport.ensureIndexes(dataSource, List.of(new IndexSpec(T, "idx_ab", "a, b")))).isEqualTo(1);
        assertThat(hasIndex("idx_ab")).isTrue();
        assertThat(SchemaPatchSupport.ensureIndexes(dataSource, List.of(new IndexSpec(T, "idx_ab", "a, b")))).isZero();

        assertThat(SchemaPatchSupport.dropIndexesIfPresent(dataSource, List.of(new IndexRef(T, "idx_ab")))).isEqualTo(1);
        assertThat(hasIndex("idx_ab")).isFalse();
        assertThat(SchemaPatchSupport.dropIndexesIfPresent(dataSource, List.of(new IndexRef(T, "idx_ab")))).isZero();

        assertThat(SchemaPatchSupport.dropColumnsIfPresent(dataSource,
                List.of(new ColumnRef(T, "a"), new ColumnRef(T, "no_such_col")))).isEqualTo(1);
        assertThat(hasColumn("a")).isFalse();

        String t2 = T + "_2";
        try {
            String ddl = "CREATE TABLE IF NOT EXISTS " + t2 + " (id INT NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB";
            assertThat(SchemaPatchSupport.ensureTables(dataSource, List.of(new TableSpec(t2, ddl)))).isEqualTo(1);
            assertThat(SchemaPatchSupport.ensureTables(dataSource, List.of(new TableSpec(t2, ddl)))).isZero();
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS " + t2);
        }
    }

    /**
     * 另一个会话 LOCK TABLES … WRITE 占着表的元数据锁（长事务 / 在线 DDL 的等价物）：
     * 补丁必须在 ~lock_wait_timeout(5s) 内放弃并返回，列没加成、也没抛异常，会话变量被还原。
     */
    @Test
    void doesNotBlockForeverWhenAnotherSessionHoldsTheTablesMetadataLock() throws Exception {
        try (Connection holder = dataSource.getConnection(); Statement st = holder.createStatement()) {
            st.execute("LOCK TABLES " + T + " WRITE");
            try (Connection c = dataSource.getConnection()) {
                SingleConnectionDataSource single = new SingleConnectionDataSource(c, true);
                long before = new JdbcTemplate(single).queryForObject("SELECT @@SESSION.lock_wait_timeout", Long.class);

                long t0 = System.nanoTime();
                int added = SchemaPatchSupport.ensureColumns(single,
                        List.of(new ColumnSpec(T, "c_blocked", "INT NOT NULL DEFAULT 0")));
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

                assertThat(added).isZero();
                assertThat(elapsedMs).as("等锁上限 %ds，绝不是默认的一年", SchemaPatchSupport.DDL_LOCK_WAIT_SECONDS)
                        .isLessThan(SchemaPatchSupport.DDL_LOCK_WAIT_SECONDS * 1000L + 5_000L);
                assertThat(new JdbcTemplate(single).queryForObject("SELECT @@SESSION.lock_wait_timeout", Long.class))
                        .as("会话变量必须还原，否则会污染连接池里的这条连接").isEqualTo(before);
            } finally {
                st.execute("UNLOCK TABLES");
            }
        }
        assertThat(hasColumn("c_blocked")).isFalse();
        // 锁释放后下一次启动就能补上
        assertThat(SchemaPatchSupport.ensureColumns(dataSource,
                List.of(new ColumnSpec(T, "c_blocked", "INT NOT NULL DEFAULT 0")))).isEqualTo(1);
    }

    /** 稳态检查只读 information_schema，不能被别的会话的表锁挡住（补丁启动不该被在线建索引卡住）。 */
    @Test
    void steadyStateCheckIsNotBlockedByATableLock() throws Exception {
        jdbc.execute("ALTER TABLE " + T + " ADD COLUMN present_col INT NULL");
        try (Connection holder = dataSource.getConnection(); Statement st = holder.createStatement()) {
            st.execute("LOCK TABLES " + T + " WRITE");
            try {
                long t0 = System.nanoTime();
                int added = SchemaPatchSupport.ensureColumns(dataSource,
                        List.of(new ColumnSpec(T, "present_col", "INT NULL")));
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

                assertThat(added).isZero();
                assertThat(elapsedMs).as("只查 information_schema，不发 ALTER，不该等表锁").isLessThan(3_000L);
            } finally {
                st.execute("UNLOCK TABLES");
            }
        }
    }
}
