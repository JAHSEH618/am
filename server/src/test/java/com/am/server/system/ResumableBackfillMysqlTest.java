package com.am.server.system;

import com.am.server.Application;
import com.am.server.system.ResumableBackfill.FailedState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 MySQL 上验证回填状态的 sys_config 读写（upsert / 读 / 删）与骨架端到端：
 * 进度键、失败清单、完成标记的落库形态。用 zz.* 前缀的键，用例前后清理。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class ResumableBackfillMysqlTest {

    private static final String MARKER = "zz.resumable_backfill_test_v1";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanBefore() {
        cleanKeys();
    }

    @AfterEach
    void cleanAfter() {
        cleanKeys();
    }

    private void cleanKeys() {
        jdbc.update("DELETE FROM sys_config WHERE config_key LIKE 'zz.resumable_backfill_test%'");
    }

    private String value(String key) {
        return jdbc.query("SELECT config_value FROM sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getString(1) : null, key);
    }

    @Test
    void configHelpersUpsertReadAndDelete() throws Exception {
        String key = MARKER + ".progress";
        assertThat(OneShotBackfillSupport.readConfig(dataSource, key)).isNull();

        OneShotBackfillSupport.upsertConfig(dataSource, key, "2026-07-03", "desc");
        assertThat(OneShotBackfillSupport.readConfig(dataSource, key)).isEqualTo("2026-07-03");

        OneShotBackfillSupport.upsertConfig(dataSource, key, "2026-07-04", "desc");
        assertThat(OneShotBackfillSupport.readConfig(dataSource, key)).as("已存在则覆盖").isEqualTo("2026-07-04");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_config WHERE config_key = ?", Integer.class, key)).isEqualTo(1);

        OneShotBackfillSupport.deleteConfig(dataSource, key);
        assertThat(OneShotBackfillSupport.readConfig(dataSource, key)).isNull();
    }

    @Test
    void dayPassPersistsFailedListAndMarkerThenRetryRecoversAndClearsIt() throws Exception {
        ResumableBackfill bf = new ResumableBackfill(ResumableBackfill.Store.jdbc(dataSource), MARKER, "test marker", 0);

        var r = bf.runDayPass(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 4), label -> {
            if (label.equals("2026-07-02")) {
                throw new IllegalStateException("bad day");
            }
        });

        assertThat(r.failed()).isEqualTo(1);
        assertThat(value(MARKER)).isEqualTo("done");
        assertThat(value(MARKER + ".progress")).as("完成后游标被清掉").isNull();
        FailedState st = FailedState.parse(value(MARKER + ".failed"));
        assertThat(st.items()).containsExactly("2026-07-02");
        assertThat(st.attempts()).isZero();

        // 下次启动：标记已在，只重试失败日
        ResumableBackfill next = new ResumableBackfill(ResumableBackfill.Store.jdbc(dataSource), MARKER, "test marker", 0);
        assertThat(next.hasPendingWork()).isTrue();
        var retry = next.retryFailed(label -> { });
        assertThat(retry.recovered()).isEqualTo(1);
        assertThat(value(MARKER + ".failed")).isNull();
        assertThat(new ResumableBackfill(ResumableBackfill.Store.jdbc(dataSource), MARKER, "t", 0).hasPendingWork()).isFalse();
    }

    @Test
    void interruptedRunLeavesTheCursorInSysConfigForTheNextBoot() throws Exception {
        ResumableBackfill bf = new ResumableBackfill(ResumableBackfill.Store.jdbc(dataSource), MARKER, "test marker", 0);

        try {
            bf.runDayPass(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 9), label -> {
                if (label.equals("2026-07-05")) {
                    throw new InterruptedException("shutdown");
                }
            });
        } catch (InterruptedException expected) {
            // 线程中断标志无所谓，测试线程不复用
        }

        assertThat(value(MARKER)).isNull();
        assertThat(value(MARKER + ".progress")).isEqualTo("2026-07-05");
    }
}
