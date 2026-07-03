import * as echarts from 'echarts';

/** 离屏渲染 ECharts option → 2x PNG dataURL（PDF 嵌图用）。 */
export function renderChartToDataUrl(option: object, width: number, height: number): string {
  const div = document.createElement('div');
  div.style.cssText = `position:fixed;left:-10000px;top:0;width:${width}px;height:${height}px;`;
  document.body.appendChild(div);
  const chart = echarts.init(div, undefined, { renderer: 'canvas', width, height });
  try {
    chart.setOption({ animation: false, ...option });
    return chart.getDataURL({ pixelRatio: 2, backgroundColor: '#ffffff' });
  } finally {
    chart.dispose();
    div.remove();
  }
}
