// 产出归因页 ECharts option 构造器。
// 柱线混合：B/A 双档堆叠柱（B 深 A 浅，同为品牌 indigo 色阶）+ 右轴 AI 占比线；
// NONE 不画柱但计入占比分母；backfilled 区间灰底 markArea + x 轴标签弱化。
// 空态 graphic 写法照 Capability/charts.ts 先例；色值全部走 tokens。

import { accent, border, indigo, ink } from '../../styles/tokens';
import type { AttributionTrendPoint } from '../../api/types';

export type TrendMetric = 'commits' | 'lines';

/** 空态：居中占位字，避免只剩坐标轴空架子。 */
function emptyChartOption(text = '暂无数据') {
  return {
    graphic: {
      type: 'text',
      left: 'center',
      top: 'middle',
      style: { text, fill: ink[3], fontSize: 14 },
    },
    xAxis: { type: 'value', show: false },
    yAxis: { type: 'category', show: false, data: [] },
    series: [],
  };
}

const B_NAME = 'B 确定';
const A_NAME = 'A 疑似';
const RATIO_NAME = 'AI 占比';

/**
 * 趋势柱线混合图。metric = commits 用 *_commits 字段，lines 用 *_lines（净行数）字段。
 * AI 占比 =（b+a）/（b+a+none），分母 ≤ 0 的点为 null 断开（connectNulls=false）。
 */
export function buildAttributionTrendOption(
  points: AttributionTrendPoint[],
  metric: TrendMetric,
) {
  if (points.length === 0) return emptyChartOption();

  const labels = points.map((p) => p.date.slice(5));
  const b = points.map((p) => (metric === 'commits' ? p.b_commits : p.b_lines));
  const a = points.map((p) => (metric === 'commits' ? p.a_commits : p.a_lines));
  const none = points.map((p) => (metric === 'commits' ? p.none_commits : p.none_lines));
  const ratio = points.map((_, i) => {
    const denom = b[i] + a[i] + none[i];
    return denom > 0 ? Number((((b[i] + a[i]) / denom) * 100).toFixed(1)) : null;
  });

  // backfilled 连续区间 → markArea 灰底（回溯数据通常是"上线日之前"的前缀段）
  const runs: [number, number][] = [];
  points.forEach((p, i) => {
    if (!p.backfilled) return;
    const last = runs[runs.length - 1];
    if (last && last[1] === i - 1) last[1] = i;
    else runs.push([i, i]);
  });

  const unit = metric === 'commits' ? '个' : '行';

  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: { dataIndex: number }[]) => {
        const i = params[0]?.dataIndex ?? 0;
        const p = points[i];
        const rows = [
          `<b>${p.date}</b>${p.backfilled ? '（上线前回溯推算）' : ''}`,
          `${B_NAME}：${b[i]} ${unit}`,
          `${A_NAME}（上限口径）：${a[i]} ${unit}`,
          `未命中：${none[i]} ${unit}`,
          `${RATIO_NAME}：${ratio[i] == null ? '—' : `${ratio[i]}%`}`,
        ];
        return rows.join('<br/>');
      },
    },
    legend: { data: [B_NAME, A_NAME, RATIO_NAME], top: 0, left: 'center' },
    grid: { left: 8, right: 8, top: 36, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: labels,
      axisLabel: {
        fontSize: 11,
        // 回溯区间的日期标签弱化成占位灰，与灰底 markArea 双重提示
        color: (_v: string, i: number) => (points[i]?.backfilled ? ink[5] : ink[3]),
      },
    },
    yAxis: [
      {
        type: 'value',
        name: metric === 'commits' ? 'commit 数' : '净行数',
        nameTextStyle: { fontSize: 11, color: ink[4] },
        splitLine: { lineStyle: { type: 'dashed', opacity: 0.6 } },
      },
      {
        type: 'value',
        max: 100,
        min: 0,
        axisLabel: { formatter: '{value}%', fontSize: 11 },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: B_NAME,
        type: 'bar',
        stack: 'tier',
        barMaxWidth: 22,
        data: b,
        itemStyle: { color: indigo[600] },
        markArea:
          runs.length > 0
            ? {
                silent: true,
                itemStyle: { color: border.subtle },
                data: runs.map(([s, e]) => [{ xAxis: labels[s] }, { xAxis: labels[e] }]),
              }
            : undefined,
      },
      {
        name: A_NAME,
        type: 'bar',
        stack: 'tier',
        barMaxWidth: 22,
        data: a,
        itemStyle: { color: indigo[300] },
      },
      {
        name: RATIO_NAME,
        type: 'line',
        yAxisIndex: 1,
        connectNulls: false,
        symbolSize: 5,
        data: ratio,
        itemStyle: { color: accent.teal.base },
        lineStyle: { color: accent.teal.base, width: 2 },
      },
    ],
  };
}
