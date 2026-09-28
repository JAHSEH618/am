import EChartsReactCore from 'echarts-for-react/esm/core';
import type { EChartsReactProps } from 'echarts-for-react/esm/types';
import { echarts } from '@/lib/echarts';

/**
 * `echarts-for-react` 的等价替身：同一个 core 组件，只是注入按需注册的 echarts
 * （见 `lib/echarts.ts`），不再把整包 echarts 带进来。
 *
 * <p>写法与库自带的默认导出一致（子类里给 `this.echarts` 赋值），所以 props、
 * `ref.getEchartsInstance()` 都和原来一样。懒加载路由直接 import 本组件；
 * 非懒加载路由仍然走 `LazyECharts`。
 */
export default class ReactECharts extends EChartsReactCore {
  constructor(props: EChartsReactProps) {
    super(props);
    this.echarts = echarts;
  }
}
