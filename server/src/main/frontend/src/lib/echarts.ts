/**
 * 按需注册的 echarts —— 全站唯一的 echarts 入口。
 *
 * <p>`echarts-for-react` 的默认导出会 `import * as echarts from 'echarts'`，把 20+ 种图表、
 * 地图 / 3D 坐标系 / 工具箱等全部组件拖进包里（~1 MB，gzip ~340 KB）。控制台实际只画
 * 下面这几种，改走 `echarts/core` 手动注册后 chunk 缩到原来的约一半。
 *
 * <p><b>新增图表类型 / option 顶层组件时必须在这里注册</b>，否则 echarts 只在控制台打
 * warning、图表静默缺一块（如 heatmap 没有 visualMap 就不着色）。对照：
 * <ul>
 *   <li>series：bar / line / pie / heatmap / radar</li>
 *   <li>option 顶层：grid / tooltip（含 axisPointer）/ legend（plain + scroll）/ title /
 *       graphic（空态文字）/ visualMap（热力图）/ markArea（归因趋势的区间底色）；
 *       radar 坐标系随 RadarChart 自动注册</li>
 * </ul>
 *
 * <p>另外两条全站默认值在这里统一打（页面 option 显式写了就以页面为准）：
 * <ul>
 *   <li>{@code aria.enabled}：canvas 对读屏器是一张空白图，开启后 echarts 按系列 / 数据生成一段
 *       文字描述挂到容器的 aria-label 上。</li>
 *   <li>系统开了「减少动态效果」时关掉图表动画——global.css 的 reduced-motion 兜底管不到 canvas。</li>
 * </ul>
 */
import * as echarts from 'echarts/core';
import { BarChart, HeatmapChart, LineChart, PieChart, RadarChart } from 'echarts/charts';
import {
  AriaComponent,
  GraphicComponent,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
} from 'echarts/components';
import { LabelLayout } from 'echarts/features';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([
  BarChart,
  HeatmapChart,
  LineChart,
  PieChart,
  RadarChart,
  AriaComponent,
  GraphicComponent,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
  LabelLayout,
  CanvasRenderer,
]);

const prefersReducedMotion =
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

// 预处理器拿到的是 echarts 内部 clone 后的 option，改它不会污染页面里 useMemo 的对象。
echarts.registerPreprocessor((option) => {
  if (option.aria == null) option.aria = { enabled: true };
  if (prefersReducedMotion) option.animation = false;
});

export { echarts };
