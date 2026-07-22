package com.am.server.domain.git;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * commit → AI 产出归因预计算实体（《管理后台-产出归因与能力使用分析 v1.0》§2.6）
 * 每个非 merge commit 一行（含 tier=NONE 未命中行，占比分母来自本表）；merge 全口径排除不落行。
 * 由 GitCommitAttributionEngine 维护，是纯派生表——口径变更可整表重算，不污染 git_commit。
 * <p>单一归属不变式：一个 commit 恰好一行、恰好归属至多一个会话，任意维度组合求和 = 总数。
 * <p>无 JPA 审计列：computed_time 由引擎显式写入（重算语义下"创建/更新时间"无意义）。
 * gz
 */
@Entity
@Table(name = "git_commit_attribution")
@Getter
@Setter
@NoArgsConstructor
public class GitCommitAttribution {

    public static final String TIER_B = "B";
    public static final String TIER_A = "A";
    public static final String TIER_NONE = "NONE";

    /** B 档窗口内找不到该工具会话时的 model 占位值。 */
    public static final String MODEL_UNKNOWN = "unknown";

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "gitCommitAttributionIdGen")
    @TableGenerator(name = "gitCommitAttributionIdGen", table = "id_sequences",
            pkColumnName = "seq_name", valueColumnName = "next_val",
            pkColumnValue = "git_commit_attribution", allocationSize = 50)
    private Long id;

    @Column(name = "commit_id", nullable = false)
    private Long commitId;

    /** 冗余自 git_commit，避免透视 JOIN */
    @Column(name = "repo_url", nullable = false, length = 512)
    private String repoUrl;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    /** 归属会话的 project_name（NONE 行为 NULL） */
    @Column(name = "project_name", length = 128)
    private String projectName;

    @Column(name = "commit_time", nullable = false)
    private LocalDateTime commitTime;

    @Column(name = "lines_added", nullable = false)
    private Integer linesAdded = 0;

    @Column(name = "lines_deleted", nullable = false)
    private Integer linesDeleted = 0;

    /** B 确定 / A 疑似 / NONE 未命中 */
    @Column(name = "tier", nullable = false, length = 8)
    private String tier;

    /** B 档命中的 trailer 规则名 */
    @Column(name = "trailer_kind", length = 32)
    private String trailerKind;

    /** 归属会话（B 档窗口内找不到该工具会话时可空）；前端 commit 明细可跳 /sessions/:id 核查 */
    @Column(name = "session_id")
    private Long sessionId;

    /** 归属工具（B 档取 trailer 映射，A 档取归属会话） */
    @Column(name = "target_type", length = 32)
    private String targetType;

    /** 归属模型（B 档无会话时为 {@link #MODEL_UNKNOWN}） */
    @Column(name = "model", length = 128)
    private String model;

    /** 归属会话活动区间与 commit ±30min 窗口的重叠秒数（核查用） */
    @Column(name = "overlap_seconds")
    private Integer overlapSeconds;

    /** 1=上线前历史回溯所得（前端趋势图对回溯区间注明"回溯推算"） */
    @Column(name = "backfilled", nullable = false, columnDefinition = "TINYINT")
    private Integer backfilled = 0;

    @Column(name = "computed_time", nullable = false)
    private LocalDateTime computedTime;
}
