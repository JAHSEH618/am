/**
 * AIWatch 设计令牌 —— JS 侧单一事实源 (v3 · Apple-native / indigo)
 * ============================================================================
 * 这里是「需要在 JS/TSX 里拿到色值」的唯一出处：ECharts 配置、AntD Tag 之外的圆点、
 * 状态色、内联 style 等。纯 CSS / className 场景走 styles/global.css 的 `:root` 变量，
 * 两边的「原色板 + 语义角色」数值必须一致 —— 改色值要两处一起改。
 *
 * 规范全文见 server/src/main/frontend/DESIGN.md。
 *
 * 设计取舍（与 PRODUCT.md 对齐）：
 *   - 单一强调色 = 品牌钴蓝 cobalt-600 #1a52dc（v4：主色调去紫，由靛紫切到更高级的钴蓝），与
 *     logo / 字标同源。强调色只用于主操作 / 选中 / 当前态 / 状态指示，不做装饰。
 *   - 墨色 5 级阶梯承载层级；正文 ≥ 4.5:1。曾经 66 次散落的 #94a3b8 这类灰，统一收敛到
 *     ink-3（可读次级）或 ink-5（仅占位符 / 禁用），不再让承载信息的文字掉到 3:1 以下。
 *   - 语义状态 {base, fg, bg, border} 四件套：base=圆点/描边，fg=同色系深色文字（达标对比），
 *     bg=极浅底，border=浅描边。把曾经的「4 种绿 / 4 种橙 / 3 种蓝」压成每语义一套。
 */

// ───────────────────────────── 原色板 (raw ramps) ─────────────────────────────

/**
 * 品牌主色阶 = cobalt 钴蓝（v4：主色调由靛紫 indigo 切到更高级、去紫的钴蓝）。
 * 导出名沿用 `indigo` 以零成本套用全部既有引用；语义即「品牌主色」。深端钴蓝沉稳、
 * 数据场景友好，且与玻璃 chrome / 墨色阶梯协调。
 */
export const indigo = {
  50: '#eef4ff',
  100: '#dbe8ff',
  200: '#bdd4ff', // Hero ring / brand-border
  300: '#8fb4ff',
  400: '#5a8cf8',
  500: '#2e6bf0', // 次级亮调 / running / icon 渐变浅端
  600: '#1a52dc', // ← 主强调色 (primary) 钴蓝
  700: '#1644b6', // 文字达标深色 (brand-fg) ≈ 7.4:1
  800: '#163a90',
  900: '#15326f',
} as const;

/** 紫罗兰色 —— v4 起不再参与品牌（主色调已去紫）；仅作分类强调（accent.violet：thinking 角色 / 图表系列）。 */
export const violet = {
  400: '#a78bfa',
  500: '#8b5cf6',
  600: '#7c3aed',
} as const;

// ───────────────────────────── 墨色 / 表面 / 描边 ─────────────────────────────

/** 墨色阶梯：数字越小越深。承载信息的文字不要用到 ink-4 以下。 */
export const ink = {
  1: '#1d1f26', // 标题 / 关键数据：近黑
  2: '#44464f', // 正文 / 主要标签   ≈ 8.4:1
  3: '#6a6c75', // 次级标签 / 说明   ≈ 4.9:1（替代散落的 #64748b / #475569）
  4: '#8a8c94', // 弱提示 / 大字辅文（不承载关键信息）
  5: '#b3b5bd', // 占位符 / 禁用 / 「—」空值（替代 #cbd5e1）
} as const;

/** 表面分层：从底到上 */
export const surface = {
  page: '#f4f5f8', // 应用底色（玻璃 chrome 之下；真实底色由 global.css 柔光背景叠加）
  card: '#ffffff', // 数据卡 / 弹层不透明白底（读数可信优先）
  sunken: '#f7f8fa', // 第二中性层：工具栏 / 过滤条 / 表头区（略冷）
  canvasDark: '#0b1220', // 深色画布：登录页命令块等
} as const;

/** 描边（统一走 slate 透明度，跟随底色加深） */
export const border = {
  subtle: 'rgba(15, 23, 42, 0.06)',
  default: 'rgba(15, 23, 42, 0.10)',
  strong: 'rgba(15, 23, 42, 0.14)',
} as const;

// ───────────────────────────── 语义状态 (semantic) ─────────────────────────────

export interface SemanticRole {
  /** 圆点 / 描边 / 实心强调 */
  base: string;
  /** 同色系深色文字（在白底或浅底上达标对比） */
  fg: string;
  /** 极浅底（chip / 提示块背景） */
  bg: string;
  /** 浅描边 */
  border: string;
}

/** 一语义一套四件套 —— 全站状态/提示/标签只引用这里，杜绝同义异色 */
export const semantic = {
  brand: { base: indigo[600], fg: indigo[700], bg: indigo[50], border: indigo[200] },
  success: { base: '#10b981', fg: '#047857', bg: '#ecfdf5', border: '#a7f3d0' },
  warning: { base: '#f59e0b', fg: '#b45309', bg: '#fffbeb', border: '#fde68a' },
  error: { base: '#ef4444', fg: '#dc2626', bg: '#fef2f2', border: '#fecaca' },
  info: { base: indigo[500], fg: indigo[700], bg: indigo[50], border: indigo[200] },
  neutral: { base: ink[4], fg: ink[3], bg: '#f1f2f4', border: border.default },
} as const;

export type SemanticName = keyof typeof semantic;

/**
 * 分类强调色 —— 用于「需要彼此区分、但不是状态语义」的场景：消息角色
 * （user=brand / assistant=success / tool=warning 已覆盖；subagent=sky / thinking=violet）、
 * 设置页分区图标、图表多系列。每色一套 {base, fg, bg, border}，避免再散落随机 hex。
 */
export const accent = {
  sky: { base: '#0ea5e9', fg: '#0369a1', bg: '#f0f9ff', border: '#bae6fd' },
  violet: { base: violet[600], fg: '#6d28d9', bg: '#f5f3ff', border: '#ddd6fe' },
  purple: { base: '#a855f7', fg: '#7e22ce', bg: '#faf5ff', border: '#e9d5ff' },
  teal: { base: '#0d9488', fg: '#0f766e', bg: '#f0fdfa', border: '#99f6e4' },
  blue: { base: '#3b82f6', fg: '#1d4ed8', bg: '#eff6ff', border: '#bfdbfe' },
  rose: { base: '#f43f5e', fg: '#e11d48', bg: '#fff1f2', border: '#fecdd3' },
} as const;

export type AccentName = keyof typeof accent;

/**
 * 会话状态分类色 —— 这 10 种是「需要彼此可区分」的类别色，故保留多色相，
 * 但 thinking 从旧 #2563eb 收敛到品牌靛紫，running/spawning 等保持原可辨识色相。
 * 与 utils/format.ts statusColor() 同源（那里从此处取值）。
 */
export const statusHue = {
  idle: '#94a3b8', // 冷调 slate：与「空闲」语义绑定，比中性灰 ink-4 更可辨
  waiting: semantic.warning.base,
  thinking: indigo[600], // was #2563eb → 收敛到品牌靛紫
  compacting: '#0d9488',
  reading: semantic.success.base,
  writing: '#ea580c',
  running: indigo[500],
  searching: '#db2777',
  browsing: '#3b82f6',
  spawning: '#f97316',
} as const;

// ───────────────────────────── 形状 / 投影 / 动效 ─────────────────────────────

/** 苹果式连续圆角 */
export const radius = { sm: 8, md: 12, lg: 16, xl: 22, pill: 999 } as const;

/** 苹果分层投影：贴地接触 + 柔和环境，叠内高光由 global.css 的 --am-inner-hi 提供 */
export const shadow = {
  sm: '0 1px 2px rgba(15, 23, 42, 0.05), 0 4px 14px -6px rgba(15, 23, 42, 0.10)',
  md: '0 2px 6px rgba(15, 23, 42, 0.06), 0 14px 34px -10px rgba(15, 23, 42, 0.16)',
  pop: '0 8px 24px -6px rgba(15, 23, 42, 0.18), 0 2px 6px rgba(15, 23, 42, 0.08)',
  innerHi: 'inset 0 1px 0 rgba(255, 255, 255, 0.6)',
} as const;

/** 苹果缓动 + 时长（产品态过渡 150–250ms） */
export const motion = {
  ease: 'cubic-bezier(0.32, 0.72, 0, 1)',
  durFast: '0.14s',
  dur: '0.2s',
  durSlow: '0.32s',
} as const;

// ───────────────────────────── 字号 / 字重 ─────────────────────────────

/** 固定 rem 不漂移的产品级字号阶梯（产品态不用 clamp 流体字号） */
export const fontSize = {
  xs: 12,
  sm: 13,
  md: 14,
  lg: 16,
  xl: 20,
  '2xl': 28,
  // 表达式高级感：Hero 数字专用展示档（紧排 + 等宽数字）
  displaySm: 30,
  display: 36,
  displayLg: 44,
} as const;

export const fontWeight = {
  regular: 400,
  medium: 500,
  semibold: 600,
  bold: 680,
} as const;

/** SF 字族栈 + 中文回退 */
export const fontFamily =
  "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'SF Pro Display', 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', sans-serif";

export const fontMono =
  "'SF Mono', SFMono-Regular, ui-monospace, 'Menlo', 'Consolas', monospace";

// ───────────────────────────── 品牌材质 ─────────────────────────────

/** 字标 / 品牌位渐变（全站唯一允许的渐变文字，仅此一处）。v4：钴蓝→azure→cyan，去紫。 */
export const brandGradient =
  'linear-gradient(118deg, #1a52dc 0%, #2e6bf0 48%, #18b5d8 100%)';

/** Liquid Glass 材质（仅用于 chrome：侧栏 / 顶栏 / 弹层） */
export const glass = {
  bg: 'linear-gradient(180deg, rgba(255, 255, 255, 0.82), rgba(255, 255, 255, 0.66))',
  blur: 'saturate(180%) blur(22px)',
  hairline: 'rgba(15, 23, 42, 0.08)',
  highlight: 'rgba(255, 255, 255, 0.7)',
} as const;

// ───────────────────────────── z-index 语义层级 ─────────────────────────────

export const z = {
  sticky: 100,
  header: 200,
  dropdown: 1050,
} as const;

// ───────────────────────────── 便捷别名（高频引用）─────────────────────────────

/**
 * 高频内联色别名 —— 让页面里 `color: TXT.muted` 这种读得懂、改一处即可。
 * 凡是想写死 #94a3b8 / #64748b / #cbd5e1 的地方，改用这里。
 */
export const TXT = {
  title: ink[1],
  body: ink[2],
  secondary: ink[3],
  muted: ink[4],
  faint: ink[5],
  brand: indigo[600],
  link: indigo[600],
} as const;
