import { useState, type CSSProperties } from 'react';

/**
 * 采集器（Agent）品牌图标。
 *
 * <p>「Agent 列表」每张卡片左上角的图标：优先用各工具**官方站点的 favicon/logo**（运行时 `<img>`
 * 加载，仅作展示识别用途），加载失败（无公开 logo / 内网被墙）自动回落到「品牌色 tile + 首字母」。
 *
 * <p>回落标按底色亮度自适应字色（修掉 Cursor / Z Code 浅底白字几乎看不见的问题），浅底再补一道描边。
 */

// type_code → 官方站点图标 URL。仅收录有稳定公开站点的工具；其余（hermes / openclaw / openharness）
// 无公开 logo，留空即走字母标回落。注：claude.ai / openai.com / cursor.com 在部分内网可能不可达，
// 不可达时同样回落，不影响功能。
const OFFICIAL_ICON: Record<string, string> = {
  cursor: 'https://www.cursor.com/favicon.ico',
  claude: 'https://claude.ai/favicon.ico',
  codex: 'https://openai.com/favicon.ico',
  opencode: 'https://opencode.ai/favicon.ico',
  kimicode: 'https://www.kimi.com/favicon.ico',
  zcode: 'https://z.ai/favicon.ico',
};

/** 相对亮度（0~1），用于决定回落标的字色 / 是否描边。 */
function luminance(hex: string): number {
  const m = /^#?([0-9a-f]{6})$/i.exec((hex || '').trim());
  if (!m) return 0;
  const n = parseInt(m[1], 16);
  const r = (n >> 16) & 255;
  const g = (n >> 8) & 255;
  const b = n & 255;
  return (0.299 * r + 0.587 * g + 0.114 * b) / 255;
}

export interface CollectorMarkProps {
  /** 采集器 type_code，如 cursor / claude / codex / ... */
  code: string;
  /** 展示名（用于 alt / aria-label） */
  name?: string;
  /** 品牌色（回落标底色 / 字色基准） */
  color: string;
  /** 是否启用（禁用时整体降透明度，与卡片一致） */
  enabled?: boolean;
  /** tile 边长（px） */
  size?: number;
}

export function CollectorMark({
  code,
  name,
  color,
  enabled = true,
  size = 36,
}: CollectorMarkProps) {
  const url = OFFICIAL_ICON[code];
  const [failed, setFailed] = useState(false);
  const label = name || code;

  const tile: CSSProperties = {
    width: size,
    height: size,
    borderRadius: 8,
    flexShrink: 0,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
    opacity: enabled ? 1 : 0.35,
    transition: 'opacity .2s',
  };

  // 官方 logo：白底圆角 tile + 居中图标
  if (url && !failed) {
    return (
      <div style={{ ...tile, background: '#fff', border: '1px solid var(--am-border)' }}>
        <img
          src={url}
          alt={label}
          width={Math.round(size * 0.64)}
          height={Math.round(size * 0.64)}
          style={{ objectFit: 'contain', display: 'block' }}
          loading="lazy"
          referrerPolicy="no-referrer"
          onError={() => setFailed(true)}
        />
      </div>
    );
  }

  // 回落：品牌色 tile + 自适应对比度首字母
  const L = luminance(color);
  const fg = L > 0.6 ? '#1d1f26' : '#fff';
  const border = L > 0.82 ? '1px solid var(--am-border)' : 'none';
  return (
    <div
      style={{
        ...tile,
        background: color,
        color: fg,
        border,
        fontWeight: 700,
        fontSize: Math.round(size * 0.39),
        textTransform: 'uppercase',
      }}
      role="img"
      aria-label={label}
    >
      {code.slice(0, 2)}
    </div>
  );
}
