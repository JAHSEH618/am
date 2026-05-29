import type { CSSProperties } from 'react';

/** AIWatch 品牌图标（观测眼）— 侧栏、登录页等与 favicon 同源 */
export interface BrandIconProps {
  /** @deprecated 保留兼容旧调用，当前均渲染同一图标 */
  variant?: 'eye' | 'monogram';
  /** 图标边长（px） */
  size?: number;
  className?: string;
  style?: CSSProperties;
  title?: string;
}

const GRADIENT_ID = 'aw-brand-grad';

function BrandGradient() {
  return (
    <defs>
      <linearGradient id={GRADIENT_ID} x1="4" y1="4" x2="28" y2="28" gradientUnits="userSpaceOnUse">
        <stop stopColor="#6366f1" />
        <stop offset="1" stopColor="#8b5cf6" />
      </linearGradient>
    </defs>
  );
}

export function BrandIcon({
  size = 30,
  className,
  style,
  title = 'AIWatch',
}: BrandIconProps) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 32 32"
      width={size}
      height={size}
      className={className}
      role="img"
      aria-label={title}
      style={{ flexShrink: 0, display: 'block', ...style }}
    >
      <title>{title}</title>
      <BrandGradient />
      <rect width="32" height="32" rx="7" fill={`url(#${GRADIENT_ID})`} />
      <path
        d="M6.5 16s4.2-6.5 9.5-6.5S25.5 16 25.5 16s-4.2 6.5-9.5 6.5S6.5 16 6.5 16Z"
        stroke="#fff"
        strokeWidth="1.75"
        fill="none"
        strokeLinejoin="round"
      />
      <circle cx="16" cy="16" r="3.1" fill="#fff" />
      <path
        d="M21.2 10.8c2.1 1.3 3.4 3.2 3.9 5.2"
        stroke="#fff"
        strokeWidth="1.5"
        strokeLinecap="round"
        fill="none"
        opacity={0.9}
      />
    </svg>
  );
}
