// 能力使用分析页前端常量：卡片/列释义字典 + kind 展示文案。
// 与 docs/design/管理后台-产出归因与能力使用分析-v1.0.md 对齐；
// 释义模式照 pages/Analysis/constants.ts 的 METRIC_HELP（是什么 / 怎么算 / 怎么读）。

/** 矩阵热力图最多展示的列数（一级维度按总调用量降序取 Top N）。 */
export const MATRIX_TOP_N = 20;

/**
 * 本页释义字典：name → 显示标签 + tooltip 文案。
 * 关键口径必须讲清楚：NL 隐式是启发式可能有噪音；矩阵纯数据驱动零使用不可见；
 * 会话数是按日聚合行求和的近似值；MCP 维度来自 mcp__<server>__<tool> 工具名解析。
 */
export const CAP_HELP: Record<string, { label: string; help: string }> = {
  // ===== Skill tab =====
  skill_ranking: {
    label: 'Skill 使用排行',
    help:
      '窗口内各技能调用汇总，按总次数降序。「显式」= 用户消息中 /技能名 直接调用；'
      + '「NL 隐式」= 启发式识别：同一轮内 Read 了 skills/*/SKILL.md 且随后出现 Edit/Write/Bash '
      + '等落地工具才计入，可能有噪音，仅作参考。纯数据驱动：窗口内零使用的技能不会出现。',
  },
  explicit_count: {
    label: '显式次数',
    help: '用户消息中显式输入 /技能名 触发的调用次数（入库时按行解析）。',
  },
  nl_count: {
    label: 'NL 次数',
    help:
      'NL 隐式识别次数：启发式规则（同一轮 Read SKILL.md + 随后出现 Edit/Write/Bash 等落地工具）'
      + '识别的自然语言触发，可能有噪音，仅作方向参考、不作精确考核。',
  },
  invoke_count: { label: '总次数', help: '窗口内该项的调用总次数（skill = 显式 + NL 隐式）。' },
  user_count: { label: '使用人数', help: '窗口内用过该项的去重员工数。' },
  session_count: {
    label: '会话数',
    help:
      '按日聚合行求和的近似值：同一会话跨多个自然日会被重复计一次以上，'
      + '仅作量级参考，不是精确去重的会话数。',
  },
  skill_trend: {
    label: 'Skill 调用趋势',
    help:
      '按日堆叠柱：显式调用（蓝）+ NL 隐式识别（紫）。NL 为启发式识别，可能有噪音；'
      + '看趋势方向即可，勿逐日抠数。',
  },
  skill_matrix: {
    label: '人 × Skill 覆盖矩阵',
    help:
      '纯数据驱动：只展示窗口内实际被使用过的技能，零使用的技能在此不可见'
      + '（不能据此断定某员工「没装」某技能）。列按总调用量降序，超过 20 列仅展示 Top 20。'
      + '点击色块或右上角选择员工可下钻个人明细。',
  },
  // ===== 插件（MCP）tab =====
  mcp_ranking: {
    label: 'MCP Server 排行',
    help:
      'MCP 维度来自工具名 mcp__<server>__<tool> 的解析：一级行为 server，'
      + '展开可看该 server 下各 tool 的调用明细（已按调用量降序）。',
  },
  plugin_ns_ranking: {
    label: '插件命名空间技能排行',
    help: '一级行为插件 namespace（如 superpowers），展开可看该命名空间下各技能的调用明细。',
  },
  mcp_trend: {
    label: 'MCP 调用趋势',
    help: '按日 MCP 工具调用总量（全部 server 合计）。维度来自 mcp__<server>__<tool> 工具名解析。',
  },
  mcp_matrix: {
    label: '人 × MCP Server 覆盖矩阵',
    help:
      '纯数据驱动：只展示窗口内实际被调用过的 MCP server，零使用的 server 在此不可见。'
      + '列按总调用量降序，超过 20 列仅展示 Top 20。点击色块或右上角选择员工可下钻个人明细。'
      + 'MCP 维度来自 mcp__<server>__<tool> 工具名解析。',
  },
  // ===== 下钻 Drawer =====
  user_drawer: {
    label: '能力使用明细',
    help:
      '该员工窗口内 Skill / MCP / 插件命名空间的全部使用明细，按调用量降序；'
      + '二级行为 MCP tool 或命名空间技能。会话数为按日聚合行求和的近似值（跨日会话会重复计）。',
  },
};

/** 下钻明细分组标题：kind → 展示文案（未知 kind 原样展示）。 */
export const KIND_META: Record<string, { label: string }> = {
  skill: { label: 'Skill 技能' },
  mcp: { label: 'MCP 工具' },
  plugin_ns: { label: '插件命名空间技能' },
};

/** 下钻明细分组展示顺序（未列出的 kind 排最后）。 */
export const KIND_ORDER = ['skill', 'mcp', 'plugin_ns'];
