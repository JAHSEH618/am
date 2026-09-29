package com.am.server.service;

import com.am.server.domain.ai.AiSessionMessageBlobLinkRepository;
import com.am.server.domain.ai.AiSessionMessageBlobRepository;
import com.am.server.domain.ai.AiSessionMessageRepository;
import com.am.server.system.SystemConfigService;
import com.am.server.system.scheduling.DynamicScheduledTaskManager;
import com.am.server.system.scheduling.ScheduledTaskDefinition;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话消息<b>正文与二进制</b>的保留期清理（<b>默认关闭</b>）。
 *
 * <h2>为什么要有 / 为什么默认关</h2>
 * {@code ai_session_message.content_text}（MEDIUMTEXT）/ {@code content_parts_json}（JSON）与
 * {@code ai_session_message_blob.gzip_blob}（MEDIUMBLOB，截图等）是 am 库里仅次于 git patch 的大头，
 * 却只增不减。但它们同时是<b>洞察审计的原文</b>、会话详情页的对话内容、以及个别派生口径的兜底输入，
 * 清掉是不可逆的——所以这个清理器默认 {@code ai.content_retention_days = 0}（关闭），
 * 启用前必须由运维评估下面这些影响，并在测试库演练。
 *
 * <h2>启用前必须评估的影响</h2>
 * <ul>
 *   <li><b>洞察审计</b>：{@code SessionAuditService} 用消息原文拼 LLM prompt。已有的 {@code ai_session_audit} 结论不受影响，
 *       但「强制重跑 / 会话又长了 ≥ reaudit 阈值 / 换 rubric 版本重审」会读到被清空的旧消息——
 *       跨过保留线仍在续聊的长会话，重审时只剩保留期内的对话。保留期应明显长于报告窗口与重审周期（建议 ≥ 90 天）。</li>
 *   <li><b>会话详情 / 消息列表</b>：老会话的每条消息只剩角色 / 序号 / 时间 / token / 工具名，正文与图片为空
 *       （前端拿到 {@code content_text = null}、{@code content_parts = null}）。典型排查（看今天 / 本周的对话）不受影响。</li>
 *   <li><b>capability_daily 重算</b>：无 TOOL_CALL 事件的 provider 的 MCP 统计有一条 {@code content_parts_json} 兜底路
 *       （{@code CapabilityDailyAggregator}）。对已过保留期的日期做整日重算 / 历史 backfill，这部分会丢；
 *       日常的当日 + 昨日滚动重算不受影响。</li>
 *   <li><b>斜杠 Top 回算</b>：只在 {@code insight.slash_full_backfill_v1} marker 缺失时才回读 content_text，已完成的库无影响。</li>
 *   <li><b>不受影响</b>：行数与全部计数列（user/assistant/total、role、sequence_no、conversation_order、message_time、
 *       input/output tokens、tool_name、slash_hits_json 与两个 slash 计数、content_kind / parts_count / has_binary）、
 *       {@code ai_session_event}（本就没有大文本列，不需要清）——所以 daily_summary、归因、报告评分口径不变。</li>
 * </ul>
 *
 * <h2>行为</h2>
 * <ul>
 *   <li><b>只置 NULL / 置空，不删行</b>：{@code content_text}、{@code content_parts_json} → NULL；
 *       blob 的 {@code gzip_blob} → 空字节（列 NOT NULL；行、sha256、mime、byte_size、宽高保留）。</li>
 *   <li><b>按「入库时间」判老</b>：{@code created_time < now − N 天}（不是消息自带的 message_time——agent 首次部署会回填几个月的历史，
 *       按 message_time 会在导入的当天就把它们清光；created_time 与主键单调对应，可以用主键游标推进）。</li>
 *   <li><b>主键游标</b>（sys_config {@code ai.content_retention_cursor}，内部状态勿手改）：只往前扫、扫过的行不再重扫；
 *       遇到第一条尚在保留期内的行就停（不会被个别时钟异常的行卡死——created_time 是服务端写的）。</li>
 *   <li><b>限行数控制 ROW binlog 放大</b>：每次运行最多处理 {@code ai.content_retention_max_rows_per_run} 行（默认 5000，
 *       硬上限——已清理过 / 无内容的行也计入，所以写入量上界确定），
 *       分批（每批 ≤ {@value #CLEAR_BATCH} 行、含二进制的消息 ≤ {@value #MAX_BINARY_MESSAGES_PER_BATCH} 条）、每批一个短事务。
 *       ROW 格式会把整行前 / 后镜像各记一遍，存量首清可能是百万行 / 数十 GB，靠多天摊平（每天 04:30 一拍）。</li>
 *   <li><b>blob 只在「不再被任何仍带 parts 的消息引用」时才清</b>：同一张图被新消息复用（sha 去重）时不会被误清；
 *       之后若又有消息带来同一份内容，{@code MessageContentIngestService#storeBlob} 会把字节补回来。</li>
 * </ul>
 *
 * <h2>启用</h2>
 * 在测试库演练后，把 {@code sys_config} 的 {@code ai.content_retention_days} 设为天数（如 180）即可，任务本身默认已启用
 * （定时任务页可改 cron / 手动触发）。设回 0 立即停止；已清理的内容不可恢复。
 * gz
 */
@Component
public class AiSessionContentRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(AiSessionContentRetentionCleaner.class);

    public static final String TASK_CODE = "ai_content_retention";
    /** 保留天数；0 或负数 = 关闭（默认）。 */
    public static final String KEY_RETENTION_DAYS = "ai.content_retention_days";
    /** 单次运行最多清理的消息行数（控制 ROW binlog 放大）。 */
    public static final String KEY_MAX_ROWS_PER_RUN = "ai.content_retention_max_rows_per_run";
    /** 内部状态：主键游标，此 id 及以前的行都已按当时的截止时间处理完。 */
    public static final String KEY_CURSOR = "ai.content_retention_cursor";

    static final int DEFAULT_RETENTION_DAYS = 0;
    static final int DEFAULT_MAX_ROWS_PER_RUN = 5_000;

    /** 沿主键取「id / created_time / has_binary」的窗口大小。 */
    static final int SCAN_WINDOW = 500;
    /** 单个清理事务处理的消息行数上限。 */
    static final int CLEAR_BATCH = 200;
    /** 单个清理事务里含二进制的消息数上限：每条最多 8 个 ≤1MB 的 blob，限制单事务写入的字节量。 */
    static final int MAX_BINARY_MESSAGES_PER_BATCH = 10;
    /** 单次运行最多扫描的行数（只读 id 等窄列，便宜，但也要有上界）。 */
    static final int MAX_SCAN_ROWS_PER_RUN = 200_000;

    private final AiSessionMessageRepository messageRepository;
    private final AiSessionMessageBlobLinkRepository linkRepository;
    private final AiSessionMessageBlobRepository blobRepository;
    private final SystemConfigService systemConfigService;
    private final DynamicScheduledTaskManager scheduledTaskManager;
    private final TransactionTemplate tx;

    public AiSessionContentRetentionCleaner(AiSessionMessageRepository messageRepository,
                                            AiSessionMessageBlobLinkRepository linkRepository,
                                            AiSessionMessageBlobRepository blobRepository,
                                            SystemConfigService systemConfigService,
                                            DynamicScheduledTaskManager scheduledTaskManager,
                                            PlatformTransactionManager transactionManager) {
        this.messageRepository = messageRepository;
        this.linkRepository = linkRepository;
        this.blobRepository = blobRepository;
        this.systemConfigService = systemConfigService;
        this.scheduledTaskManager = scheduledTaskManager;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    public void init() {
        systemConfigService.seedIfAbsent(KEY_RETENTION_DAYS, String.valueOf(DEFAULT_RETENTION_DAYS), "int",
                "retention", false,
                "会话消息正文 / 二进制保留天数：超期只清空 content_text / content_parts_json 与 blob 字节，行与计数保留。"
                        + "0 = 不清理（默认）。启用前须评估对洞察重审、会话详情、capability 重算的影响，见 AiSessionContentRetentionCleaner");
        systemConfigService.seedIfAbsent(KEY_MAX_ROWS_PER_RUN, String.valueOf(DEFAULT_MAX_ROWS_PER_RUN), "int",
                "retention", false,
                "内容保留期清理单次运行最多清理的消息行数（ROW binlog 会记整行前后镜像，限行数以控制写放大）");
        systemConfigService.seedIfAbsent(KEY_CURSOR, "0", "int", "retention", false,
                "内容保留期清理的主键游标（内部状态，请勿手改）");
        scheduledTaskManager.register(
                new ScheduledTaskDefinition(
                        TASK_CODE,
                        "会话内容保留期清理",
                        ScheduledTaskDefinition.CATEGORY_BUSINESS,
                        "0 30 4 * * *",
                        true,
                        true,
                        true,
                        "每天 04:30 清空超过保留期的会话消息正文 / 二进制（sys_config " + KEY_RETENTION_DAYS
                                + "，默认 0 = 关闭）。只清 content_text / content_parts_json / blob 字节，行与计数保留；"
                                + "每次最多清 " + KEY_MAX_ROWS_PER_RUN + " 行以控制 binlog 放大。启用前须评估对洞察重审 / 会话详情的影响。",
                        "Asia/Shanghai"),
                this::cleanup);
    }

    /** @return 本次实际被清空内容的消息行数 */
    public int cleanup() {
        int days = systemConfigService.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
        if (days <= 0) {
            log.info("ai content retention: disabled ({}={})", KEY_RETENTION_DAYS, days);
            return 0;
        }
        int maxRows = Math.max(1, systemConfigService.getInt(KEY_MAX_ROWS_PER_RUN, DEFAULT_MAX_ROWS_PER_RUN));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        long startCursor = readCursor();
        long cursor = startCursor;
        int processed = 0;       // 本次运行已处理（送去清理）的消息行数——按它卡上限，binlog 上界因此是确定的
        int clearedMessages = 0;
        int clearedBlobs = 0;
        int scanned = 0;
        try {
            scan:
            while (processed < maxRows && scanned < MAX_SCAN_ROWS_PER_RUN) {
                List<AiSessionMessageRepository.MessageAgeRow> window =
                        messageRepository.findAgeRowsAfterId(cursor, PageRequest.of(0, SCAN_WINDOW));
                if (window.isEmpty()) {
                    break;
                }
                scanned += window.size();
                List<Long> batch = new ArrayList<>(CLEAR_BATCH);
                int binaryInBatch = 0;
                for (AiSessionMessageRepository.MessageAgeRow row : window) {
                    if (row.getCreatedTime() == null || !row.getCreatedTime().isBefore(cutoff)) {
                        // 第一条仍在保留期内的行：游标停在它之前，后面的等它们变老
                        int[] done = flush(batch);
                        processed += batch.size();
                        clearedMessages += done[0];
                        clearedBlobs += done[1];
                        cursor = advance(cursor, batch);
                        break scan;
                    }
                    batch.add(row.getId());
                    if (row.getHasBinary() != null && row.getHasBinary() == 1) {
                        binaryInBatch++;
                    }
                    if (batch.size() >= Math.min(CLEAR_BATCH, maxRows - processed)
                            || binaryInBatch >= MAX_BINARY_MESSAGES_PER_BATCH) {
                        int[] done = flush(batch);
                        processed += batch.size();
                        clearedMessages += done[0];
                        clearedBlobs += done[1];
                        cursor = advance(cursor, batch);
                        batch.clear();
                        binaryInBatch = 0;
                        if (processed >= maxRows) {
                            break scan;
                        }
                    }
                }
                int[] done = flush(batch);
                processed += batch.size();
                clearedMessages += done[0];
                clearedBlobs += done[1];
                cursor = advance(cursor, batch);
            }
        } finally {
            // 即使中途失败也保留已完成批次的进度（未完成的批次没有推进游标，下次重来；重复清理是幂等的）
            if (cursor != startCursor) {
                systemConfigService.set(KEY_CURSOR, String.valueOf(cursor), "retention-cleaner");
            }
        }
        if (clearedMessages > 0 || clearedBlobs > 0) {
            log.info("ai content retention: cleared content of {} messages and bytes of {} blobs older than {} "
                            + "({} days); processed {} rows, cursor {} -> {} (cap {} rows/run)",
                    clearedMessages, clearedBlobs, cutoff, days, processed, startCursor, cursor, maxRows);
        }
        return clearedMessages;
    }

    /** 一批（同一事务）：先清消息内容，再清「只被这批（及更早已清）消息引用」的 blob。 */
    private int[] flush(List<Long> ids) {
        if (ids.isEmpty()) {
            return new int[]{0, 0};
        }
        List<Long> copy = List.copyOf(ids);
        int[] result = tx.execute(status -> {
            int messages = messageRepository.clearContentByIdIn(copy);
            int blobs = 0;
            List<Long> blobIds = linkRepository.findBlobIdsByMessageIdIn(copy);
            if (!blobIds.isEmpty()) {
                blobs = blobRepository.clearBlobsNotReferencedByLiveMessages(blobIds);
            }
            return new int[]{messages, blobs};
        });
        return result == null ? new int[]{0, 0} : result;
    }

    private static long advance(long cursor, List<Long> processed) {
        return processed.isEmpty() ? cursor : Math.max(cursor, processed.get(processed.size() - 1));
    }

    private long readCursor() {
        try {
            return Math.max(0L, Long.parseLong(systemConfigService.getString(KEY_CURSOR, "0").trim()));
        } catch (NumberFormatException e) {
            log.warn("ai content retention: bad cursor value, restarting from 0");
            return 0L;
        }
    }
}
