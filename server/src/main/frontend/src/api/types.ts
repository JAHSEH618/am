// 后端返回类型镜像（保持 snake_case 字段名以匹配 Jackson 序列化结果）
// gz

export interface R<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageDto<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

// 监控目标字典：与后端 monitor_target 表 + MonitorTargetDto 对齐
export interface MonitorTarget {
  type_code: string;
  type_name: string;
  enabled: number;
  display_color: string;
  sort_no: number;
  description: string | null;
}

/**
 * 系统设置 - 活跃 Agent 管理用（含"最近 7 天会话数"决策参考）。
 * 对应后端 MonitorTargetAdminController.MonitorTargetAdminDto。
 */
export interface MonitorTargetAdmin extends MonitorTarget {
  recent7d_session_count: number;
}

/**
 * 系统设置 - Judge / Insight 配置测试连通性结果。
 * 对应后端 InsightConfigAdminController.TestResult。
 */
export interface JudgeTestResult {
  success: boolean;
  latency_ms: number;
  message: string | null;
  sample_difficulty: number | null;
}

/**
 * 系统设置 - 定时任务运行时状态。
 * 对应后端 DynamicScheduledTaskManager.ScheduledTaskStatus。
 */
export interface ScheduledTaskStatus {
  task_code: string;
  display_name: string;
  /** "business" / "infra" */
  category: string;
  description: string | null;
  cron: string;
  default_cron: string;
  enabled: boolean;
  cron_editable: boolean;
  manual_triggerable: boolean;
  /** cron 触发时区 ID，null = JVM 默认 */
  zone_id: string | null;
  running: boolean;
  last_start_time: string | null;
  last_end_time: string | null;
  last_duration_ms: number;
  last_status: 'SUCCESS' | 'FAILED' | null;
  last_error_text: string | null;
  success_count: number;
  failure_count: number;
  next_run_time: string | null;
}

export interface DashboardOverview {
  online_agents: number;
  /** ACTIVE 台账内心跳已超出在线窗口的设备数（与 online_agents 相加为全量 ACTIVE 台数）。 */
  offline_agents: number;
  active_agents: number;
  active_ai_sessions: number;
  /** 今日累计开过的 AI 会话数（v2.5 起独立字段，不再与 active_ai_sessions 混用）。 */
  today_ai_sessions: number;
  today_online_seconds: number;
  today_active_seconds: number;
  today_input_tokens: number;
  today_output_tokens: number;
  today_messages: number;
  today_tool_calls: number;
  today_projects: number;
  today_users: number;
  /** AI 渗透率（北极星指标，0~100，整数百分比）。-1 表示尚未接入数据源。 */
  ai_penetration_percent: number;
  /**
   * 服务端安装目录 manifest.json 的 version（运维当前分发的客户端最新版本）。
   * 未配置 install.dir 或无法读取时为 null。
   */
  latest_agent_version: string | null;
}

/** AI 渗透率（北极星）· 可选时间窗查询结果。 */
export interface AiPenetration {
  /** 0~100 整数百分比；-1 = 无数据。 */
  percent: number;
  /** 回显口径：today / 7d / 30d。 */
  window: string;
}

export interface TokenTrendPoint {
  /** ISO yyyy-MM-dd */
  date: string;
  input_tokens: number;
  output_tokens: number;
}

/** 全公司 Token 走势:逐日一点,升序,缺失日已补零(近 N 天) */
export interface TokenTrend {
  points: TokenTrendPoint[];
}

/** 大屏洞察审计进度 · 与队列剩余同频（默认 10s） */
export interface DashboardInsightAuditFast {
  stable_audited_session_count: number;
  pending_audit_count: number;
}

/** 大屏洞察审计进度 · 低频（默认 60s）：总量与未审计口径 */
export interface DashboardInsightAuditSlow {
  total_valid_session_count: number;
  unaudited_session_count: number;
}

export interface OnlineAgent {
  agent_id: string;
  user_code: string;
  /** "姓名|工号"展示串；前端 Dashboard 在线表合并的"员工"列直接渲染这个 */
  user_display: string;
  hostname: string | null;
  os_type: string | null;
  agent_version: string | null;
  local_ip: string | null;
  git_user_name: string | null;
  git_user_email: string | null;
  cursor_email: string | null;
  cursor_membership_type: string | null;
  cursor_subscription_status: string | null;
  cursor_signup_type: string | null;
  last_seen_time: string | null;
  duration_seconds: number;
  active_seconds: number;
  project_name: string | null;
  repo_url: string | null;
  branch_name: string | null;
  /** 当前在跑的 AI 工具（cursor / claude / codex / hermes / openclaw / openharness）；null = 未运行 AI */
  target_type: string | null;
  current_status: string | null;
  current_tool: string | null;
  current_model: string | null;
  active: boolean;
  since_seen: number;
  /**
   * ai_session.last_activity 距今的秒数。> 60s 时服务端已把 current_status 强制降级为 'idle'
   * 并清空 current_tool；前端可读这个字段给状态点加灰色 stale 提示。
   * 无 ai_session（target_type=null）时为 -1。
   */
  stale_since_seconds: number;
  /** 心跳在在线窗口内为 true；离线段为 false。老后端未返回时视为在线。 */
  device_online?: boolean;
  /** 离线秒数；在线为 0。 */
  offline_seconds?: number;
}

/**
 * 员工详情窗口期环比（嵌入 PeopleDetail.wow）。
 * 本期 = RangePicker [from, min(to,today)]；上周 = 上一 ISO 自然周（周一 ~ 周日）。
 */
export interface WeekOverWeek {
  this_week_from: string;
  this_week_to: string;
  last_week_from: string;
  last_week_to: string;
  active_seconds_union: WowMetric;
  tokens: WowMetric;
  sessions: WowMetric;
  messages: WowMetric;
  retries: WowMetric;
  ai_commits: WowMetric;
  git_commits: WowMetric;
}

export interface WowMetric {
  this_week: number;
  last_week: number;
  /** 上周为 0 时为 null（无可比较基线，前端显示 —） */
  change_pct: number | null;
}

export interface TopItem {
  key: string;
  /** UI 渲染用文本：员工=姓名|工号，项目=项目名；老接口未返回时回退到 key */
  display_label?: string;
  session_count: number;
  /** 主消息数（按 event_time 切片求和），保留用于历史接口/排序参考；UI 默认走下面 user/assistant */
  message_count: number;
  token_count: number;
  extra_count: number;
  /**
   * 与 AI 会话列表 "X/Y" 同口径——窗内有活动的会话求 SUM(user_messages)/SUM(assistant_messages)。
   * 后端 v2.7 起返回；老后端可能没有这两个字段，前端要做 nullable fallback。
   */
  user_message_count?: number;
  assistant_message_count?: number;
  /** 今日窗内会话级累计 in/out（last_activity 切片），与 AI 会话 token 粒度一致 */
  input_token_count?: number;
  output_token_count?: number;
}

/**
 * 会话附带 Insight 评判摘要（与后端 AiSessionAuditSummaryDto / ai_session_audit 对齐）。
 */
export interface AiSessionAuditSummary {
  difficulty: number;
  outcome: string;
  mode: string;
  audited_at: string;
  audit_version: string;
}

/** 会话审计完整字段（{@code GET /ai-sessions/:id/audit}，弹框详情） */
export interface AiSessionAuditDetail {
  id: number;
  ai_session_id: number;
  user_code: string;
  target_type: string;
  audit_version: string;
  judge_a_model: string | null;
  judge_b_model: string | null;
  difficulty: number;
  difficulty_a: number;
  difficulty_b: number;
  outcome: string;
  mode: string;
  cap_problem_decomposition: number;
  cap_context_management: number;
  cap_debugging_skill: number;
  cap_tool_orchestration: number;
  cap_self_correction: number;
  judge_disagreement: number;
  judge_reason_text: string | null;
  message_count_at_audit: number;
  audited_at: string;
}

export interface AiSession {
  id: number;
  target_type: string;
  external_session_id: string;
  agent_id: string;
  user_code: string;
  /** "姓名|工号"展示串 */
  user_display: string;
  host_hash: string;
  cwd: string | null;
  git_branch: string | null;
  repo_url: string | null;
  project_name: string | null;
  worktree: boolean;
  main_repo: string | null;
  model: string | null;
  status: string;
  current_tool: string | null;
  started_at: string;
  last_activity: string;
  ended_at: string | null;
  user_messages: number;
  assistant_messages: number;
  total_messages: number;
  /** DB 已入库消息总行数（详情页回填进度）。 */
  stored_message_count?: number | null;
  /** DB 已入库 user 消息数（与对话 Tab 对齐）。 */
  stored_user_messages?: number | null;
  /** DB 已入库 assistant 消息数。 */
  stored_assistant_messages?: number | null;
  /** DB 对话视图条数：user + assistant + subagent。 */
  stored_conversation_count?: number | null;
  /** Agent 最近一次快照 recent_messages 条数。 */
  reported_snapshot_messages?: number | null;
  input_tokens: number;
  output_tokens: number;
  cache_create_tokens: number;
  cache_read_tokens: number;
  /**
   * 窗内对话数：详情页按 message 表 user+assistant+subagent 计数；列表页仍可能走 event 聚合。
   * 仅当请求带了 from/to 时回填，否则为 null。
   */
  window_message_count: number | null;
  /** 窗内 token 总量（input + output）。null 时退化展示 input_tokens / output_tokens */
  window_tokens: number | null;
  /**
   * 无效会话原因（v2.11）。null = 有效会话；非空时表示模型从未真正回应过这个会话。
   * - "local_command_only"  Claude Code 等 CLI 把 /usage、/exit 等本地斜杠命令上报上来，
   *                          但模型从未被调用
   * - "no_assistant_reply"  其它来源的同类无效会话兜底原因
   * 前端在 Sessions 列表默认隐藏；SessionDetail 顶部展示 banner 提示。
   */
  invalid_reason: string | null;
  /** 有 ai_session_audit 缓存时回填；否则为 null / 省略 */
  audit?: AiSessionAuditSummary | null;
  /**
   * 列表页：时间窗内 user 消息 slash_hits 合并去重后的斜杠枚举（与单条消息的 slash_hits_json 同源）。
   * command / skill / noise 见 ingest 侧分类。
   */
  slash_invocations?: { token: string; kind: string }[] | null;
}

/** 会话消息结构化内容段，与后端 ContentPartDto 对齐 */
export type ContentPartType =
  | 'text'
  | 'thinking'
  | 'tool_call'
  | 'tool_result'
  | 'image'
  | 'file_ref'
  | 'file_snippet'
  | 'system_context';

export interface ContentPart {
  type: ContentPartType | string;
  text?: string | null;
  mime?: string | null;
  path?: string | null;
  old_path?: string | null;
  language?: string | null;
  start_line?: number | null;
  end_line?: number | null;
  tool_name?: string | null;
  arguments_json?: string | null;
  blob_id?: number | null;
  width?: number | null;
  height?: number | null;
  truncated?: boolean | null;
  truncate_reason?: string | null;
  sort_order?: number | null;
}

export interface AiSessionMessage {
  id: number;
  ai_session_id: number;
  external_message_id: string | null;
  role: string;
  sequence_no: number;
  content_text: string | null;
  content_parts?: ContentPart[] | null;
  content_kind?: string | null;
  has_binary?: boolean;
  parts_count?: number;
  ingest_version?: number;
  tool_name: string | null;
  input_tokens: number;
  output_tokens: number;
  message_time: string;
  /** ingest 时按 user 正文逐行解析的斜杠命令次数 */
  slash_command_count?: number;
  /** ingest 时解析的斜杠技能次数 */
  slash_skill_count?: number;
  /** JSON 数组 [{token,kind}] */
  slash_hits_json?: string | null;
}

export interface AiSessionEvent {
  id: number;
  ai_session_id: number;
  user_code: string;
  /** "姓名|工号"展示串 */
  user_display: string;
  target_type: string;
  event_type: string;
  status: string | null;
  tool_name: string | null;
  tokens_delta: number;
  messages_delta: number;
  event_time: string;
}

export interface ToolStat {
  tool_name: string;
  count: number;
  user_count: number;
  session_count: number;
}

export interface AgentAlert {
  id: number;
  agent_id: string | null;
  user_code: string | null;
  /** "姓名|工号"展示串 */
  user_display: string | null;
  host_hash: string | null;
  alert_type: string;
  alert_level: string;
  message: string | null;
  event_time: string;
}

// ===== v3.0 分析报告 =====
// 见 docs/design/employee-insight-from-ai-sessions-v1.0.md
//
// 报告 API 全部走 /api/v1/admin/analysis/**；前端只有"管理员"页面能访问，
// 通过现有的 session cookie / X-Admin-Token 鉴权链路。

export type AnalysisReportStatus = 'pending' | 'running' | 'completed' | 'failed';

/** 报告生成进度（轮询） */
export interface AnalysisReportProgress {
  id: number;
  status: AnalysisReportStatus;
  audited_count: number;
  total_count: number;
  progress_percent: number; // 0-100
  error_text: string | null;
}

/** 历史报告侧栏一行 */
export interface AnalysisReportListItem {
  id: number;
  window_from: string;
  window_to: string;
  status: AnalysisReportStatus;
  audited_count: number;
  total_count: number;
  active_user_count: number | null;
  total_session_count: number | null;
  created_time: string;
  completed_time: string | null;
}

/** 难度 1-5 → 会话数（json 字段，后端 @JsonRawValue 透传） */
export type DifficultyDist = Record<'1' | '2' | '3' | '4' | '5', number>;

/** 协作模式 → 占比（0-1） */
export type ModeDist = Partial<
  Record<'leverage' | 'learning' | 'dependent' | 'exploratory' | 'debugging', number>
>;

/** 团队能力分位（五维） */
export type TeamCapabilityPercentiles = Partial<
  Record<
    | 'problem_decomposition'
    | 'context_management'
    | 'debugging_skill'
    | 'tool_orchestration'
    | 'self_correction',
    PercentileBundle
  >
>;

export interface TeamPercentiles {
  session_count?: PercentileBundle;
  ai_active_hours?: PercentileBundle;
  ai_commits_per_active_hour?: PercentileBundle;
}

export interface PercentileBundle {
  p10?: number | null;
  p25?: number | null;
  p50?: number | null;
  p75?: number | null;
  p90?: number | null;
}

/** Watchlist 汇总：flag → 触发的 user_code 列表 */
export type WatchlistSummary = Record<string, string[]>;

export interface ToolBreakdownItem {
  name: string;
  count: number;
  kind: 'command' | 'skill';
}

export interface TeamToolBreakdown {
  items: ToolBreakdownItem[];
  command_total: number;
  skill_total: number;
}

export interface HighlightSessionCard {
  session_id: number;
  difficulty: number;
  mode: string;
  reason: string;
  nearby_commit: boolean;
}

export type CompositeGrade = 'S' | 'A' | 'B' | 'C' | 'D';

export interface CompositeBreakdownDimension {
  key: 'cap' | 'output' | 'quality' | 'challenge' | 'independence';
  label: string;
  weight: number;
  /** 0-1 归一化子分 */
  score: number;
}

/** v2 得分构成（composite_breakdown_json 透传） */
export interface CompositeBreakdown {
  formula_version: string;
  dimensions: CompositeBreakdownDimension[];
  raw: number;
  shrink_weight: number;
  team_mean: number | null;
  final: number;
}

/** LLM 个人评语 */
export interface UserNarrative {
  level_summary: string;
  evidence: string;
  strengths: string;
  weaknesses: string;
  suggestions: string;
}

/** LLM 团队总评 */
export interface TeamNarrative {
  overview: string;
  highlights: string;
  risks: string;
  recommendations: string;
}

export interface AnalysisReportUser {
  user_code: string;
  user_display: string;

  session_count: number;
  ai_active_hours: number;
  total_tokens: number;
  ai_commit_count: number;
  ai_lines_added: number;

  difficulty_dist: DifficultyDist | null;
  avg_difficulty: number | null;
  high_difficulty_ratio: number | null;
  completion_rate?: number | null;
  abandoned_rate?: number | null;

  cap_problem_decomposition: number | null;
  cap_context_management: number | null;
  cap_debugging_skill: number | null;
  cap_tool_orchestration: number | null;
  cap_self_correction: number | null;

  mode_dist: ModeDist | null;

  ai_commits_per_active_hour: number | null;
  ai_lines_per_1k_token: number | null;
  commit_revert_rate: number | null;
  high_difficulty_commit_ratio: number | null;

  composite_score: number | null;
  composite_percentile: number | null;
  composite_bucket: 'top25' | 'mid50' | 'bottom25' | null;
  /** v2 等级；旧报告为 null（回退 bucket 展示） */
  composite_grade?: CompositeGrade | null;
  composite_confidence?: 'normal' | 'low' | null;
  composite_breakdown?: CompositeBreakdown | null;

  watchlist_flags: string[] | null;
  highlight_session_ids: number[] | null;
  highlight_sessions?: HighlightSessionCard[] | null;
  insufficient_data: boolean;

  tool_command_count?: number;
  tool_skill_count?: number;
  tool_breakdown?: ToolBreakdownItem[] | null;
  top_models?: NameValuePair[] | null;
  top_projects?: NameValuePair[] | null;
  agent_dist?: NameValuePair[] | null;
  narrative?: UserNarrative | null;
  retry_count?: number | null;
  retry_per_active_hour?: number | null;
  tool_call_count?: number | null;
}

export interface AnalysisReportDetail {
  id: number;
  window_from: string;
  window_to: string;
  status: AnalysisReportStatus;
  audited_count: number;
  total_count: number;
  progress_percent: number;
  error_text: string | null;
  judge_disagreement_ratio: number | null;
  created_time: string;
  completed_time: string | null;

  active_user_count: number | null;
  total_session_count: number | null;
  total_active_hours: number | null;
  total_ai_commit: number | null;

  team_difficulty_dist: DifficultyDist | null;
  team_mode_dist: Partial<Record<string, number>> | null;
  team_percentiles: TeamPercentiles | null;
  team_capability_percentiles?: TeamCapabilityPercentiles | null;
  watchlist_summary: WatchlistSummary | null;

  team_tool_breakdown?: TeamToolBreakdown | null;

  team_grade_dist?: Partial<Record<CompositeGrade, number>> | null;
  team_narrative?: TeamNarrative | null;

  users: AnalysisReportUser[];
}

// ===== v2.1 Phase 2 类型 =====

export interface PeopleSummary {
  user_code: string;
  /** "姓名|工号"展示串 */
  user_display: string;
  days_with_data: number;
  /** 窗口内已过的自然日数（日均协作分母） */
  window_elapsed_days: number;
  ai_active_seconds_total: number;
  ai_active_seconds_avg: number;
  ai_active_seconds_union_avg: number;
  total_input_tokens: number;
  total_output_tokens: number;
  total_tokens: number;
  ai_message_count_total: number;
  /**
   * 窗口内 role=user 的消息条数 —— 画像「提问次数」同源；依赖上报 message 样本。
   */
  user_message_count: number;
  /** 窗口内 role=assistant 的消息条数 */
  assistant_message_count: number;
  /** 问答比 assistant/user；无用户消息时为 null */
  qa_ratio: number | null;
  ai_session_count_total: number;
  tool_call_count_total: number;
  retry_count_total: number;
  ai_commit_count_total: number;
  /** 时间窗 git_commit 行数（与项目透视同源）；区别于 ai_commit_count_total（日报摘要口径） */
  git_commit_window_count: number;
  first_response_avg_ms: number;
  top_model: string | null;
  /** 最近一份 completed 报告的 v2 等级；未评估为 null */
  composite_grade?: CompositeGrade | null;
  composite_score?: number | null;
  composite_confidence?: 'normal' | 'low' | null;
  grade_window?: string | null;
}

export interface PeopleDailyPoint {
  date: string;
  ai_session_count: number;
  ai_active_seconds: number;
  ai_active_seconds_union: number;
  ai_message_count: number;
  tool_call_count: number;
  retry_count: number;
  input_tokens: number;
  output_tokens: number;
  first_response_avg_ms: number;
  thinking_seconds: number;
  top_model: string | null;
}

export interface NameValuePair {
  name: string;
  value: number;
}

export interface PeopleDetail {
  summary: PeopleSummary;
  daily_timeline: PeopleDailyPoint[];
  top_models: NameValuePair[];
  top_tools: NameValuePair[];
  top_projects: NameValuePair[];
  /** 本周 vs 上周环比；后端无人有数据时仍会返回，所有指标的 last/this 都为 0 + change_pct=null */
  wow: WeekOverWeek;
}

export interface ProjectSummary {
  project_name: string;
  repo_url: string | null;
  session_count: number;
  /** 事件流 MESSAGE_DELTA 窗内合计（参考）；展示列走 user/assistant */
  message_count: number;
  /** 发送：user 侧消息（会话级累计，last_activity 切片） */
  user_message_count: number;
  /** 接收：assistant 侧消息 */
  assistant_message_count: number;
  /** Token in / out（会话 input_tokens / output_tokens，last_activity 切片） */
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
  user_count: number;
  /** 时间窗内 git_commit 条数（与仓库 URL 对齐） */
  git_commit_count: number;
  last_activity: string | null;
  top_model: string | null;
}

export interface ProjectDailyPoint {
  date: string;
  session_count: number;
  total_tokens: number;
  user_count: number;
}

export interface ProjectContributor {
  user_code: string;
  /** "姓名|工号"展示串 */
  user_display: string;
  session_count: number;
  total_tokens: number;
  message_count: number;
  user_message_count: number;
  assistant_message_count: number;
  input_tokens: number;
  output_tokens: number;
}

export interface ProjectDetail {
  summary: ProjectSummary;
  contributor_matrix: ProjectContributor[];
  daily_timeline: ProjectDailyPoint[];
  top_slash_commands: NameValuePair[];
  top_models: NameValuePair[];
}

/** GET /projects/:name/git-commits：逐文件 numstat（新上报才有） */
export interface GitCommitPathStat {
  path: string;
  lines_added: number;
  lines_deleted: number;
}

/** GET /projects/:name/git-commits 弹框列表行 */
export interface ProjectGitCommit {
  /** 仓库远程 URL（员工跨仓弹框时有值） */
  repo_url?: string | null;
  commit_hash: string;
  commit_time: string;
  user_code: string;
  user_display: string;
  author_name: string | null;
  author_email: string | null;
  message_subject: string | null;
  branch_name: string | null;
  files_changed: number;
  lines_added: number;
  lines_deleted: number;
  /** snake_case JSON：git numstat 逐路径明细 */
  path_stats?: GitCommitPathStat[] | null;
  /** none / partial / full / skipped */
  detail_status?: string | null;
}

export interface GitCommitPatchResponse {
  path: string;
  patch: string;
  patch_truncated: boolean;
  truncate_reason?: string | null;
  binary: boolean;
}

export interface ModelDistribution {
  model: string;
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
  percent: number;
  session_count: number;
  user_count: number;
}

export interface ModelHeatmap {
  models: string[];
  days: string[];
  cells: { day_index: number; model_index: number; tokens: number }[];
}

// ===== 能力使用分析（/admin/capability）=====
// 与后端 CapabilityController + web/dto/Capability*Dto 对齐；只读 capability_daily 预聚合表。

export type CapabilityKind = 'skill' | 'mcp' | 'plugin_ns';

/** 排行二级明细行：kind=mcp 为 tool，kind=plugin_ns 为命名空间下技能。 */
export interface CapabilityRankingChild {
  sub_item: string;
  invoke_count: number;
  session_count: number;
}

/** 排行一级行：skill 名 / MCP server / 插件 namespace。 */
export interface CapabilityRankingRow {
  item: string;
  invoke_count: number;
  /** kind=skill 时为显式 /技能 调用次数；其它 kind 为 null */
  explicit_count: number | null;
  /** kind=skill 时为 NL 隐式识别次数（启发式，可能有噪音）；其它 kind 为 null */
  nl_count: number | null;
  user_count: number;
  session_count: number;
  /** kind=mcp / plugin_ns 的二级明细（已按调用量降序）；kind=skill 为 null */
  children: CapabilityRankingChild[] | null;
}

/** 趋势点（date 升序）；kind=skill 时 explicit/nl 分档，invoke = 显式 + NL。 */
export interface CapabilityTrendPoint {
  date: string;
  invoke_count: number;
  explicit_count: number | null;
  nl_count: number | null;
}

export interface CapabilityMatrixUser {
  user_code: string;
  display_name: string;
  total_count: number;
}

/** 人×一级维度覆盖矩阵：users/items 均按总量降序；cells = [userIndex, itemIndex, invokeCount]。 */
export interface CapabilityMatrix {
  users: CapabilityMatrixUser[];
  items: string[];
  cells: [number, number, number][];
}

/** 按人下钻行：sub_item 空串=一级汇总行、非空=二级明细行；按调用量降序。 */
export interface CapabilityUserItem {
  kind: string;
  item: string;
  sub_item: string;
  invoke_count: number;
  session_count: number;
}

// ===== 产出归因分析（/admin/attribution）=====
// 与后端 AttributionController + web/dto/Attribution*Dto 对齐；只读 git_commit_attribution 预计算表。
// 口径：B 档 = commit message 命中 AI trailer（误报≈0）；A 档 = 同人同仓库 AI 会话窗口 ±30min（上限口径）；
// B/A 互斥（命中 B 不再计 A）；单一归属（每 commit 至多归属一个会话）；merge commit 全口径排除。

export type AttributionTier = 'B' | 'A' | 'NONE';

/** 透视行维度（必选）；列维度额外允许 'none' = 仅行维度。 */
export type AttributionRowDim = 'user' | 'project' | 'tool' | 'model';
export type AttributionColDim = AttributionRowDim | 'none';

/** 趋势点（date 升序）：B/A/NONE 三档 commit 数与净行数分列；backfilled = 上线前历史回溯所得。 */
export interface AttributionTrendPoint {
  date: string;
  b_commits: number;
  a_commits: number;
  none_commits: number;
  b_lines: number;
  a_lines: number;
  none_lines: number;
  backfilled: boolean;
}

/** 透视单元：按 (row_key, col_key, tier) 展平；col=none 时 col_key 恒为 "*"。 */
export interface AttributionPivotCell {
  /** 维度值为空串 = 该 commit 未归因（project/tool/model 缺失），前端渲染「未归因」。 */
  row_key: string;
  col_key: string;
  tier: AttributionTier;
  commit_count: number;
  lines_added: number;
  lines_deleted: number;
}

export interface AttributionPivot {
  cells: AttributionPivotCell[];
  /** 行或列含"人"维度时携带：user_code → 显示名。 */
  user_names: Record<string, string> | null;
}

/** commit 明细抽屉行；session_id 非空可跳 /sessions/:id 人工核查。 */
export interface AttributionCommitRow {
  commit_id: number;
  commit_hash: string;
  subject: string;
  user_code: string;
  project_name: string | null;
  repo_url: string;
  commit_time: string;
  lines_added: number;
  lines_deleted: number;
  tier: string;
  trailer_kind: string | null;
  session_id: number | null;
  target_type: string | null;
  model: string | null;
  overlap_seconds: number | null;
  backfilled: boolean;
}

/** 渗透率迁移对照（-1 = 无数据）。 */
export interface PenetrationCheck {
  window: string;
  legacy_percent: number;
  attribution_percent: number;
  deviation_pp: number;
}

// 安装客户端弹框：与 InstallController.StatusDto / Platform 对齐
export interface InstallPlatform {
  os: 'darwin' | 'linux' | 'windows';
  arch: 'arm64' | 'amd64';
  filename: string;
}

export interface InstallStatus {
  configured: boolean;
  dir: string | null;
  base_url_path: string;
  platforms: InstallPlatform[];
  missing_files: string[];
}
