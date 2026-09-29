package com.am.server.system;

import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 控制台整窗聚合的覆盖索引：夜间低峰在线构建（存量库；新库见 schema.sql 内联）。
 *
 * <p>项目透视 / 模型分布 / 热力图 / 仪表盘 Top 等按窗口扫 {@code ai_session_event}，员工数据按窗口扫
 * {@code ai_session_message}。原有 (target_type, event_time) 之类索引能定位区间，但不覆盖查询列：
 * 窗内每一行（含最后被 event_type 过滤掉的 STATUS_CHANGE / TOOL_CALL）都要回表一次，30 天窗口就是百万级随机读。
 * 覆盖索引把过滤列与聚合列都放进二级索引，整窗聚合只读索引页。
 *
 * <p>为什么不在启动时建：这两张是库里最大的表，建索引要数分钟到数十分钟、占额外磁盘（排序临时文件 + 索引本身）；
 * 且 ALTER 收尾要拿表的元数据锁，拿锁期间会挡住后续所有读写。所以：
 * <ul>
 *   <li>挂到动态调度，默认每天 03:30 跑，UI 可改时间 / 停用 / 手动触发；索引都已存在时只做一次 information_schema 查询；</li>
 *   <li>{@code ALGORITHM=INPLACE, LOCK=NONE}：构建期间不阻塞 DML；</li>
 *   <li>会话级 {@code lock_wait_timeout} 压到 {@value #MDL_WAIT_SECONDS}s：拿不到元数据锁就放弃、次日再试，
 *       而不是排在长查询后面把 ingest 一起堵死（MySQL 默认 lock_wait_timeout 是一年）。</li>
 * </ul>
 * 上线前请确认磁盘余量（约为两张表数据量的 30%～50%）。
 * gz
 */
@Component
@RequiredArgsConstructor
public class CoveringIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(CoveringIndexBuilder.class);

    public static final String TASK_CODE = "covering_index_build";

    static final int MDL_WAIT_SECONDS = 10;

    /**
     * 建索引期间放宽的 JDBC 网络（socket 读）超时：4h。prod 连接池带 {@code socketTimeout}（application-prod.yml，
     * 默认 10min，用来兜网络黑洞），而大表 {@code ALTER … ADD INDEX} 要几十分钟才返回第一个字节，
     * 不放宽会被驱动在半途切断连接。仍取一个有限的大值而不是 0：真遇到黑洞时定时任务线程至多卡这么久。
     * HikariCP 在连接归还时会把网络超时复位回池配置值，不会污染其他借用者。
     */
    static final int DDL_NETWORK_TIMEOUT_MS = 4 * 60 * 60 * 1000;

    record IndexSpec(String table, String name, String columns) {}

    static final List<IndexSpec> INDEXES = List.of(
            new IndexSpec("ai_session_event", "idx_window_cover",
                    "target_type, event_time, event_type, ai_session_id, user_code, "
                            + "tokens_delta, input_tokens_delta, output_tokens_delta, messages_delta"),
            new IndexSpec("ai_session_message", "idx_window_cover",
                    "target_type, message_time, ai_session_id, user_code, role, "
                            + "slash_command_count, slash_skill_count"));

    private final DataSource dataSource;
    private final DynamicScheduledTaskManager scheduledTaskManager;

    @PostConstruct
    public void registerDynamicTask() {
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE,
                        "控制台覆盖索引在线构建",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 30 3 * * *",
                        true,
                        true,
                        true,
                        "在 ai_session_event / ai_session_message 上在线补建整窗聚合的覆盖索引（ALGORITHM=INPLACE, "
                                + "LOCK=NONE，元数据锁最多等 " + MDL_WAIT_SECONDS + "s，拿不到就次日再试）。"
                                + "索引已存在时为空操作。大表首次构建耗时较长、占额外磁盘，请放在低峰执行。",
                        "Asia/Shanghai"),
                this::buildMissing);
    }

    public void buildMissing() {
        for (IndexSpec spec : INDEXES) {
            if (indexExists(spec)) {
                continue;
            }
            long start = System.currentTimeMillis();
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                c.setNetworkTimeout(Runnable::run, DDL_NETWORK_TIMEOUT_MS);
                st.execute("SET SESSION lock_wait_timeout = " + MDL_WAIT_SECONDS);
                st.execute(ddl(spec));
                log.info("covering index built: {}.{} in {}s", spec.table(), spec.name(),
                        (System.currentTimeMillis() - start) / 1000);
            } catch (SQLException e) {
                log.warn("covering index {}.{} not built (will retry next run): {}",
                        spec.table(), spec.name(), e.getMessage());
            }
        }
    }

    static String ddl(IndexSpec spec) {
        return "ALTER TABLE " + spec.table() + " ADD INDEX " + spec.name() + " (" + spec.columns() + ")"
                + ", ALGORITHM=INPLACE, LOCK=NONE";
    }

    private boolean indexExists(IndexSpec spec) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT 1 FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                     LIMIT 1
                     """)) {
            ps.setString(1, spec.table());
            ps.setString(2, spec.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            // 查不到就当已存在：宁可今晚不建，也不在不确定状态下对大表发 DDL
            log.warn("covering index existence check failed for {}.{}: {}", spec.table(), spec.name(), e.getMessage());
            return true;
        }
    }
}
