package com.am.server.system;

import com.am.server.insight.aggregate.SlashHitsJsonSupport;
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
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 存量 {@code ai_session_message.slash_*} 三列按收紧后的提取规则一次性重算
 * （替换 command/skill/noise 行、保留 nl_skill 行，见 {@link SlashHitsJsonSupport#recomputeExtracted}）。
 *
 * <p>新规则是旧规则的严格子集 → 只扫既有命中行；候选谓词无索引，
 * 全量完成后写 sys_config marker（{@value #MARKER_KEY}），后续启动直接跳过。
 * gz
 */
@Configuration
public class SlashAnnotationBackfillPatch {

    private static final Logger log = LoggerFactory.getLogger(SlashAnnotationBackfillPatch.class);

    static final String MARKER_KEY = "insight.slash_backfill_v2";

    private static final String SELECT_BATCH = """
            SELECT m.id, m.content_text, m.slash_command_count, m.slash_skill_count,
                   m.slash_hits_json, s.target_type
            FROM ai_session_message m
            JOIN ai_session s ON s.id = m.ai_session_id
            WHERE m.id > ?
              AND LOWER(m.role) = 'user'
              AND (m.slash_command_count > 0 OR m.slash_skill_count > 0 OR m.slash_hits_json IS NOT NULL)
            ORDER BY m.id
            LIMIT 500
            """;

    private static final String UPDATE_ROW = """
            UPDATE ai_session_message
            SET slash_command_count = ?, slash_skill_count = ?, slash_hits_json = ?
            WHERE id = ?
            """;

    private static final String MARKER_EXISTS = "SELECT 1 FROM sys_config WHERE config_key = ?";

    private static final String MARKER_INSERT = """
            INSERT IGNORE INTO sys_config
                (config_key, config_value, value_type, category, is_secret, description, updated_time, created_time)
            VALUES (?, 'done', 'string', 'insight', 0, 'slash 提取规则收紧后的存量重算完成标记', NOW(), NOW())
            """;

    private record Row(long id, String contentText, int cmd, int sk, String hitsJson, String targetType) {}

    @Bean
    ApplicationRunner backfillSlashAnnotations(DataSource dataSource) {
        return args -> {
            try (Connection c = dataSource.getConnection()) {
                if (markerExists(c)) {
                    return;
                }
                long cursor = 0L;
                long scanned = 0;
                long updated = 0;
                while (true) {
                    List<Row> batch = selectBatch(c, cursor);
                    if (batch.isEmpty()) {
                        break;
                    }
                    updated += rewrite(c, batch);
                    scanned += batch.size();
                    cursor = batch.get(batch.size() - 1).id();
                }
                writeMarker(c);
                log.info("slash annotation backfill: scanned={} updated={}", scanned, updated);
            } catch (SQLException e) {
                log.warn("slash annotation backfill skipped: {}", e.getMessage());
            }
        };
    }

    private static List<Row> selectBatch(Connection c, long cursor) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(SELECT_BATCH)) {
            ps.setLong(1, cursor);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Row(rs.getLong(1), rs.getString(2), rs.getInt(3),
                            rs.getInt(4), rs.getString(5), rs.getString(6)));
                }
            }
        }
        return out;
    }

    private static int rewrite(Connection c, List<Row> batch) throws SQLException {
        int updated = 0;
        try (PreparedStatement ps = c.prepareStatement(UPDATE_ROW)) {
            for (Row row : batch) {
                UserSlashInvocationExtractor.Annotation fresh =
                        UserSlashInvocationExtractor.annotateUserContent(row.contentText(), row.targetType());
                SlashHitsJsonSupport.ExtractedRecompute r = SlashHitsJsonSupport.recomputeExtracted(
                        row.hitsJson(), row.cmd(), row.sk(), fresh);
                if (!r.changed()) {
                    continue;
                }
                ps.setInt(1, r.commandCount());
                ps.setInt(2, r.skillCount());
                if (r.hitsJson() == null) {
                    ps.setNull(3, Types.LONGVARCHAR);
                } else {
                    ps.setString(3, r.hitsJson());
                }
                ps.setLong(4, row.id());
                ps.addBatch();
                updated++;
            }
            ps.executeBatch();
        }
        return updated;
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
