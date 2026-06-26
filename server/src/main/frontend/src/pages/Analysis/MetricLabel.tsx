import { Tooltip } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { METRIC_HELP } from './constants';

interface Props {
  /** 字典 key（推荐方式）：从 METRIC_HELP 读取 label 与 help。 */
  name?: keyof typeof METRIC_HELP | string;
  /** 显式覆盖 label，name 不在字典里时也能用。 */
  label?: string;
  /** 显式覆盖 help 文案。 */
  help?: string;
  /** 字号继承父级，但可在 <Card title> 这种位置传 size="lg" 撑起来。 */
  size?: 'sm' | 'md' | 'lg';
}

/**
 * 带 hover tooltip 的指标 label。
 *
 * <p>所有"测评指标"前面统一用本组件，鼠标放在 "?" 图标上展示完整解释。
 * 文案集中在 {@link METRIC_HELP}，新增指标只需在字典里加一行。
 *
 * gz
 */
export default function MetricLabel({ name, label, help, size = 'md' }: Props) {
  const dict = name ? METRIC_HELP[name as string] : undefined;
  const finalLabel = label ?? dict?.label ?? (typeof name === 'string' ? name : '');
  const finalHelp = help ?? dict?.help ?? '';

  const iconSize = size === 'lg' ? 14 : size === 'sm' ? 11 : 12;

  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      <span>{finalLabel}</span>
      {finalHelp && (
        <Tooltip title={finalHelp} mouseEnterDelay={0.15} overlayStyle={{ maxWidth: 360 }}>
          <QuestionCircleOutlined
            style={{ color: 'var(--am-ink-3)', fontSize: iconSize, cursor: 'help' }}
          />
        </Tooltip>
      )}
    </span>
  );
}
