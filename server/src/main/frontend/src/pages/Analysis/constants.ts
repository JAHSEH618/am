// 分析报告 v3.0 前端常量：watchlist 文案、协作模式文案、能力维度文案。
// 与 docs/design/employee-insight-from-ai-sessions-v1.0.md §3 / §2.2 对齐。

export const WATCHLIST_META: Record<
  string,
  { label: string; color: string; severity: 'warn' | 'info' | 'good'; help: string }
> = {
  high_invest_low_output: {
    label: '高投入低产出',
    color: 'volcano',
    severity: 'warn',
    help: '触发条件：session ≥ 15 且 AI 协作时长 ≥ 团队 P75 且 每小时 commit ≤ 团队 P25。可能是任务难、卡壳多，或 AI 用得不顺手。',
  },
  high_revert: {
    label: '高回滚率',
    color: 'red',
    severity: 'warn',
    help: '触发条件：窗口内 Git 提交 ≥ 5 且 回滚率 > 25%。同仓库内在之后 7 天内出现明确撤回语义（如 Git 默认 Revert 摘要、revert:/rollback 字样）；不含日常 fix/hotfix/bugfix 类提交，以免长期维护误判。',
  },
  low_difficulty: {
    label: '任务难度偏低',
    color: 'orange',
    severity: 'info',
    help: '触发条件：session ≥ 15 且 难度 1-2 占比 > 80%。AI 主要在做小事；可能是工作内容本身简单，也可能是没把 AI 用在难事上。',
  },
  high_dependent: {
    label: '依赖型偏高',
    color: 'gold',
    severity: 'info',
    help: '触发条件：session ≥ 15 且 mode.dependent 占比 > 35%。基础问题问 AI、缺乏自主判断、卡壳反复。',
  },
  low_completion: {
    label: '完成率偏低',
    color: 'magenta',
    severity: 'warn',
    help: '（已停用）',
  },
  debugging_loop: {
    label: '调试循环',
    color: 'cyan',
    severity: 'warn',
    help: '触发条件：session ≥ 15 且 mode.debugging 占比 > 40% 且 每小时 commit ≤ 团队 P25。长时间陷在粘错误→试方案循环，产出偏低。',
  },
  silent_productive: {
    label: '沉默+产出正常',
    color: 'green',
    severity: 'good',
    help: '触发条件：session 数 ≤ 团队 P10 且 Git 提交 ≥ 3 且 每小时 commit ≥ 团队 P50。话不多、活儿稳，正向信号。',
  },
};

export const MODE_META: Record<string, { label: string; color: string }> = {
  leverage: { label: '杠杆型', color: '#22c55e' },
  learning: { label: '学习型', color: '#3b82f6' },
  dependent: { label: '依赖型', color: '#f97316' },
  exploratory: { label: '探索型', color: '#a855f7' },
  debugging: { label: '调试型', color: '#06b6d4' },
};

export const OUTCOME_META: Record<string, { label: string; color: string }> = {
  completed: { label: '完成', color: '#22c55e' },
  partial: { label: '部分', color: '#f59e0b' },
  abandoned: { label: '放弃', color: '#ef4444' },
};

export const CAPABILITY_DIMENSIONS: Array<{
  key:
    | 'cap_problem_decomposition'
    | 'cap_context_management'
    | 'cap_debugging_skill'
    | 'cap_tool_orchestration'
    | 'cap_self_correction';
  label: string;
}> = [
  { key: 'cap_problem_decomposition', label: '需求拆解' },
  { key: 'cap_context_management', label: '上下文管理' },
  { key: 'cap_debugging_skill', label: '调试能力' },
  { key: 'cap_tool_orchestration', label: '工具编排' },
  { key: 'cap_self_correction', label: '自我纠错' },
];

export const BUCKET_META: Record<
  'top25' | 'mid50' | 'bottom25',
  { label: string; color: string }
> = {
  top25: { label: '前 25%', color: 'green' },
  mid50: { label: '中 50%', color: 'blue' },
  bottom25: { label: '后 25%', color: 'orange' },
};

/**
 * 所有测评指标的释义字典：name → 显示标签 + tooltip 文案。
 *
 * 与 docs/design/employee-insight-from-ai-sessions-v1.0.md 中描述对齐。每条都
 * 包括"是什么 / 怎么算 / 怎么读"三件套，避免管理员误读。
 */
export const METRIC_HELP: Record<string, { label: string; help: string }> = {
  // ===== 团队级 =====
  active_user_count: {
    label: '活跃员工',
    help: '窗口内至少有 1 条 AI 会话活动的员工数。沉默员工不计入此处，但仍可能在员工列表里出现（如果他们之前有审计缓存）。',
  },
  total_session_count: {
    label: '总会话数',
    help:
      '窗口内的会话总数。生成完成后「已审计 / 总会话」表示窗口内有评判结果的会话数（含本轮命中缓存未调 LLM 的）。进行中进度多为本轮队列计数，缓存命中不计入故勿误读。默认生成跨窗口复用 ai_session_audit；会话新增消息超过 reaudit 阈值或 audit_version 升级会再审。「强制重跑」对本窗口全部会话忽略缓存重审。',
  },
  total_active_hours: {
    label: '协作总时长（小时）',
    help:
      '窗口内各活跃员工「协作时长」之和；每人每天取 daily_summary.ai_active_seconds_union（与员工数据 / DailySummaryAggregator 信号流并集口径一致），再按报告自然日窗口累加。无日汇总行的日期视为 0，若与画像差异大请先让员工数据页触发当日聚合。',
  },
  total_ai_commit: {
    label: 'Git 提交数',
    help:
      '窗口内经 Agent 上报并入库的 git 提交总数（git_commit 表）。不再按 ai_assisted 过滤：该字段由会话重叠启发式推断，容易漏判/误判，不适合作为报告主口径。',
  },
  judge_disagreement_ratio: {
    label: '双 judge 不一致比例',
    help: '双 judge 给出的难度差 > 1 或某维度差 > 1 的会话占比。健康范围 < 20%；高于该值说明评判标准有歧义或样本太短，需要人工 spot-check。',
  },
  // ===== 团队分布图 =====
  team_difficulty_dist: {
    label: '任务难度分布',
    help: '团队整体的任务难度构成（1-5）。难度1=拼写格式；3=多文件/调试；5=系统设计/复杂重构。1-2 占比过高说明 AI 主要在做小事。',
  },
  team_mode_dist: {
    label: '协作模式占比',
    help: '5 种协作模式的团队占比：leverage 加速明确任务；learning 学习新东西；exploratory 探索讨论；debugging 调试循环；dependent 依赖型。',
  },
  team_completion_dist: {
    label: '会话结果分布',
    help: 'completed 完成 / partial 部分完成 / abandoned 放弃。partial+abandoned 占比 > 40% 时团队整体卡壳偏多。',
  },
  team_tool_breakdown: {
    label: 'Slash Commands分布',
    help:
      '团队窗口内各 user 消息入库时解析的主动斜杠调用汇总：Cursor 等为「行首或空白后的 /…」token；Codex 为 $技能名；Claude Code 当前不写入。横向柱状图为各 token 调用次数，区域最高 140px 超出可滚动。',
  },
  team_percentiles: {
    label: '团队基线（百分位）',
    help: 'P10/25/50/75/90 是本窗口本团队的相对基线，watchlist 触发与员工分位排序都基于这套基线（不与外部对比）。',
  },
  /** 团队基线表各行：与员工详情指标同源数值字段，但语义为「跨员工分布分位」，勿复用带「本人」的员工词条 */
  team_percentile_session_count: {
    label: '会话数',
    help:
      '把窗口内每位活跃员工的会话数作为一个样本，全员排序后取 P10…P90。同一列是该分位上的「会话数」阈值（不是总和）；watchlist 里「≤ 团队 P10」等规则与此对齐。',
  },
  team_percentile_ai_active_hours: {
    label: 'AI 协作时长（h）',
    help:
      '同上：每位员工窗口内协作时长（小时）作为样本的分位点，用于衡量团队在时长维度上的分布基线。',
  },
  team_percentile_ai_commits_per_active_hour: {
    label: '每小时 commit',
    help:
      '同上：每位员工「窗口内 commit 数 ÷ 协作小时」作为样本的分位点；单人协作时长为 0 的员工通常不参与该指标的分位计算（与后端一致）。',
  },
  team_percentile_completion_rate: {
    label: '完成率',
    help:
      '同上：每位员工难度加权完成率在团队内的分位点；列值为该分位处的完成率阈值。',
  },
  watchlist_summary: {
    label: 'Watchlist 概览',
    help: '触发 watchlist 的员工汇总。这些信号是"值得 1 对 1 聊一聊"的提示，不是结论；具体原因要去看该员工的详情面板。',
  },
  // ===== 员工列表表头 =====
  composite_bucket: {
    label: '分位',
    help: 'composite_score 在团队内的分位段。综合分 = 0.35·能力均值/5 + 0.35·产出分位 + 0.20·高难度占比 + 0.10·质量指数（1-回滚率）。',
  },
  composite_score: {
    label: '综合分',
    help: '0-100 的内部排序分；不要单独用它评定员工，需要结合 watchlist + 典型会话样本一起看。',
  },
  composite_percentile: {
    label: '综合分位',
    help: '该员工在本团队内 composite_score 的百分位（0-100），用于"前 25% / 中 50% / 后 25%"分位段判定。',
  },
  // ===== 员工/会话指标 =====
  session_count: {
    label: '会话数（本人）',
    help: '该行员工在报告时间窗内的 AI 会话总数。< 10 个时标记"样本不足"，仅展示基本量，不出能力评判（避免小样本误判）。',
  },
  slash_commands: {
    label: 'Slash Commands',
    help:
      '报告窗口内该员工 user 消息中的快捷调用汇总：/'
      + '… 的命令与技能（启发式归类）以及 Codex 的 $ '
      + '技能等；与团队视图「Slash Commands分布」同源。大字为命令与技能次数之和。',
  },
  tool_command_count: {
    label: '斜杠命令次数',
    help:
      '报告窗口内该员工所有 user 消息的 slash_command_count 之和（入库时识别形如 /fix、/run-skill 或技能路径的斜杠 token，排除裸 /、/. 等碎片）。历史未回填空行为 0。',
  },
  tool_skill_count: {
    label: '斜杠技能次数',
    help:
      '报告窗口内该员工所有 user 消息的 slash_skill_count 之和（含显式 /技能 与 NL 已执行 skill：同一轮 user 消息内 Read …/skills/*/SKILL.md 之后出现 Edit/Write/Bash 等落地工具，kind=nl_skill；仅 Read 或 Grep 浏览不计）。员工详情顶部 Slash Commands 大字为命令+技能之和。噪声 /usage、/exit 不计入。',
  },
  ai_active_hours: {
    label: 'AI 协作时长（h）',
    help:
      '该员工在报告窗口内，按自然日累加 daily_summary.ai_active_seconds_union 后换算为小时（与员工数据「日均协作 / 趋势图」同源）。若某日为 0 可能尚无日汇总，需在画像页或定时任务刷新后再跑报告。',
  },
  total_tokens: {
    label: 'Token 总量',
    help: 'input + output token 累计。仅作量级参考，不入综合分（避免用"和 AI 聊得多"反过来变成绩效指标）。',
  },
  ai_commit_count: {
    label: 'Git 提交数',
    help:
      '该员工在窗口内经上报入库的 commit 条数。前提仍是 Agent 已采集 git 流水；与 ai_assisted 推断无关。',
  },
  ai_lines_added: {
    label: '提交新增行数',
    help: '上述窗口内各 commit 的 lines_added 之和（上报字段；未上报则视为 0）。配合"每千 token 落地行数"看落地效率。',
  },
  avg_difficulty: {
    label: '平均难度',
    help: '该员工窗口内已审计会话的平均难度（1-5）。趋稳于 3 以上说明在做有挑战的事；常年 1-2 + 沉默信号通常意味着停滞。',
  },
  high_difficulty_ratio: {
    label: '高难度（≥4）占比',
    help: '难度 ≥ 4 的会话占比。反映窗口内接难活的比例，是综合分的组成项之一。',
  },
  ai_active_hours_list: {
    label: 'AI 协作（h）',
    help: '窗口内按 natural day 累加 daily_summary.ai_active_seconds_union 后换算为小时。',
  },
  completion_rate: {
    label: '完成率',
    help: '难度加权完成率：completed=1 / partial=0.5 / abandoned=0，按难度权重(1,2,4,8,16)加权。高难度完成的权重远高于低难度。',
  },
  abandoned_rate: {
    label: '放弃率',
    help: '会话明确放弃 / 长时间不回 / 换话题的占比。> 30% 时通常说明任务超出能力范围或上下文管理有问题。',
  },
  cap_problem_decomposition: {
    label: '需求拆解',
    help: '把复杂问题拆成可执行小步的能力。1：单条命令式提问；3：能分 2-3 步；5：明确目标+子任务清晰+约束完整。',
  },
  cap_context_management: {
    label: '上下文管理',
    help: '是否提供足够信息让 AI 理解任务。1：无代码无错误凭空问；3：贴了关键代码或错误；5：贴代码+错误+已尝试方案+业务约束。',
  },
  cap_debugging_skill: {
    label: '调试能力',
    help: '定位问题、读懂错误、设计验证步骤。1：粘错误不分析；3：能基于 AI 提示验证排除；5：主动设计实验对比假设推断根因。',
  },
  cap_tool_orchestration: {
    label: '工具编排',
    help: '合适工具的选择与切换。1：只让 AI 写代码不会用工具；3：会用工具但搭配生硬；5：根据任务流畅切换 read/write/search/不同模型。',
  },
  cap_self_correction: {
    label: '自我纠错',
    help: '发现 AI 走偏时及时调整方向（不死磕）。1：AI 错了跟着错；3：会发现并指出；5：会重新组织问题、换思路、引入新约束。',
  },
  capability_radar: {
    label: '能力雷达',
    help: '5 维能力评分，难度加权均值。雷达图参考圈为 rubric 中位（3）；用于横向看该员工的"长板与短板"。',
  },
  // ===== 产出验证 =====
  ai_commits_per_active_hour: {
    label: '每小时 commit',
    help: 'Git 提交数 ÷ AI 协作小时数。是衡量"产出效率"的核心指标；与团队 P25/P50/P75 比较读分位。',
  },
  ai_lines_per_1k_token: {
    label: '每千 token 落地行数',
    help: '提交新增行数 ÷ Token 总量 × 1000。低意味着"聊得多落地少"，可能是依赖型 / 探索型为主。',
  },
  commit_revert_rate: {
    label: '回滚率',
    help:
      '窗口内提交中，同仓库 7 天内出现明确撤回语义后续提交的比例（Revert 摘要、revert:/rollback；不含日常 fix/hotfix/bugfix）。',
  },
  high_difficulty_commit_ratio: {
    label: '高难度 commit 占比',
    help:
      '关联到难度 ≥4 审计会话（见 git_commit.ai_session_ids）的提交数 ÷ 窗口内提交总数。无会话关联的提交不会计入分子。',
  },
  // ===== watchlist =====
  watchlist_flags: {
    label: 'Watchlist',
    help: '触发的 watchlist 信号。鼠标移到每个标签可看详细规则。仅作"值得聊一聊"的提示，不作为结论。',
  },
};
