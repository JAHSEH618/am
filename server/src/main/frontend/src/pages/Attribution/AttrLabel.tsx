import MetricLabel from '../Analysis/MetricLabel';
import { ATTR_HELP } from './constants';

/**
 * 归因分析页释义标签：复用 Analysis/MetricLabel 的 tooltip 外观（显式传 label/help），
 * 文案集中在本目录 constants.ts 的 ATTR_HELP —— 新增释义只需在字典里加一行。
 */
export default function AttrLabel({
  name,
  size,
}: {
  name: keyof typeof ATTR_HELP;
  size?: 'sm' | 'md' | 'lg';
}) {
  const meta = ATTR_HELP[name];
  return <MetricLabel label={meta?.label ?? String(name)} help={meta?.help} size={size} />;
}
