import type { CSSProperties } from 'react';
import { statusColor } from '../utils/format';
import { ink } from '../styles/tokens';

/**
 * 统一状态圆点 —— 全站「彩色小圆点 + 状态」的唯一实现。
 *
 * 取代散落在 Dashboard / Realtime / Sessions / People / SessionDetail / ModelsTools /
 * Alerts / SourceFilter / gitCommitTableColumns 等处手搓的 `<span style={{ width:8,
 * height:8, borderRadius:4, background: ... }} />`，保证圆点尺寸 / 陈旧灰化 / 活跃脉冲一致。
 */
export interface StatusDotProps {
  /** 显式色值；优先于 status。 */
  color?: string;
  /** 会话状态名，按 statusColor() 取色。 */
  status?: string | null;
  /** 陈旧 / 长时间未动：灰化 + 柔环。 */
  stale?: boolean;
  /** 活跃脉冲光环（@prefers-reduced-motion 下自动关闭）。 */
  pulse?: boolean;
  /** 直径 px，默认 8。 */
  size?: number;
  style?: CSSProperties;
}

export function StatusDot({
  color,
  status,
  stale = false,
  pulse = false,
  size = 8,
  style,
}: StatusDotProps) {
  const c = stale ? ink[5] : color ?? statusColor(status);
  return (
    <span
      aria-hidden
      className={pulse && !stale ? 'am-status-dot am-status-dot--pulse' : 'am-status-dot'}
      style={{
        width: size,
        height: size,
        borderRadius: size / 2,
        background: c,
        boxShadow: stale ? '0 0 0 2px var(--am-surface-sunken)' : undefined,
        // 供 ::after 脉冲读取当前点色
        ['--am-dot-color' as string]: c,
        ...style,
      }}
    />
  );
}

export default StatusDot;
