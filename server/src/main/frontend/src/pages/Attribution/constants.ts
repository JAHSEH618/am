// 产出归因分析页前端常量：卡片/列释义字典 + 档位/维度元数据。
// 与 docs/design/管理后台-产出归因与能力使用分析-v1.0.md §2 对齐；
// 释义模式照 pages/Capability/constants.ts 的 CAP_HELP（是什么 / 怎么算 / 怎么读）。

import type { AttributionRowDim } from '../../api/types';

/**
 * 本页释义字典：name → 显示标签 + tooltip 文案。
 * 关键口径必须讲清楚：B 档误报≈0 可作结论；A 档是上限口径不得当结论；
 * B/A 互斥 + 单一归属 → 任意维度组合求和 = 总数；merge commit 全口径排除；
 * backfilled 行来自上线前历史回溯；北极星渗透率与本页同源（git_commit_attribution）。
 */
export const ATTR_HELP: Record<string, { label: string; help: string }> = {
  attribution_trend: {
    label: 'AI 产出趋势',
    help:
      'B/A 双档堆叠柱 + 右轴 AI 占比线 =（B+A）÷（B+A+未命中），分母为 0 的日期断开不连线。'
      + 'B 档（深色）= commit message 命中 AI trailer，误报≈0，可作结论；'
      + 'A 档（浅色）= 同人同仓库 AI 会话活动窗口 ±30 分钟内的 commit，是上限口径——'
      + '重度用户几乎所有 commit 都会命中，读数时不得把 A 档当结论。'
      + '未命中不画柱但计入占比分母。merge commit 全口径排除。'
      + '灰底区间为上线前历史回溯推算（backfilled）。',
  },
  tier_b: {
    label: 'B 档（确定 AI 产出）',
    help:
      'commit message 命中 AI trailer（如 Co-Authored-By: Claude / Generated with Claude Code）。'
      + '误报≈0，可作结论使用。',
  },
  tier_a: {
    label: 'A 档（疑似 AI 辅助）',
    help:
      'commit 落在同人同仓库 AI 会话活动窗口 ±30 分钟内。这是上限口径：'
      + '重度 AI 用户几乎所有 commit 都会命中，读数时不得把 A 档当结论，只作参考上界。'
      + 'B/A 互斥：命中 B 档的 commit 不再计入 A 档。',
  },
  pivot: {
    label: '归因交叉透视',
    help:
      '按 人/项目/工具/模型 任意行列组合切分 B/A/未命中三档。单一归属：每个 commit 至多归属'
      + '一个会话，任意维度组合求和 = 总数，不会重复计。B/A 必须分列读——B 档可作结论，'
      + 'A 档是上限口径。维度值缺失（未归因）的 commit 归入灰色「未归因」。'
      + '点击单元格或行可下钻 commit 明细。merge commit 全口径排除。',
  },
  ai_ratio: {
    label: 'AI 占比',
    help:
      '（B+A）÷（B+A+未命中），按 commit 数口径；分母为 0 显示 —。'
      + '分子含 A 档上限口径，此占比是"AI 参与的上界"而非精确产出比。'
      + '北极星 AI 渗透率与本口径同源（同读 git_commit_attribution 预计算表）。',
  },
  commit_drawer: {
    label: 'Commit 明细',
    help:
      '该切片下逐 commit 的归因证据：B 档看 trailer 类型；A 档看归属会话与窗口重叠时长，'
      + '点击会话列可跳转归属会话人工核查。「回溯」标 = 上线前历史回溯推算所得。',
  },
  backfilled: {
    label: '回溯推算',
    help:
      'backfilled 行来自上线（归因引擎部署）前的历史回溯：B 档回溯（trailer 文本匹配）可信；'
      + 'A 档回溯依赖历史会话事件完整性，可能偏低。趋势图对回溯区间以灰底注明。',
  },
};

/** 三档元数据：Tag 色系 + 展示文案（B=success、A=蓝、NONE=default）。 */
export const TIER_META: Record<string, { label: string; tag: string }> = {
  B: { label: 'B 确定', tag: 'success' },
  A: { label: 'A 疑似', tag: 'blue' },
  NONE: { label: '未命中', tag: 'default' },
};

/** 透视维度：value 与服务端 row/col 参数一致。 */
export const DIM_OPTIONS: { value: AttributionRowDim; label: string }[] = [
  { value: 'user', label: '人' },
  { value: 'project', label: '项目' },
  { value: 'tool', label: '工具' },
  { value: 'model', label: '模型' },
];

/** 维度 → /commits 明细过滤参数名（下钻 Drawer 用）。 */
export const DIM_COMMIT_PARAM: Record<
  AttributionRowDim,
  'user_code' | 'project_name' | 'target_type' | 'model'
> = {
  user: 'user_code',
  project: 'project_name',
  tool: 'target_type',
  model: 'model',
};

/** 维度值空串（该 commit 未归因出此维度）的统一展示文案。 */
export const UNATTRIBUTED_LABEL = '未归因';

/** 透视表 / 明细抽屉每页行数。 */
export const PIVOT_PAGE_SIZE = 15;
export const COMMITS_PAGE_SIZE = 20;
