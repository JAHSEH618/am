import { lazy, Suspense } from 'react';
import type { ComponentProps } from 'react';

/**
 * 懒加载版 ECharts。
 *
 * <p>echarts 打出来是 1MB+ 的独立 chunk。只要有一个**非懒加载路由**静态 import 了
 * `echarts-for-react`，Vite 就会把该 chunk 变成入口的静态依赖，在 index.html 里挂上
 * `<link rel="modulepreload">` —— 于是首屏渲染前必须先下完它。Dashboard 和 People
 * 都是非懒加载路由（见 App.tsx），正是它们把 echarts 拖进了关键路径。
 *
 * <p>改成动态 import 后，echarts 退出入口依赖图：页面先渲染数字卡片和表格，
 * 图表随后补上。已经在 lazy 路由里的页面（Projects / Capability / Analysis 等）
 * 本来就不在关键路径上，保持直接 import 即可。
 *
 * <p>注意：`React.lazy` 下 ref 转发不可靠，需要 `getEchartsInstance()` 的地方
 * （如 Analysis/UserDetail）请继续直接 import。
 */
const ReactECharts = lazy(() => import('echarts-for-react'));

type Props = ComponentProps<typeof ReactECharts>;

/** 占位：撑住与图表相同的盒子，避免 chunk 到达时的布局跳动。 */
function ChartFallback({ style }: { style?: Props['style'] }) {
  return <div style={{ width: '100%', height: '100%', ...style }} />;
}

export default function LazyECharts(props: Props) {
  return (
    <Suspense fallback={<ChartFallback style={props.style} />}>
      <ReactECharts {...props} />
    </Suspense>
  );
}
