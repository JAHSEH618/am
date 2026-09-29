package com.am.server.service;

import com.am.server.Application;
import com.am.server.domain.ai.AiSessionMessage;
import com.am.server.domain.ai.AiSessionMessageBlob;
import com.am.server.domain.ai.AiSessionMessageBlobLink;
import com.am.server.domain.ai.AiSessionMessageBlobLinkRepository;
import com.am.server.domain.ai.AiSessionMessageBlobRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容保留期清理（默认关闭）打真库验证：只清 content_text / content_parts_json 与 blob 字节，保留行与计数；
 * 主键游标只向前、停在第一条仍在保留期内的行；单次运行限行数；blob 只在不再被"活消息"引用时才清。
 * 测试跑在一个会回滚的事务里，清理器的分批事务加入其中；sys_config 用 mock，不写真表。
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class AiSessionContentRetentionCleanerTest {

    private static final long SESSION_ID = -998833L;
    private static final LocalDateTime OLD = LocalDateTime.now().minusDays(400);
    private static final LocalDateTime RECENT = LocalDateTime.now().minusDays(1);

    @Autowired
    AiSessionMessageRepository messages;
    @Autowired
    AiSessionMessageBlobRepository blobs;
    @Autowired
    AiSessionMessageBlobLinkRepository links;
    @Autowired
    PlatformTransactionManager tm;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;

    private final SystemConfigService config = mock(SystemConfigService.class);
    private AiSessionContentRetentionCleaner cleaner;
    private long startCursor;
    private int seq;

    @BeforeEach
    void setUp() {
        cleaner = new AiSessionContentRetentionCleaner(messages, links, blobs, config,
                mock(DynamicScheduledTaskManager.class), tm);
        // 游标放在现有最大 id 之后：只处理本测试造的行（id 由 TABLE 生成器单调分配）
        startCursor = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM ai_session_message", Long.class);
        when(config.getInt(AiSessionContentRetentionCleaner.KEY_RETENTION_DAYS, 0)).thenReturn(30);
        when(config.getInt(AiSessionContentRetentionCleaner.KEY_MAX_ROWS_PER_RUN, 5_000)).thenReturn(5_000);
        when(config.getString(AiSessionContentRetentionCleaner.KEY_CURSOR, "0")).thenReturn(String.valueOf(startCursor));
    }

    @Test
    void disabledByDefault_touchesNothing() {
        when(config.getInt(AiSessionContentRetentionCleaner.KEY_RETENTION_DAYS, 0)).thenReturn(0);
        long id = message("user", OLD, "正文", "[{\"type\":\"text\",\"text\":\"正文\"}]");

        assertThat(cleaner.cleanup()).isZero();

        assertThat(contentTextOf(id)).isEqualTo("正文");
        verify(config, never()).set(anyString(), anyString(), anyString());
    }

    @Test
    void clearsOldContentKeepsRowsAndCounters_stopsAtFirstRecentRow_andAdvancesCursor() {
        long old1 = message("user", OLD, "老消息1", "[{\"type\":\"text\",\"text\":\"老消息1\"}]");
        long old2 = message("assistant", OLD, "老消息2", "[{\"type\":\"text\",\"text\":\"老消息2\"}]");
        long recent = message("user", RECENT, "新消息", "[{\"type\":\"text\",\"text\":\"新消息\"}]");
        long oldAfterRecent = message("user", OLD, "排在新消息之后的老行", null);
        em.flush();
        setCreated(old1, OLD);
        setCreated(old2, OLD);
        setCreated(recent, RECENT);
        setCreated(oldAfterRecent, OLD);
        em.clear();

        int cleared = cleaner.cleanup();

        assertThat(cleared).isEqualTo(2);
        for (long id : new long[]{old1, old2}) {
            AiSessionMessage m = messages.findById(id).orElseThrow();
            assertThat(m.getContentText()).isNull();
            assertThat(m.getContentPartsJson()).isNull();
            // 行与口径列保留
            assertThat(m.getRole()).isNotNull();
            assertThat(m.getSequenceNo()).isNotNull();
            assertThat(m.getMessageTime()).isNotNull();
            assertThat(m.getContentKind()).isEqualTo("multipart");
            assertThat(m.getPartsCount()).isEqualTo(1);
            assertThat(m.getSlashCommandCount()).isEqualTo(3);
        }
        assertThat(contentTextOf(recent)).isEqualTo("新消息");
        assertThat(contentTextOf(oldAfterRecent)).as("游标停在第一条保留期内的行之前，其后的老行等它们前面的行变老").isEqualTo("排在新消息之后的老行");
        verify(config).set(eq(AiSessionContentRetentionCleaner.KEY_CURSOR), eq(String.valueOf(old2)), anyString());

        // 游标推进后再跑：没有新东西可清，游标不动
        when(config.getString(AiSessionContentRetentionCleaner.KEY_CURSOR, "0")).thenReturn(String.valueOf(old2));
        org.mockito.Mockito.clearInvocations(config);
        assertThat(cleaner.cleanup()).isZero();
        verify(config, never()).set(anyString(), anyString(), anyString());
    }

    @Test
    void capsHowManyRowsOneRunProcesses() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(message("user", OLD, "老消息" + i, null));
        }
        em.flush();
        ids.forEach(id -> setCreated(id, OLD));
        em.clear();
        when(config.getInt(AiSessionContentRetentionCleaner.KEY_MAX_ROWS_PER_RUN, 5_000)).thenReturn(2);

        assertThat(cleaner.cleanup()).isEqualTo(2);
        assertThat(contentTextOf(ids.get(0))).isNull();
        assertThat(contentTextOf(ids.get(1))).isNull();
        assertThat(contentTextOf(ids.get(2))).isEqualTo("老消息2");
        verify(config).set(eq(AiSessionContentRetentionCleaner.KEY_CURSOR), eq(String.valueOf(ids.get(1))), anyString());

        // 下一拍从游标接着清
        when(config.getString(AiSessionContentRetentionCleaner.KEY_CURSOR, "0")).thenReturn(String.valueOf(ids.get(1)));
        assertThat(cleaner.cleanup()).isEqualTo(2);
        assertThat(contentTextOf(ids.get(3))).isNull();
        assertThat(contentTextOf(ids.get(4))).isEqualTo("老消息4");
    }

    @Test
    void blobBytesAreClearedOnlyWhenNoLiveMessageStillReferencesThem() {
        long oldOnly = message("user", OLD, "带图", "[{\"type\":\"image\"}]");
        long oldShared = message("user", OLD, "带图2", "[{\"type\":\"image\"}]");
        long recentShared = message("user", RECENT, "新消息复用同一张图", "[{\"type\":\"image\"}]");
        long onlyBlob = blob("sha-retention-only", 5_000);
        long sharedBlob = blob("sha-retention-shared", 6_000);
        link(oldOnly, 0, onlyBlob);
        link(oldShared, 0, sharedBlob);
        link(recentShared, 0, sharedBlob);
        em.flush();
        setCreated(oldOnly, OLD);
        setCreated(oldShared, OLD);
        setCreated(recentShared, RECENT);
        em.clear();

        assertThat(cleaner.cleanup()).isEqualTo(2);

        assertThat(blobLength(onlyBlob)).as("只被已清理的消息引用 → 清空字节").isZero();
        assertThat(blobLength(sharedBlob)).as("仍被保留期内的消息引用 → 保留").isEqualTo(6_000);
        // 行 / sha / byte_size 保留（uk_sha256 去重仍然有效）
        assertThat(jdbc.queryForObject("SELECT byte_size FROM ai_session_message_blob WHERE id = ?", Integer.class, onlyBlob))
                .isEqualTo(5_000);
        assertThat(jdbc.queryForObject("SELECT content_sha256 FROM ai_session_message_blob WHERE id = ?", String.class, onlyBlob))
                .isEqualTo("sha-retention-only");
    }

    // ------------------------------------------------------------------ helpers

    private long message(String role, LocalDateTime created, String text, String partsJson) {
        AiSessionMessage m = new AiSessionMessage();
        m.setAiSessionId(SESSION_ID);
        m.setTargetType("cursor");
        m.setUserCode("U-retention");
        m.setRole(role);
        m.setSequenceNo(++seq);
        m.setExternalMessageId("ret-" + SESSION_ID + "-" + seq);
        m.setMessageTime(created);
        m.setContentText(text);
        m.setContentPartsJson(partsJson);
        m.setContentKind(partsJson == null ? "text_only" : "multipart");
        m.setPartsCount(partsJson == null ? 0 : 1);
        m.setHasBinary(partsJson != null && partsJson.contains("image") ? 1 : 0);
        m.setSlashCommandCount(3);
        return messages.save(m).getId();
    }

    private long blob(String sha, int size) {
        AiSessionMessageBlob b = new AiSessionMessageBlob();
        b.setContentSha256(sha);
        b.setMimeType("image/png");
        b.setByteSize(size);
        b.setGzipBlob(new byte[size]);
        return blobs.save(b).getId();
    }

    private void link(long messageId, int partIndex, long blobId) {
        AiSessionMessageBlobLink l = new AiSessionMessageBlobLink();
        l.setMessageId(messageId);
        l.setPartIndex(partIndex);
        l.setBlobId(blobId);
        links.save(l);
    }

    private void setCreated(long id, LocalDateTime t) {
        em.flush();
        jdbc.update("UPDATE ai_session_message SET created_time = ? WHERE id = ?", t, id);
    }

    private String contentTextOf(long id) {
        em.flush();
        return jdbc.queryForObject("SELECT content_text FROM ai_session_message WHERE id = ?", String.class, id);
    }

    private int blobLength(long id) {
        em.flush();
        return jdbc.queryForObject("SELECT LENGTH(gzip_blob) FROM ai_session_message_blob WHERE id = ?", Integer.class, id);
    }
}
