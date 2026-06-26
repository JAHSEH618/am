import { useState, type CSSProperties } from 'react';

// 各采集器官方 logo —— 打包为静态资源，离线可用、零外网请求（仅作展示识别用途）。
// 素材取自各工具官网 favicon / 官方品牌字形；hermes / openclaw / openharness 无公开 logo，
// 不在表内即走「品牌色 tile + 首字母」回落标。新增工具图标：把 <code>.svg|png 放进
// assets/collectors/ 并在下表登记一行即可。
import cursorLogo from '../../assets/collectors/cursor.svg';
import claudeLogo from '../../assets/collectors/claude.svg';
import codexLogo from '../../assets/collectors/codex.svg';
import opencodeLogo from '../../assets/collectors/opencode.svg';
import kimicodeLogo from '../../assets/collectors/kimicode.png';
import zcodeLogo from '../../assets/collectors/zcode.png';
// 小众工具的官方标识取自其上游项目（见 agent monitor 源码注释消歧）：
// openclaw=github.com/openclaw、openharness=HKUDS/OpenHarness、hermes=Nous Research
import openclawLogo from '../../assets/collectors/openclaw.png';
import openharnessLogo from '../../assets/collectors/openharness.png';
import hermesLogo from '../../assets/collectors/hermes.png';

/** type_code → 打包 logo 资源 URL。 */
const BUNDLED_LOGO: Record<string, string> = {
  cursor: cursorLogo,
  claude: claudeLogo,
  codex: codexLogo,
  opencode: opencodeLogo,
  kimicode: kimicodeLogo,
  zcode: zcodeLogo,
  openclaw: openclawLogo,
  openharness: openharnessLogo,
  hermes: hermesLogo,
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
  const logo = BUNDLED_LOGO[code];
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

  // 官方 logo：白底圆角 tile + 居中图标（彩色 / 单色字形皆能识别）
  if (logo && !failed) {
    return (
      <div style={{ ...tile, background: '#fff', border: '1px solid var(--am-border)' }}>
        <img
          src={logo}
          alt={label}
          width={Math.round(size * 0.64)}
          height={Math.round(size * 0.64)}
          style={{ objectFit: 'contain', display: 'block' }}
          loading="lazy"
          onError={() => setFailed(true)}
        />
      </div>
    );
  }

  // 回落：品牌色 tile + 自适应对比度首字母（修掉浅底白字几乎看不见的问题）
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
