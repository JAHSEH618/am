// 能力使用分析页 ECharts option 构造器 —— Skill / 插件两 tab 共用。
// 空态 graphic、堆叠柱与 heatmap 写法照 Tools.tsx / ModelsTools.tsx 先例；色值全部走 tokens。

import { accent, indigo, ink } from '../../styles/tokens';
import type { CapabilityMatrix, CapabilityTrendPoint } from '../../api/types';
import { MATRIX_TOP_N } from './constants';

/** 空态：居中占位字，避免只剩坐标轴空架子（与 ModelsTools 占比图同款）。 */
export function emptyChartOption(text = '暂无数据') {
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

/** Skill 趋势：显式（命令蓝 indigo-600）+ NL 隐式（技能紫 accent.purple）堆叠柱。 */
export function buildSkillTrendOption(points: CapabilityTrendPoint[]) {
  if (points.length === 0) return emptyChartOption();
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['显式调用', 'NL 隐式'], top: 0, left: 'center' },
    grid: { left: 8, right: 16, top: 32, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: points.map((p) => p.date.slice(5)),
      axisLabel: { fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { type: 'dashed', opacity: 0.6 } },
    },
    series: [
      {
        name: '显式调用',
        type: 'bar',
        stack: 'invoke',
        barMaxWidth: 22,
        data: points.map((p) => p.explicit_count ?? 0),
        itemStyle: { color: indigo[600] },
      },
      {
        name: 'NL 隐式',
        type: 'bar',
        stack: 'invoke',
        barMaxWidth: 22,
        data: points.map((p) => p.nl_count ?? 0),
        itemStyle: { color: accent.purple.base },
      },
    ],
  };
}

/** MCP 趋势：单系列柱（总量即可，无显式/NL 分档）。 */
export function buildMcpTrendOption(points: CapabilityTrendPoint[]) {
  if (points.length === 0) return emptyChartOption();
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: points.map((p) => p.date.slice(5)),
      axisLabel: { fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { type: 'dashed', opacity: 0.6 } },
    },
    series: [
      {
        name: '调用次数',
        type: 'bar',
        barMaxWidth: 22,
        data: points.map((p) => p.invoke_count),
        itemStyle: { color: indigo[600] },
      },
    ],
  };
}

/**
 * 人×一级维度覆盖矩阵 heatmap：y=员工、x=一级维度（skill / MCP server）。
 * 列超过 MATRIX_TOP_N 时只画 Top N（items 已按总量降序，直接截前 N 列）；
 * visualMap 渐变沿品牌 indigo 色阶，照 ModelsTools 模型×日期热力图先例。
 */
export function buildMatrixHeatmapOption(matrix: CapabilityMatrix) {
  if (matrix.users.length === 0 || matrix.items.length === 0) return emptyChartOption();
  const items = matrix.items.slice(0, MATRIX_TOP_N);
  const cells = matrix.cells.filter((c) => c[1] < items.length);
  const max = cells.reduce((m, c) => Math.max(m, c[2]), 0);
  return {
    tooltip: {
      formatter: (p: { value: [number, number, number] }) => {
        const item = items[p.value[0]];
        const user = matrix.users[p.value[1]];
        return `${user?.display_name ?? ''} · ${item}<br/>调用 ${p.value[2]} 次`;
      },
    },
    grid: { left: 110, right: 30, top: 12, bottom: 96 },
    xAxis: {
      type: 'category',
      data: items,
      axisLabel: { rotate: 30, fontSize: 11, width: 110, overflow: 'truncate', ellipsis: '…' },
      splitArea: { show: true },
    },
    yAxis: {
      type: 'category',
      data: matrix.users.map((u) => u.display_name),
      inverse: true,
      axisLabel: { width: 96, overflow: 'truncate', ellipsis: '…', fontSize: 11 },
      splitArea: { show: true },
    },
    visualMap: {
      min: 0,
      max: Math.max(max, 1),
      calculable: false,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      inRange: { color: [indigo[50], indigo[500], indigo[900]] },
    },
    series: [
      {
        name: '调用次数',
        type: 'heatmap',
        // cells = [userIndex, itemIndex, count] → heatmap 数据点 [x=itemIndex, y=userIndex, count]
        data: cells.map((c) => [c[1], c[0], c[2]]),
        label: { show: false },
        emphasis: { itemStyle: { borderColor: ink[1], borderWidth: 1 } },
      },
    ],
  };
}

/** 矩阵卡片高度：随员工行数伸缩，避免几个人时大片留白 / 几十人时挤成一线。 */
export function matrixHeight(matrix: CapabilityMatrix | null): number {
  const rows = matrix?.users.length ?? 0;
  return Math.min(640, Math.max(280, rows * 26 + 150));
}
