import type { ReactNode } from 'react';
import { Card } from 'antd';

/**
 * HeroCard / MetricRow —— 全站「核心指标卡」与「次级指标行」的唯一实现。
 *
 * 取舍（与 Dashboard 金标准一致）：底色回白、数字回近黑（监控台读数要被信任，不在彩底上读数），
 * 语义色只活在「图标 chip + 角落极淡晕染 + 状态文字」三处；36px/680 的近黑数字独占视觉重心。
 * 视觉骨架由 global.css 的 `.am-hero*` / `.am-metric-row` 提供，这里只负责结构与无障碍。
 *
 * 原先内置在 Dashboard.tsx，现按其 TODO 抽出，供 People / Analysis 等页复用，杜绝各页
 * 再手搓 `fontSize:26` 的离格指标卡。
 */

export type HeroTone = 'indigo' | 'emerald' | 'amber' | 'rose' | 'sky' | 'violet';

// Hero chip 配色：浅底 + 饱和 icon；每个 KPI 一个语义色，全部走设计 token（CSS 变量）。
const TONE_PALETTE: Record<HeroTone, { bg: string; fg: string }> = {
  indigo:  { bg: 'var(--am-brand-bg)',   fg: 'var(--am-brand)' },
  emerald: { bg: 'var(--am-success-bg)', fg: 'var(--am-success-fg)' },
  amber:   { bg: 'var(--am-warning-bg)', fg: 'var(--am-warning-fg)' },
  rose:    { bg: 'var(--am-rose-bg)',    fg: 'var(--am-rose-fg)' },
  sky:     { bg: 'var(--am-sky-bg)',     fg: 'var(--am-sky-fg)' },
  violet:  { bg: 'var(--am-violet-bg)',  fg: 'var(--am-violet-fg)' },
};

export interface HeroCardProps {
  label: string;
  value: ReactNode;
  suffix?: string;
  subnote?: ReactNode;
  icon?: ReactNode;
  tone: HeroTone;
  /** 可点击：例如跳转 AI 会话「仅活跃」视图 */
  onClick?: () => void;
  /** 无障碍名称（onClick 时建议传入） */
  ariaLabel?: string;
  /** 右上角附加控件（如窗口选择器）。 */
  extra?: ReactNode;
}

export function HeroCard({ label, value, suffix, subnote, icon, tone, onClick, ariaLabel, extra }: HeroCardProps) {
  const c = TONE_PALETTE[tone];
  const interactive = !!onClick;
  return (
    <Card
      size="small"
      styles={{ body: { padding: 18, cursor: interactive ? 'pointer' : undefined } }}
      style={{ ['--am-hero-tint' as string]: c.bg, position: 'relative' }}
      className={`am-hero${interactive ? ' am-clickable' : ''}`}
      onClick={onClick}
      role={interactive ? 'button' : undefined}
      tabIndex={interactive ? 0 : undefined}
      aria-label={interactive ? (ariaLabel || label) : undefined}
      onKeyDown={
        interactive
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onClick?.();
              }
            }
          : undefined
      }
    >
      {extra != null && (
        <div className="am-hero-extra" onClick={(e) => e.stopPropagation()}>
          {extra}
        </div>
      )}
      <div className="am-hero-top">
        <span className="am-hero-chip" style={{ background: c.bg, color: c.fg }}>
          {icon}
        </span>
        <span className="am-hero-label">{label}</span>
      </div>
      <div className="am-hero-value">
        {value}
        {suffix && <span className="am-hero-suffix">{suffix}</span>}
      </div>
      {subnote != null && subnote !== '' && <div className="am-hero-subnote">{subnote}</div>}
    </Card>
  );
}

export interface MetricRowProps {
  label: ReactNode;
  value: ReactNode;
}

/** 次级指标：紧凑一行一项（label 退到 ink-3，value 近黑 600 + tabular-nums，底部发丝线）。 */
export function MetricRow({ label, value }: MetricRowProps) {
  return (
    <div className="am-metric-row">
      <span className="am-metric-label">{label}</span>
      <span className="am-metric-value">{value}</span>
    </div>
  );
}

export default HeroCard;
