import dayjs from 'dayjs';

/** 前端「多久未动」灰化提示阈值；应 ≥ 2min 上报间隔 × 1.5，避免 tick 间误灰。 */
export const STALE_VISUAL_THRESHOLD_SECONDS = 180;

export function formatDuration(seconds: number | null | undefined): string {
  if (!seconds || seconds < 0) return '0 分';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h <= 0) return `${m} 分`;
  return `${h} 时 ${m} 分`;
}

export function formatTokens(n: number | null | undefined): string {
  if (!n) return '0';
  // 按量级缩写时取绝对值再补回符号：异常数据（如客户端计数回退算出的负 token）也能缩成
  // "-92.50 M" 而非原样输出 "-92504752" 撑破卡片。正常正值不受影响。
  const sign = n < 0 ? '-' : '';
  const abs = Math.abs(n);
  if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(2)} M`;
  if (abs >= 1_000) return `${sign}${(abs / 1_000).toFixed(1)} k`;
  return n.toString();
}

/** 横条图等紧凑场景：Token 统一按百万 (M) 显示，如 3.66M */
export function formatTokensM(n: number | null | undefined, digits = 2): string {
  if (n == null || n <= 0) return '0';
  const m = n / 1_000_000;
  if (m >= 100) return `${m.toFixed(0)}M`;
  if (m >= 10) return `${m.toFixed(1)}M`;
  return `${m.toFixed(digits)}M`;
}

export function formatTime(s: string | null | undefined, fmt = 'MM-DD HH:mm:ss'): string {
  if (!s) return '-';
  return dayjs(s).format(fmt);
}

export function formatTimeFromNow(s: string | null | undefined): string {
  if (!s) return '-';
  const sec = dayjs().diff(dayjs(s), 'second');
  if (sec < 60) return `${sec} 秒前`;
  if (sec < 3600) return `${Math.floor(sec / 60)} 分前`;
  if (sec < 86400) return `${Math.floor(sec / 3600)} 时前`;
  return dayjs(s).format('MM-DD HH:mm');
}

// v2.8 起删除 STATUS_STALE_THRESHOLD_SECONDS / isStatusStale：
// 旧逻辑给 status != idle 且 last_activity > 180s 的会话打"离线"标 + 灰化，与"status=idle
// 且几十天未动的会话不会被标离线"口径不一致，反而让用户困惑。前端直接渲染原始 status，
// 真正的"陈旧"由 last_activity 列呈现。后端 DashboardController 内部自有同名常量，
// 用于 hero 卡 active_ai_sessions 计数（不依赖前端），保留不动。

export function statusColor(status: string | null | undefined): string {
  switch ((status || '').toLowerCase()) {
    case 'idle': return '#94a3b8';
    case 'waiting': return '#f59e0b';
    case 'thinking': return '#2563eb';
    case 'compacting': return '#0d9488';
    case 'reading': return '#10b981';
    case 'writing': return '#ea580c';
    case 'running': return '#6366f1';
    case 'searching': return '#db2777';
    case 'browsing': return '#3b82f6';
    case 'spawning': return '#f97316';
    default: return '#94a3b8';
  }
}

/**
 * 把 AntD 的 preset color 名（geekblue / magenta / ...）转成实际 hex，
 * 用在 Tag 之外的场景：Select option 前的圆点、SourceFilter 等。
 *
 * 取 AntD 5 默认主题 -6 级（即 Tag 默认渲染时的饱和度）。
 */
export function presetColorToHex(name: string | null | undefined): string {
  switch ((name || '').toLowerCase()) {
    case 'blue': return '#1677ff';
    case 'geekblue': return '#2f54eb';
    case 'cyan': return '#13c2c2';
    case 'green': return '#52c41a';
    case 'lime': return '#a0d911';
    case 'gold': return '#faad14';
    case 'yellow': return '#fadb14';
    case 'orange': return '#fa8c16';
    case 'red': return '#f5222d';
    case 'volcano': return '#fa541c';
    case 'magenta':
    case 'pink': return '#eb2f96';
    case 'purple': return '#722ed1';
    default: return '#94a3b8';
  }
}

/**
 * 是否处于"正在跑工具"的状态。决定 UI 要不要在状态旁边渲染 current_tool tag —— 否则
 * 30s 后会话回到 idle，但 current_tool 还残留，前端会出现"空闲 [Edit]"的矛盾组合。
 * 与后端 AbstractAiSessionIngestService.isToolStatus() 保持一致。
 */
export function isToolStatus(status: string | null | undefined): boolean {
  switch ((status || '').toLowerCase()) {
    case 'reading':
    case 'writing':
    case 'running':
    case 'searching':
    case 'browsing':
    case 'spawning':
      return true;
    default:
      return false;
  }
}

export function statusLabel(status: string | null | undefined): string {
  switch ((status || '').toLowerCase()) {
    case 'idle': return '空闲';
    case 'waiting': return '等待用户';
    case 'thinking': return '思考中';
    case 'compacting': return '压缩上下文';
    case 'reading': return '读取';
    case 'writing': return '写入';
    case 'running': return '执行';
    case 'searching': return '检索';
    case 'browsing': return '浏览';
    case 'spawning': return '派生';
    default: return status || '-';
  }
}

export function formatMessageDelta(delta: number): string | null {
  if (!delta) return null;
  return `${delta > 0 ? '+' : ''}${delta} msg`;
}

export function messageDeltaTagColor(delta: number): string {
  return delta > 0 ? 'cyan' : 'orange';
}

export function eventTypeLabel(type: string): string {
  switch (type) {
    case 'SESSION_OPEN': return '会话开启';
    case 'SESSION_CLOSE': return '会话关闭';
    case 'STATUS_CHANGE': return '状态变化';
    case 'TOOL_CALL': return '工具调用';
    case 'MESSAGE_DELTA': return '消息增量';
    case 'TOKEN_DELTA': return 'Token 增量';
    default: return type;
  }
}

/**
 * Cursor 会员档展示文案。Cursor 服务端字段为 lowerCase 下划线 / 单词形态：
 *   free / pro / pro_plus / business / ultra / enterprise
 */
export function membershipLabel(t: string | null | undefined): string {
  switch ((t || '').toLowerCase()) {
    case 'free': return 'Free';
    case 'pro': return 'Pro';
    case 'pro_plus': return 'Pro+';
    case 'business': return 'Business';
    case 'ultra': return 'Ultra';
    case 'enterprise': return 'Enterprise';
    case 'team': return 'Team';
    default: return t || '-';
  }
}

export function membershipColor(t: string | null | undefined): string {
  switch ((t || '').toLowerCase()) {
    case 'free': return 'default';
    case 'pro': return 'blue';
    case 'pro_plus': return 'geekblue';
    case 'business':
    case 'team': return 'purple';
    case 'ultra': return 'magenta';
    case 'enterprise': return 'gold';
    default: return 'default';
  }
}

/**
 * Cursor 注册渠道展示。原始值（采样）：Auth_0 / Google / GitHub / Email
 * Auth_0 是 Cursor 自建邮箱账号，向用户解释成 "邮箱" 比 "Auth0" 更直观。
 */
export function signupTypeLabel(t: string | null | undefined): string {
  if (!t) return '';
  const lower = t.toLowerCase();
  if (lower === 'auth_0' || lower === 'auth0') return '邮箱';
  if (lower === 'google') return 'Google';
  if (lower === 'github') return 'GitHub';
  if (lower === 'email') return '邮箱';
  return t;
}

/**
 * Provider 来源（target_type）展示文案 + 颜色。
 *
 * 优先从注入的 map 中查（来自 GET /api/v1/monitor-targets，含 type_name/display_color），
 * 命中不到再退回硬编码兜底——这样老页面不传 map 也能正常工作，新接入的 Provider
 * 只要在 monitor_target 表加一行，前端 Segmented + Tag 立即就有正确的展示。
 */
export interface TargetTypeMeta {
  label: string;
  color: string;
}

export type TargetTypeMap = Record<string, TargetTypeMeta>;

export function targetTypeLabel(
  t: string | null | undefined,
  map?: TargetTypeMap,
): string {
  const key = (t || '').toLowerCase();
  if (map && map[key]) return map[key].label;
  switch (key) {
    case 'cursor': return 'Cursor';
    case 'claude': return 'Claude Code';
    case 'codex': return 'Codex CLI';
    case 'hermes': return 'Hermes Agent';
    case 'openclaw': return 'OpenClaw';
    case 'openharness': return 'OpenHarness';
    case 'opencode': return 'OpenCode';
    case 'kimicode': return 'Kimi Code';
    case 'zcode': return 'Z Code';
    default: return t || '-';
  }
}

export function targetTypeColor(
  t: string | null | undefined,
  map?: TargetTypeMap,
): string {
  const key = (t || '').toLowerCase();
  if (map && map[key]) return map[key].color;
  switch (key) {
    case 'cursor': return 'geekblue';
    case 'claude': return 'magenta';
    case 'codex': return 'green';
    case 'hermes': return 'purple';
    case 'openclaw': return 'orange';
    case 'openharness': return 'cyan';
    case 'opencode': return 'blue';
    case 'kimicode': return 'gold';
    case 'zcode': return 'volcano';
    default: return 'default';
  }
}

export function eventTypeColor(type: string): string {
  switch (type) {
    case 'SESSION_OPEN': return 'green';
    case 'SESSION_CLOSE': return 'default';
    case 'STATUS_CHANGE': return 'blue';
    case 'TOOL_CALL': return 'purple';
    case 'MESSAGE_DELTA': return 'cyan';
    case 'TOKEN_DELTA': return 'gold';
    default: return 'default';
  }
}
