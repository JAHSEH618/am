import MetricLabel from '../Analysis/MetricLabel';
import { CAP_HELP } from './constants';

/**
 * 能力分析页释义标签：复用 Analysis/MetricLabel 的 tooltip 外观（显式传 label/help），
 * 文案集中在本目录 constants.ts 的 CAP_HELP —— 新增释义只需在字典里加一行。
 */
export default function CapLabel({
  name,
  size,
}: {
  name: keyof typeof CAP_HELP;
  size?: 'sm' | 'md' | 'lg';
}) {
  const meta = CAP_HELP[name];
  return <MetricLabel label={meta?.label ?? String(name)} help={meta?.help} size={size} />;
}
