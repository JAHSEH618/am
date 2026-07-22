package com.am.server.domain.summary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 能力使用日聚合实体（《管理后台-产出归因与能力使用分析 v1.0》§3.3）
 * 一行 = (work_date, user_code, kind, item, sub_item)，由 CapabilityDailyAggregator 按日整日重算写入
 * （delete + insert，本表是纯派生物，口径变更可整表重建）。
 *
 * <p>两级维度约定：{@code mcp} / {@code plugin_ns} 同时落两种粒度——
 * <ul>
 *   <li>{@code sub_item = ""} 汇总行：item = server / namespace，session_count 为该一级维度的去重会话数</li>
 *   <li>{@code sub_item != ""} 明细行：mcp = tool 名、plugin_ns = 技能名</li>
 * </ul>
 * 查询必须按粒度过滤（sub_item 为空 / 非空），跨粒度求和会重复计数；
 * {@code skill} / {@code nl_skill} 恒为 sub_item="" 单粒度。
 * gz
 */
@Entity
@Table(name = "capability_daily")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class CapabilityDaily {

    /** 显式技能调用（slash_hits_json kind=skill，含 cursor 首 token 与 codex $技能） */
    public static final String KIND_SKILL = "skill";
    /** 自然语言隐式技能（slash_hits_json kind=nl_skill，NlSkillAttributionSupport 启发式） */
    public static final String KIND_NL_SKILL = "nl_skill";
    /** MCP server 工具调用（tool_name 形如 mcp__&lt;server&gt;__&lt;tool&gt;） */
    public static final String KIND_MCP = "mcp";
    /** 插件市场命名空间技能（token 形如 &lt;namespace&gt;:&lt;skill&gt;） */
    public static final String KIND_PLUGIN_NS = "plugin_ns";

    /** 汇总行的 sub_item 取值（MySQL 唯一键不允许多个 NULL，故用空串） */
    public static final String SUB_ITEM_ROLLUP = "";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "user_code", nullable = false, length = 64)
    private String userCode;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    /** 一级维度：skill 名 / MCP server / 插件 namespace（统一小写） */
    @Column(name = "item", nullable = false, length = 128)
    private String item;

    /** 二级维度：mcp=tool 名、plugin_ns=技能名；汇总行与 skill/nl_skill 恒为空串 */
    @Column(name = "sub_item", nullable = false, length = 256)
    private String subItem = SUB_ITEM_ROLLUP;

    @Column(name = "invoke_count", nullable = false)
    private Integer invokeCount = 0;

    @Column(name = "session_count", nullable = false)
    private Integer sessionCount = 0;

    @CreatedDate
    @Column(name = "created_time", nullable = false, updatable = false)
    private LocalDateTime createdTime;

    @LastModifiedDate
    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
