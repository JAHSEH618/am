package com.am.server.system;

import com.am.server.insight.aggregate.UserSlashInvocationExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把从未标注过 slash 的历史 user 消息一次性按现行规则标注，之后斜杠 Top 不再现场回算 content_text。
 *
 * <p>背景：ingest 对"无命中"的 user 消息把 {@code slash_hits_json} 存成 NULL，与"还没标注过的历史行"
 * 无法区分，于是 {@code SlashCommandStatSupport} 的兜底查询（{@code slash_hits_json IS NULL AND content_text…}）
 * 实际命中窗口内几乎所有 user 消息：每次打开模型与工具页 / 员工详情 / 项目详情，都要把整窗提问原文
 * （MEDIUMTEXT，行外存储）读出来、再用同一个提取器跑一遍——对已在 ingest 标注过的行纯属白算。
 *
 * <p>本补丁在后台线程按主键分批（每批 {@value #BATCH} 行、批间小憩）重跑提取器，只回写有命中的行；
 * 全量完成后写 marker（{@value #MARKER_KEY}）并刷新 sys_config 缓存，读路径据此关掉兜底查询。
 * 未完成前读路径保持旧行为，口径不变。
 * gz
 */
@Configuration
public class SlashAnnotationFullBackfillPatch {

    private static final Logger log = LoggerFactory.getLogger(SlashAnnotationFullBackfillPatch.class);

    public static final String MARKER_KEY = "insight.slash_full_backfill_v1";

    static final int BATCH = 500;

    /** 批间停顿：一次性全表扫，不和白天的 ingest / 页面查询抢 IO。 */
    private static final long PAUSE_MS = 20L;

    private static final String SELECT_BATCH = """
            SELECT m.id, m.content_text, s.target_type
            FROM ai_session_message m
            JOIN ai_session s ON s.id = m.ai_session_id
            WHERE m.id > ?
              AND LOWER(m.role) = 'user'
              AND (m.slash_hits_json IS NULL
                   OR JSON_TYPE(m.slash_hits_json) = 'NULL'
                   OR JSON_LENGTH(m.slash_hits_json) = 0)
            ORDER BY m.id
            LIMIT ?
            """;

    private static final String UPDATE_ROW = """
            UPDATE ai_session_message
            SET slash_command_count = ?, slash_skill_count = ?, slash_hits_json = ?
            WHERE id = ?
            """;

    private record Row(long id, String contentText, String targetType) {}

    @Bean
    ApplicationRunner backfillSlashAnnotationsFull(DataSource dataSource, SystemConfigService configService) {
        return args -> {
            try {
                if (OneShotBackfillSupport.markerExists(dataSource, MARKER_KEY)) {
                    return;
                }
            } catch (SQLException e) {
                log.warn("slash full backfill skipped: {}", e.getMessage());
                return;
            }
            Thread t = new Thread(() -> run(dataSource, configService), "slash-full-backfill");
            t.setDaemon(true);
            t.start();
        };
    }

    private static void run(DataSource dataSource, SystemConfigService configService) {
        long cursor = 0L;
        long scanned = 0;
        long updated = 0;
        try {
            while (true) {
                List<Row> batch = selectBatch(dataSource, cursor);
                if (batch.isEmpty()) {
                    break;
                }
                updated += annotate(dataSource, batch);
                scanned += batch.size();
                cursor = batch.get(batch.size() - 1).id();
                Thread.sleep(PAUSE_MS);
            }
            OneShotBackfillSupport.writeMarker(dataSource, MARKER_KEY, "历史 user 消息 slash 全量标注完成标记（斜杠 Top 关闭 content_text 回算）");
            configService.reload();
            log.info("slash full backfill: scanned={} updated={}", scanned, updated);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("slash full backfill aborted at id>{} (will resume next boot): {}", cursor, e.toString());
        }
    }

    private static List<Row> selectBatch(DataSource dataSource, long cursor) throws SQLException {
        List<Row> out = new ArrayList<>(BATCH);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_BATCH)) {
            ps.setLong(1, cursor);
            ps.setInt(2, BATCH);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return out;
    }

    private static int annotate(DataSource dataSource, List<Row> batch) throws SQLException {
        int updated = 0;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(UPDATE_ROW)) {
            for (Row row : batch) {
                UserSlashInvocationExtractor.Annotation ann =
                        UserSlashInvocationExtractor.annotateUserContent(row.contentText(), row.targetType());
                if (ann.slashHitsJson() == null) {
                    continue;
                }
                ps.setInt(1, ann.slashCommandCount());
                ps.setInt(2, ann.slashSkillCount());
                ps.setString(3, ann.slashHitsJson());
                ps.setLong(4, row.id());
                ps.addBatch();
                updated++;
            }
            if (updated > 0) {
                ps.executeBatch();
            }
        }
        return updated;
    }
}
