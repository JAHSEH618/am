import { useMemo } from 'react';
import { Select } from 'antd';
import type { MonitorTarget } from '../api/types';
import { presetColorToHex } from '../utils/format';

/**
 * SourceFilter —— AI 会话来源筛选下拉。
 *
 * 设计目标：
 *
 *   1. 字典驱动：选项来自 GET /api/v1/monitor-targets，加新 Provider 不改前端代码；
 *   2. 宽度恒定：无论 6 个还是 60 个 agent 都是同一个 220px 控件，不会像 Segmented 那样横向无限延展；
 *   3. 视觉锚点：每项前面一个 6×6 圆点，颜色取自字典 display_color，
 *      让用户在下拉列表里也能用颜色快速识别来源（与表格 Tag 颜色对应）；
 *   4. "全部" 作为内置首项（value='all'），与 Sessions 页 URL 参数一致。
 *
 * 不做的事：
 *
 *   - 多选：监控后台的"按来源过滤"通常是单选场景（聚焦看一种 agent），
 *     需要多选时再扩展为 mode="multiple"，目前 KISS。
 *   - 自带 fetch：组件内部不拉数据，由调用方传 targets 进来——
 *     避免 Sessions / SessionDetail 各自重复请求。
 */
export interface SourceFilterProps {
  value: string; // 'all' 或 type_code
  onChange: (v: string) => void;
  targets: MonitorTarget[];
  width?: number;
  size?: 'small' | 'middle' | 'large';
}

const ALL_VALUE = 'all';

export default function SourceFilter({
  value,
  onChange,
  targets,
  width = 220,
  size = 'middle',
}: SourceFilterProps) {
  const options = useMemo(() => {
    const enabled = targets.filter((t) => t.enabled);
    return [
      {
        value: ALL_VALUE,
        label: (
          <span style={dotRow}>
            <span style={{ ...dot, background: 'var(--am-ink-5)' }} />
            <span>全部来源</span>
            <span style={count}>{enabled.length}</span>
          </span>
        ),
      },
      ...enabled.map((t) => ({
        value: t.type_code,
        label: (
          <span style={dotRow}>
            <span style={{ ...dot, background: presetColorToHex(t.display_color) }} />
            <span>{t.type_name}</span>
          </span>
        ),
      })),
    ];
  }, [targets]);

  return (
    <Select
      value={value}
      size={size}
      style={{ width }}
      options={options}
      onChange={onChange}
      // 让选中态在控件里也带圆点；showSearch 暂时不开（agent 数 < 20 时无需搜索）
      placeholder="全部来源"
    />
  );
}

const dotRow: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 8,
};

const dot: React.CSSProperties = {
  display: 'inline-block',
  width: 8,
  height: 8,
  borderRadius: 4,
  flexShrink: 0,
};

const count: React.CSSProperties = {
  marginLeft: 'auto',
  fontSize: 12,
  color: 'var(--am-ink-3)',
  fontVariantNumeric: 'tabular-nums',
};
