import dayjs from 'dayjs';
import { statusHue, ink } from '../styles/tokens';

/** 前端「多久未动」灰化提示阈值；应 ≥ 2min 上报间隔 × 1.5，避免 tick 间误灰。 */
export const STALE_VISUAL_THRESHOLD_SECONDS = 180;

/**
 * 员工显示名规整。
 *
 * <p>`user_display` 形如「姓名|工号/邮箱」，且姓名常带 git author 残留的尖括号
 * （如 `<祁连博>|<qi_lb@his.com>`）。这里只取「姓名」段并去掉非中文符号，最终只留中文姓名
 * （保留少数民族姓名间隔号 `·`/`・`）。
 *
 * <p>兜底：姓名段无中文时（纯英文名 / 只有工号）退回原姓名段，避免清成空串。
 */
export function employeeName(
  display?: string | null,
  fallback?: string | null,
): string {
  const raw = (display ?? '').trim() || (fallback ?? '').trim();
  if (!raw) return '';
  const namePart = raw.split('|')[0].trim();
  // 保留：CJK 统一表意 一-鿿、扩展 A 㐀-䶿、姓名间隔号 ·(·) ・(・)
  const cleaned = namePart.replace(/[^一-鿿㐀-䶿·・]/g, '');
  return cleaned || namePart || raw;
}

/**
 * 模型名规整。
 *
 * <p>原始模型 ID 常带厂商路由前缀（`anthropic/`、`us.anthropic.`、`models/`）、末尾日期戳
 * （`-20250514`、`-2024-08-06`）与版本尾巴（`-v1:0`、`-preview`），看板里很难一眼认出是哪个模型。
 * 这里收成清晰短名：`claude-sonnet-4-20250514` → `claude-sonnet-4`、`gpt-4o-2024-08-06` → `gpt-4o`。
 *
 * <p>只做「去噪」不做厂商映射，国产模型（glm / kimi / qwen / deepseek）原样保留，永不误伤；
 * 去噪后为空（极端脏值）时退回原串。完整版本号仍可在单元格 hover 的 title 里看到。
 */
export function modelLabel(raw?: string | null): string {
  const s = (raw ?? '').trim();
  if (!s) return '';
  let id = s.replace(/[<>]/g, '').trim();
  id = id.replace(/^(?:[\w.\-]+\/)+/, ''); // 去所有路径段前缀 a/b/c -> c
  id = id.replace(/^(?:us|eu|apac|global)\./i, ''); // bedrock 区域前缀
  id = id.replace(
    /^(?:anthropic|openai|google|meta|mistral|deepseek|qwen|moonshot|zhipu)\.(?=[a-z])/i,
    '',
  );
  id = id
    .replace(/[-_@:]\d{4}-?\d{2}-?\d{2}$/, '') // -20250514 / -2024-08-06
    .replace(/[-_:]v\d+(?::\d+)?$/i, '') // -v1:0 (bedrock)
    .replace(/[-_](?:latest|preview|exp|experimental|beta|stable)$/i, '')
    .replace(/[-_@:]\d{6,}$/, '') // 其它纯数字尾巴 -250514
    .trim();
  return id || s;
}

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

/** 会话状态分类色：统一从 tokens.statusHue 取，色值不再散落在此 */
export function statusColor(status: string | null | undefined): string {
  const key = (status || '').toLowerCase() as keyof typeof statusHue;
  return statusHue[key] ?? statusHue.idle;
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
    default: return ink[4];
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
    case 'pro_plus': return 'cyan';
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
    case 'cursor': return 'lime';
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
