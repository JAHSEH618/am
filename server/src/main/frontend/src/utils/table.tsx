import type { CSSProperties, KeyboardEvent, ReactNode } from 'react';

/**
 * 表格 / 列渲染共享助手 —— 统一「可点击行的键盘可达」「空值占位」「数字列等宽」三件高频小事，
 * 让各页表格在交互、留白、对齐上一致（Apple-native 的精致来自这些细节的统一）。
 */

/**
 * 可点击行：把 onClick 行为补齐为「键盘也能触达」——挂到 AntD `<Table onRow>`。
 * 取代各页散落的「只有鼠标 onClick、Tab 不可达」的行（Sessions / Projects / People / Analysis）。
 * 与 Dashboard 的 HeroCard 同款交互（Enter / Space 触发 + 焦点环由 global.css 提供）。
 */
export function clickableRowProps(onClick: () => void): {
  onClick: () => void;
  onKeyDown: (e: KeyboardEvent<HTMLElement>) => void;
  tabIndex: number;
  role: string;
  style: CSSProperties;
} {
  return {
    onClick,
    onKeyDown: (e) => {
      // 仅在行本身获得焦点时响应；内部链接/按钮的回车不冒泡到这里（它们自行 stopPropagation 即可）
      if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) {
        e.preventDefault();
        onClick();
      }
    },
    tabIndex: 0,
    role: 'button',
    style: { cursor: 'pointer' },
  };
}

/** 空值统一占位：ink-5 的「—」（与 Dashboard 表格一致；占位符色不承载信息、不抢读数）。 */
export const EMPTY_DASH: ReactNode = <span style={{ color: 'var(--am-ink-5)' }}>—</span>;

/** 列渲染兜底：null / 空串 → 统一「—」；否则原样返回。 */
export function emptyCell(v: ReactNode): ReactNode {
  return v == null || v === '' ? EMPTY_DASH : v;
}

/** 数字列内联样式：等宽数字，防止数据滚动时左右抖动（全局已开 tnum，这里用于需要局部强调处）。 */
export const NUM_STYLE: CSSProperties = { fontVariantNumeric: 'tabular-nums' };
