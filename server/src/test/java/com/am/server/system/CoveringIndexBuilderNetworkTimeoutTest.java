package com.am.server.system;

import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * prod 连接池带 {@code socketTimeout}（默认 10min，兜网络黑洞）。夜间在线建覆盖索引要几十分钟才返回，
 * 必须在发 DDL 之前把这条连接的网络超时放宽，否则会被驱动半途切断（见 application-prod.yml 的说明）。
 */
class CoveringIndexBuilderNetworkTimeoutTest {

    @Test
    void networkTimeoutIsLifted_beforeEachDdlStatement() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement existsQuery = mock(PreparedStatement.class);
        ResultSet noRows = mock(ResultSet.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(any())).thenReturn(existsQuery);
        when(existsQuery.executeQuery()).thenReturn(noRows);
        when(noRows.next()).thenReturn(false); // 索引都不存在 → 两张表都要建
        when(conn.createStatement()).thenReturn(stmt);

        new CoveringIndexBuilder(ds, mock(DynamicScheduledTaskManager.class)).buildMissing();

        InOrder order = inOrder(conn, stmt);
        for (int i = 0; i < CoveringIndexBuilder.INDEXES.size(); i++) {
            order.verify(conn).setNetworkTimeout(any(Executor.class), eq(CoveringIndexBuilder.DDL_NETWORK_TIMEOUT_MS));
            order.verify(stmt).execute(startsWith("SET SESSION lock_wait_timeout"));
            order.verify(stmt).execute(startsWith("ALTER TABLE"));
        }
        // 每张表恰好一次放宽 + 一次 DDL，不多不少
        org.mockito.Mockito.verify(conn, times(CoveringIndexBuilder.INDEXES.size()))
                .setNetworkTimeout(any(Executor.class), eq(CoveringIndexBuilder.DDL_NETWORK_TIMEOUT_MS));
    }

    @Test
    void ddlTimeout_isFiniteAndLongerThanTheDefaultPoolSocketTimeout() {
        // 有限：真遇到网络黑洞时定时任务线程不会永远卡死；且必须大于池默认的 10min，否则放宽形同虚设
        org.junit.jupiter.api.Assertions.assertTrue(CoveringIndexBuilder.DDL_NETWORK_TIMEOUT_MS > 10 * 60 * 1000);
        org.junit.jupiter.api.Assertions.assertTrue(CoveringIndexBuilder.DDL_NETWORK_TIMEOUT_MS > 0);
    }
}
