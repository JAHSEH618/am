import axios, { AxiosError, type AxiosResponse } from 'axios';
import { App as AntdApp } from 'antd';
import type {
  AgentAlert,
  AiPenetration,
  AiSessionAuditDetail,
  AiSession,
  AiSessionEvent,
  AiSessionMessage,
  AnalysisReportDetail,
  AnalysisReportListItem,
  AnalysisReportProgress,
  AnalysisReportUser,
  DashboardInsightAuditFast,
  DashboardInsightAuditSlow,
  DashboardOverview,
  InstallStatus,
  ModelDistribution,
  ModelHeatmap,
  JudgeTestResult,
  MonitorTarget,
  MonitorTargetAdmin,
  NameValuePair,
  OnlineAgent,
  ScheduledTaskStatus,
  PageDto,
  PeopleDetail,
  PeopleSummary,
  ProjectDetail,
  ProjectGitCommit,
  GitCommitPatchResponse,
  ProjectSummary,
  R,
  TokenTrend,
  ToolStat,
  TopItem,
} from './types';

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
  // 同源 + 后端用 HttpSession Cookie 维持登录态：必须带上 cookie，否则后续业务接口都 401
  withCredentials: true,
});

let messageHandle: ReturnType<typeof AntdApp.useApp>['message'] | null = null;
export function bindMessageHandle(handle: ReturnType<typeof AntdApp.useApp>['message']) {
  messageHandle = handle;
}

http.interceptors.response.use(
  (resp: AxiosResponse<R<unknown>>) => resp,
  (err: AxiosError<R<unknown>>) => {
    // 401 → 跳登录页（保留 from，登录后跳回原页）
    // 由全局守卫消费：抛 'AUTH_REQUIRED' 让 RequireAuth 捕获后跳转，
    // 避免每个调用方都自己处理 401
    if (err.response?.status === 401) {
      // 控制台挂在 /console 基址下，硬跳必须带上前缀，否则会落到公开落地页 /。
      const base = '/console';
      const full = window.location.pathname + window.location.search;
      const here = full.startsWith(base) ? full.slice(base.length) || '/' : full;
      // 已经在登录页就别再跳，避免死循环
      if (!here.startsWith('/login')) {
        const next = encodeURIComponent(here);
        window.location.replace(`${base}/login?from=${next}`);
      }
      // 401 不弹错误 toast（登录页跳转本身就是反馈）
      return Promise.reject(err);
    }
    const fallback = '请求失败';
    const apiMsg = err.response?.data?.message || err.message || fallback;
    messageHandle?.error(apiMsg);
    return Promise.reject(err);
  },
);

async function unwrap<T>(p: Promise<AxiosResponse<R<T>>>): Promise<T> {
  const resp = await p;
  if (resp.data?.code !== 0) {
    const msg = resp.data?.message || `服务端业务错误 code=${resp.data?.code}`;
    messageHandle?.error(msg);
    throw new Error(msg);
  }
  return resp.data.data;
}

// dashboard
export const fetchOverview = () => unwrap<DashboardOverview>(http.get('/dashboard/overview'));
export const fetchInsightAuditFast = () =>
  unwrap<DashboardInsightAuditFast>(http.get('/dashboard/insight-audit/fast'));
export const fetchInsightAuditSlow = () =>
  unwrap<DashboardInsightAuditSlow>(http.get('/dashboard/insight-audit/slow'));
export const fetchAiPenetration = (window: string) =>
  unwrap<AiPenetration>(http.get('/dashboard/ai-penetration', { params: { window } }));
export const fetchOnline = () => unwrap<OnlineAgent[]>(http.get('/dashboard/online'));
export const fetchTopProjects = (limit = 10) =>
  unwrap<TopItem[]>(http.get('/dashboard/top-projects', { params: { limit } }));
export const fetchTopEmployees = (limit = 10) =>
  unwrap<TopItem[]>(http.get('/dashboard/top-employees', { params: { limit } }));
export const fetchTokenTrend = (days = 30) =>
  unwrap<TokenTrend>(http.get('/dashboard/token-trend', { params: { days } }));

// monitor targets dictionary —— 给前端 Segmented / Tag 颜色映射用
let monitorTargetsCache: MonitorTarget[] | null = null;
let monitorTargetsInflight: Promise<MonitorTarget[]> | null = null;

export const fetchMonitorTargets = () => {
  if (monitorTargetsCache) {
    return Promise.resolve(monitorTargetsCache);
  }
  if (!monitorTargetsInflight) {
    monitorTargetsInflight = unwrap<MonitorTarget[]>(http.get('/monitor-targets')).then((rows) => {
      monitorTargetsCache = rows;
      monitorTargetsInflight = null;
      return rows;
    }).catch((err) => {
      monitorTargetsInflight = null;
      throw err;
    });
  }
  return monitorTargetsInflight;
};

export function invalidateMonitorTargetsCache() {
  monitorTargetsCache = null;
}

// ai sessions
export const fetchSessions = (params: {
  /** 精确按 user_code 过滤（向后兼容老入口，比如项目透视页跳转） */
  user_code?: string;
  /** v2.2 新增：按"姓名 / 工号"模糊搜索；服务端通过 EmployeeDisplayService 反向找 user_code 列表 */
  user_name?: string;
  days?: number;
  /** v2.4 新增：精确时间窗口（yyyy-MM-dd），优先级高于 days；命中后服务端会重算 window_message_count / window_tokens */
  from?: string;
  to?: string;
  // 多 Provider 维度过滤：'all' / 'cursor' / 'cursor,claude'
  target_type?: string;
  /** 按项目名过滤（项目透视页"会话数超链"跳转使用） */
  project_name?: string;
  /** true：只返回 status != idle，与大盘「活跃 AI 会话」口径一致（不按 last_activity 时间窗收窄列表） */
  active_only?: boolean;
  /**
   * v2.11：是否包含 invalid_reason 非空的无效会话（如 Claude Code 本地命令空跑）。
   * 默认 false（隐藏）；运维查脏数据时切到 true 查看全量。
   */
  include_invalid?: boolean;
  page?: number;
  size?: number;
}) => unwrap<PageDto<AiSession>>(http.get('/ai-sessions', { params }));

export const fetchSession = (id: number, params?: { from?: string; to?: string }) =>
  unwrap<AiSession>(http.get(`/ai-sessions/${id}`, { params }));

/** 会话审计完整内容（弹框懒加载，含 judge 说明）；说明可能较长，超时放宽 */
export const fetchSessionAuditDetail = (id: number) =>
  unwrap<AiSessionAuditDetail>(
    http.get(`/ai-sessions/${id}/audit`, { timeout: 120_000 }),
  );

export const fetchSessionMessages = (
  id: number,
  params: { page?: number; size?: number; from?: string; to?: string; role?: string; roles?: string } = {},
) =>
  unwrap<PageDto<AiSessionMessage>>(
    http.get(`/ai-sessions/${id}/messages`, {
      params: { page: 0, size: 100, ...params },
    }),
  );

export const fetchSessionEvents = (
  id: number,
  params: { page?: number; size?: number; from?: string; to?: string } = {},
) =>
  unwrap<PageDto<AiSessionEvent>>(
    http.get(`/ai-sessions/${id}/events`, {
      params: { page: 0, size: 50, ...params },
    }),
  );

// stats
export const fetchToolStats = (params: { from?: string; to?: string; limit?: number }) =>
  unwrap<ToolStat[]>(http.get('/stats/tools', { params }));

// alerts
export const fetchAlerts = (params: {
  type?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}) => unwrap<PageDto<AgentAlert>>(http.get('/alerts', { params }));

// ===== v2.1 Phase 2 API =====

export const fetchPeople = (params?: { from?: string; to?: string }) =>
  unwrap<PeopleSummary[]>(http.get('/people', { params }));

export const fetchPersonDetail = (userCode: string, params?: { from?: string; to?: string }) =>
  unwrap<PeopleDetail>(http.get(`/people/${encodeURIComponent(userCode)}`, { params }));

export const fetchPersonGitCommits = (
  userCode: string,
  params?: { from?: string; to?: string; limit?: number },
) =>
  unwrap<ProjectGitCommit[]>(
    http.get(`/people/${encodeURIComponent(userCode)}/git-commits`, { params }),
  );

export const fetchPersonSlashCommands = (
  userCode: string,
  params?: { from?: string; to?: string; limit?: number },
) =>
  unwrap<NameValuePair[]>(
    http.get(`/people/${encodeURIComponent(userCode)}/slash-commands`, { params }),
  );

export const fetchProjects = (params?: { from?: string; to?: string }) =>
  unwrap<ProjectSummary[]>(http.get('/projects', { params }));

export const fetchProjectDetail = (projectName: string, params?: { from?: string; to?: string }) =>
  unwrap<ProjectDetail>(http.get(`/projects/${encodeURIComponent(projectName)}`, { params }));

export const fetchProjectGitCommits = (
  projectName: string,
  params?: { from?: string; to?: string; limit?: number },
) =>
  unwrap<ProjectGitCommit[]>(
    http.get(`/projects/${encodeURIComponent(projectName)}/git-commits`, { params }),
  );

export const fetchGitCommitPatch = (params: {
  repo_url: string;
  commit_hash: string;
  path: string;
}) =>
  unwrap<GitCommitPatchResponse>(http.get('/git-commits/patch', { params }));

export const fetchModelDistribution = (params?: { from?: string; to?: string }) =>
  unwrap<ModelDistribution[]>(http.get('/models/distribution', { params }));

export const fetchModelHeatmap = (params?: { from?: string; to?: string }) =>
  unwrap<ModelHeatmap>(http.get('/models/heatmap', { params }));

// install
// 注意：登录页（未登录态）也会调这个接口拉 base_url_path / platforms / missing_files。
// SecurityConfig 里 /api/v1/install/** 必须 permitAll。
export const fetchInstallStatus = () =>
  unwrap<InstallStatus>(http.get('/install/status'));

// ========== v3.0 分析报告（仅管理员）==========
// 路由前缀 /admin/analysis 走 AdminTokenInterceptor，登录态 session 即可放行；
// 自动化脚本走 X-Admin-Token 头。

/** 触发生成或重跑（force=true 强制重新审计）。 */
export const generateAnalysisReport = (params: { from: string; to: string; force?: boolean }) =>
  unwrap<AnalysisReportProgress>(
    http.post('/admin/analysis/generate', null, { params }),
  );

/** 查找同窗口是否已有报告（不触发生成），返回 null 表示无。 */
export const findAnalysisReport = (params: { from: string; to: string }) =>
  unwrap<AnalysisReportProgress | null>(http.get('/admin/analysis', { params }));

/** 已生成的最近 50 个报告（侧栏历史）。 */
export const fetchAnalysisHistory = () =>
  unwrap<AnalysisReportListItem[]>(http.get('/admin/analysis/history'));

/** 报告详情：团队摘要 + 员工列表。 */
export const fetchAnalysisReportDetail = (reportId: number) =>
  unwrap<AnalysisReportDetail>(http.get(`/admin/analysis/${reportId}`));

/** 单员工详情（与 detail.users 单项相同，分开 endpoint 便于轮询单人）。 */
export const fetchAnalysisReportUser = (reportId: number, userCode: string) =>
  unwrap<AnalysisReportUser>(
    http.get(`/admin/analysis/${reportId}/users/${encodeURIComponent(userCode)}`),
  );

/** 生成中轮询进度。 */
export const fetchAnalysisReportProgress = (reportId: number) =>
  unwrap<AnalysisReportProgress>(http.get(`/admin/analysis/${reportId}/progress`));

/** 删除历史报告（生成中不可删）。 */
export const deleteAnalysisReport = (reportId: number) =>
  unwrap<void>(http.delete(`/admin/analysis/${reportId}`));

// ========== auth ==========
export interface AuthMe { username: string }
export const login = (username: string, password: string) =>
  unwrap<AuthMe>(http.post('/auth/login', { username, password }));
export const logout = () => unwrap<void>(http.post('/auth/logout'));
export const fetchMe = () => unwrap<AuthMe>(http.get('/auth/me'));

// ========== v2.10 系统设置 ==========
// 全部走 /api/v1/admin/** → AdminTokenInterceptor 鉴权（session 或 X-Admin-Token）。

/** 列出全部 monitor_target（含 enabled=0），附带最近 7 天会话数用于管理员决策。 */
export const fetchMonitorTargetsAdmin = () =>
  unwrap<MonitorTargetAdmin[]>(http.get('/admin/monitor-targets'));

/** 切换某 agent 的激活开关（不影响客户端采集，只影响展示/聚合白名单）。 */
export const setMonitorTargetEnabled = (typeCode: string, value: 0 | 1) =>
  unwrap<MonitorTarget>(
    http.patch(`/admin/monitor-targets/${encodeURIComponent(typeCode)}/enabled`, null, {
      params: { value },
    }),
  );

// 定时任务（business 类）：cron / 启停 / 手动触发都热生效。
export const fetchScheduledTasks = () =>
  unwrap<ScheduledTaskStatus[]>(http.get('/admin/scheduled-tasks'));

export const updateScheduledTask = (
  taskCode: string,
  payload: { cron?: string; enabled?: boolean },
) =>
  unwrap<ScheduledTaskStatus>(
    http.patch(`/admin/scheduled-tasks/${encodeURIComponent(taskCode)}`, payload),
  );

export const triggerScheduledTask = (taskCode: string) =>
  unwrap<ScheduledTaskStatus>(
    http.post(`/admin/scheduled-tasks/${encodeURIComponent(taskCode)}/trigger`),
  );

// Judge 模型 + 评判参数（热更新；admin 走鉴权后直接看明文，方便核对/复制）。
export const fetchInsightConfig = () =>
  unwrap<Record<string, string>>(http.get('/admin/insight-config'));

export const saveInsightConfig = (payload: Record<string, string>) =>
  unwrap<Record<string, string>>(http.put('/admin/insight-config', payload));

export const testJudgeConnection = (slot: 'a' | 'b') =>
  unwrap<JudgeTestResult>(http.post('/admin/insight-config/test', null, { params: { slot } }));

// 会话内容采集限额（服务端 ingest / 审计兜底）
export const fetchCaptureConfig = () =>
  unwrap<Record<string, string>>(http.get('/admin/capture-config'));

export const saveCaptureConfig = (payload: Record<string, string>) =>
  unwrap<Record<string, string>>(http.put('/admin/capture-config', payload));

// 鉴权与安全（账号 / 密码 / admin-token；改完立即生效，已登录会话不强制踢出）
export const fetchAuthConfig = () =>
  unwrap<Record<string, string>>(http.get('/admin/auth-config'));

export const saveAuthConfig = (payload: Record<string, string>) =>
  unwrap<Record<string, string>>(http.put('/admin/auth-config', payload));

export const rotateAdminToken = () =>
  unwrap<{ admin_token: string; note: string }>(http.post('/admin/auth-config/rotate-token'));

// 操作日志（sys_config 变更流水）
export interface SysConfigAuditRow {
  id: number;
  config_key: string;
  category: string;
  is_secret: number;
  old_value: string | null;
  new_value: string | null;
  operator: string | null;
  changed_time: string;
}
export const fetchSysConfigAudit = (params: {
  category?: string;
  key?: string;
  page?: number;
  size?: number;
} = {}) =>
  unwrap<PageDto<SysConfigAuditRow>>(
    http.get('/admin/sys-config-audit', { params }),
  );

export default http;
