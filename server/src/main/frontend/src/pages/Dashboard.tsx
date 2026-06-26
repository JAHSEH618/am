import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Card, Col, Input, Pagination, Row, Segmented, Select, Space, Spin, Table, Tag, Progress, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ApiOutlined,
  ClockCircleOutlined,
  AimOutlined,
  RocketOutlined,
  SearchOutlined,
  FundProjectionScreenOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  fetchAiPenetration,
  fetchInsightAuditFast,
  fetchInsightAuditSlow,
  fetchOnline,
  fetchOverview,
  fetchTopEmployees,
  fetchTopProjects,
} from '../api/client';
import type {
  AiSession,
  DashboardInsightAuditFast,
  DashboardInsightAuditSlow,
  DashboardOverview,
  OnlineAgent,
  TopItem,
} from '../api/types';
import { useSse } from '../hooks/useSse';
import {
  formatDuration,
  formatTimeFromNow,
  statusLabel,
  formatTokens,
  isToolStatus,
  membershipLabel,
  membershipColor,
  signupTypeLabel,
  targetTypeLabel,
  targetTypeColor,
  STALE_VISUAL_THRESHOLD_SECONDS,
} from '../utils/format';
import StatusDot from '../components/StatusDot';

// v2.7.1：HTTP 兜底 polling 间隔。SSE 实时 patch 已经覆盖大部分高频更新（status/tool/model/project），
// polling 主要负责拉今日累计指标（today_messages / today_tokens / top 列表）和"上次没开页时漏掉的"
// 在线表全量。10s 间隔在 SSE 健康时几乎不产生用户可感的延迟，SSE 断连时也能保证大盘 ≤ 10s 刷新。
const REFRESH_MS = 10_000;

/** 洞察审计：已审计 / 队列剩余，与 overview 解耦，默认 10s */
const INSIGHT_AUDIT_FAST_MS = 10_000;
/** 洞察审计：未审计总数 / 有效会话总量，默认 60s */
const INSIGHT_AUDIT_SLOW_MS = 60_000;

const { Text, Title, Paragraph } = Typography;
// 在线员工表分页：按 agent_device 维度切（每页多台机器，用户可选 50 / 100 / 200）。
// 不直接用 ant Table 内置 pagination，因为 OnlineAgentTable 用 rowSpan 合并"按机器不变"的列，
// row 维度切会把同一 agent_device 的多行切到不同页，导致第 2 页员工名 / IP 等列空白。
const AGENT_PAGE_SIZE_OPTIONS = [50, 100, 200] as const;

export default function Dashboard() {
  const navigate = useNavigate();
  const [overview, setOverview] = useState<DashboardOverview | null>(null);
  const [online, setOnline] = useState<OnlineAgent[]>([]);
  const [topProjects, setTopProjects] = useState<TopItem[]>([]);
  const [topEmployees, setTopEmployees] = useState<TopItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [insightAuditFast, setInsightAuditFast] = useState<DashboardInsightAuditFast | null>(null);
  const [insightAuditSlow, setInsightAuditSlow] = useState<DashboardInsightAuditSlow | null>(null);
  // AI 渗透率（北极星）可选窗口：今日 / 7天 / 30天。首屏沿用 overview 的 30 天值，切换时单独拉。
  const [penWindow, setPenWindow] = useState<'today' | '7d' | '30d'>('30d');
  const [penPct, setPenPct] = useState<number | undefined>(undefined);
  const [agentPage, setAgentPage] = useState(1);
  const [agentPageSize, setAgentPageSize] = useState(50);
  /** 注册员工表筛选 */
  const [agentFilterStatus, setAgentFilterStatus] = useState<'all' | 'online' | 'offline'>('all');
  const [agentFilterKeyword, setAgentFilterKeyword] = useState('');

  useEffect(() => {
    let alive = true;
    const tick = async () => {
      if (document.hidden) return;
      try {
        const [ov, on, tp, te] = await Promise.all([
          fetchOverview(),
          fetchOnline(),
          fetchTopProjects(8),
          fetchTopEmployees(8),
        ]);
        if (!alive) return;
        setOverview(ov);
        setOnline(on);
        setTopProjects(tp);
        setTopEmployees(te);
      } finally {
        if (alive) setLoading(false);
      }
    };
    tick();
    const id = window.setInterval(tick, REFRESH_MS);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, []);

  useEffect(() => {
    let alive = true;
    const pull = async () => {
      if (document.hidden) return;
      try {
        const data = await fetchInsightAuditFast();
        if (alive) setInsightAuditFast(data);
      } catch {
        if (alive) setInsightAuditFast(null);
      }
    };
    pull();
    const id = window.setInterval(pull, INSIGHT_AUDIT_FAST_MS);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, []);

  useEffect(() => {
    let alive = true;
    const pull = async () => {
      if (document.hidden) return;
      try {
        const data = await fetchInsightAuditSlow();
        if (alive) setInsightAuditSlow(data);
      } catch {
        if (alive) setInsightAuditSlow(null);
      }
    };
    pull();
    const id = window.setInterval(pull, INSIGHT_AUDIT_SLOW_MS);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, []);

  // AI 渗透率：窗口切换时单独拉该口径的值（首屏由 overview 提供 30 天值，避免闪烁）。
  useEffect(() => {
    let alive = true;
    fetchAiPenetration(penWindow)
      .then((d) => { if (alive) setPenPct(d.percent); })
      .catch(() => { /* 失败保留上一次/overview 的值 */ });
    return () => { alive = false; };
  }, [penWindow]);

  // 服务端 ingest 完成后推 session_changed / session_event。本地先 patch 在线表，
  // 再防抖拉 overview + online，保证 hero「活跃 AI 会话」、运行中 Agent 列与后端真值一致
  // （含 target_type 从 null 展开为多行等结构变化）。
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const scheduleDashboardRefresh = useCallback(() => {
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
    refreshTimerRef.current = setTimeout(async () => {
      try {
        const [ov, on] = await Promise.all([fetchOverview(), fetchOnline()]);
        setOverview(ov);
        setOnline(on);
      } catch {
        // 忽略；下一轮 10s polling 会兜底
      }
    }, 400);
  }, []);

  const onSessionChanged = useCallback((data: string) => {
    try {
      const s: AiSession = JSON.parse(data);
      setOnline((prev) =>
        prev.map((row) => {
          if (row.device_online === false) return row;
          if (row.agent_id !== s.agent_id) return row;
          if (row.target_type != null && row.target_type !== s.target_type) return row;
          return {
            ...row,
            target_type: row.target_type ?? s.target_type,
            current_status: s.status,
            current_tool: s.current_tool,
            current_model: s.model,
            project_name: s.project_name || row.project_name,
            branch_name: s.git_branch || row.branch_name,
            stale_since_seconds: 0,
            active: s.status !== 'idle' && s.status != null,
          };
        }),
      );
      scheduleDashboardRefresh();
    } catch {
      // 解析失败忽略；下一次 polling 会兜底纠偏
    }
  }, [scheduleDashboardRefresh]);

  const onSessionEvent = useCallback(() => {
    scheduleDashboardRefresh();
  }, [scheduleDashboardRefresh]);

  const sseHandlers = useMemo(
    () => ({ session_changed: onSessionChanged, session_event: onSessionEvent }),
    [onSessionChanged, onSessionEvent],
  );

  useSse('/api/v1/dashboard/stream', sseHandlers);

  const filteredAgentsList = useMemo(() => {
    const q = agentFilterKeyword.trim().toLowerCase();
    return online.filter((row) => {
      if (agentFilterStatus === 'online' && row.device_online === false) return false;
      if (agentFilterStatus === 'offline' && row.device_online !== false) return false;
      if (!q) return true;
      const disp = (row.user_display || '').toLowerCase();
      const code = (row.user_code || '').toLowerCase();
      const ip = (row.local_ip || '').toLowerCase();
      return disp.includes(q) || code.includes(q) || ip.includes(q);
    });
  }, [online, agentFilterStatus, agentFilterKeyword]);

  useEffect(() => {
    setAgentPage(1);
  }, [agentFilterStatus, agentFilterKeyword]);

  // ---- 在线员工分页 -------------------------------------------------------
  //
  // online 是按 (agent_device, target_type) 展开的多行结构；分页必须按 agent_device 维度，
  // 否则 rowSpan 跨页时第 2 页的"员工/IP/Git"会因为非首行 rowSpan=0 而空白。
  const { pagedOnline, totalAgents } = useMemo(() => {
    const ids: string[] = [];
    const seen = new Set<string>();
    for (const r of filteredAgentsList) {
      if (!seen.has(r.agent_id)) {
        seen.add(r.agent_id);
        ids.push(r.agent_id);
      }
    }
    const start = (agentPage - 1) * agentPageSize;
    const pageIds = new Set(ids.slice(start, start + agentPageSize));
    return {
      pagedOnline: filteredAgentsList.filter((r) => pageIds.has(r.agent_id)),
      totalAgents: ids.length,
    };
  }, [filteredAgentsList, agentPage, agentPageSize]);

  // 数据集变小（员工集中下线 / 重启）时把当前页拉回首页，避免停在空白页
  useEffect(() => {
    const maxPage = Math.max(1, Math.ceil(totalAgents / agentPageSize));
    if (agentPage > maxPage) setAgentPage(maxPage);
  }, [totalAgents, agentPage, agentPageSize]);

  return (
    <Spin spinning={loading && overview === null} tip="加载中...">
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {/* Hero 区：4 个核心指标，颜色锚点 + 大字号；移动端两列、桌面四列 */}
        <Row gutter={[16, 16]} className="am-dashboard-hero-row">
          <Col xs={12} md={6}>
            <HeroCard
              label="注册员工"
              value={
                <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                  <span style={{ color: 'var(--am-success-fg)' }}>{overview?.online_agents ?? 0}</span>
                  <span style={{ opacity: 0.45, fontWeight: 500 }}> / </span>
                  <span style={{ color: 'var(--am-error-fg)' }}>{overview?.offline_agents ?? 0}</span>
                </span>
              }
              suffix="台"
              subnote={`跑活 ${overview?.active_agents ?? 0} 台`}
              icon={<ApiOutlined />}
              tone="indigo"
            />
          </Col>
          <Col xs={12} md={6}>
            <HeroCard
              // active_ai_sessions 是 session 维度真值（同员工同 provider 下多个 active session 不会被合并），
              // 由后端聚合保证与 AI 会话列表"非空闲"行数严格一致；polling 周期 10s 内必同步。
              // 注意不要回退到本地 online 数组推算——它按 (agent, target_type) 去重过会少计数。
              label="活跃 AI 会话"
              value={overview?.active_ai_sessions ?? 0}
              suffix={`个正在跑 / 今日已开 ${overview?.today_ai_sessions ?? 0}`}
              icon={<RocketOutlined />}
              tone="emerald"
              ariaLabel="查看当前非空闲的 AI 会话列表"
              onClick={() => navigate('/sessions?active_only=1')}
            />
          </Col>
          <Col xs={12} md={6}>
            <HeroCard
              label="今日 Token"
              value={formatTokens(
                (overview?.today_input_tokens ?? 0) + (overview?.today_output_tokens ?? 0),
              )}
              icon={<ClockCircleOutlined />}
              tone="amber"
            />
          </Col>
          <Col xs={12} md={6}>
            {(() => {
              const penDisplay = penPct ?? (overview ? overview.ai_penetration_percent : -1);
              const winLabel = penWindow === 'today' ? '今日' : penWindow === '7d' ? '近7天' : '近30天';
              return (
                <HeroCard
                  label="AI 渗透率"
                  value={penDisplay >= 0 ? `${penDisplay}%` : '—'}
                  suffix={penDisplay >= 0 ? '北极星' : undefined}
                  subnote={
                    penDisplay >= 0
                      ? `${winLabel} · AI 协助代码占比`
                      : 'git_commit 与会话归因后生效'
                  }
                  icon={<AimOutlined />}
                  tone="rose"
                  extra={
                    <Segmented
                      size="small"
                      value={penWindow}
                      onChange={(v) => setPenWindow(v as 'today' | '7d' | '30d')}
                      options={[
                        { label: '今日', value: 'today' },
                        { label: '7天', value: '7d' },
                        { label: '30天', value: '30d' },
                      ]}
                    />
                  }
                />
              );
            })()}
          </Col>
        </Row>

        <InsightAuditProgressCard fast={insightAuditFast} slow={insightAuditSlow} />

        {/* 次级指标：紧凑 metric list（不再每项一张白卡） */}
        <Card size="small" title="今日运营指标" styles={{ body: { padding: '12px 20px' } }}>
          <Row gutter={[24, 0]}>
            <Col xs={24} md={12} lg={8}>
              <MetricRow
                label="今日在线时长"
                value={formatDuration(overview?.today_online_seconds)}
              />
              <MetricRow
                label="今日活跃时长"
                value={formatDuration(overview?.today_active_seconds)}
              />
            </Col>
            <Col xs={24} md={12} lg={8}>
              <MetricRow label="今日活跃员工" value={overview?.today_users ?? 0} />
              <MetricRow label="今日涉及项目" value={overview?.today_projects ?? 0} />
            </Col>
            <Col xs={24} md={12} lg={8}>
              <MetricRow label="今日消息数" value={overview?.today_messages ?? 0} />
              <MetricRow label="今日工具调用" value={overview?.today_tool_calls ?? 0} />
            </Col>
          </Row>
        </Card>

        {/* 注册员工表：在线 + 离线设备合并 */}
        <Card
          size="small"
          title={
            <Space>
              <span style={{ fontWeight: 600 }}>注册员工</span>
              <span style={{ color: 'var(--am-ink-3)', fontSize: 13, fontWeight: 400 }}>
                {totalAgents} 台机器
                {totalAgents > 0 &&
                  ` · 第 ${agentPage} / ${Math.max(1, Math.ceil(totalAgents / agentPageSize))} 页`}
              </span>
            </Space>
          }
          styles={{ body: { padding: 0 } }}
          // 与上方「速览 KPI 群组」拉开一档（16+8≈24px）：从这里起进入"下钻明细"区。
          style={{ marginTop: 8 }}
        >
          <div className="am-agent-filter">
            <div className="am-agent-filter-inner">
              <div className="am-agent-filter-status">
                <span className="am-agent-filter-label">在线状态</span>
                <Select<'all' | 'online' | 'offline'>
                  className="am-agent-filter-select"
                  variant="borderless"
                  popupMatchSelectWidth={false}
                  value={agentFilterStatus}
                  onChange={(v) => setAgentFilterStatus(v)}
                  options={[
                    { label: '全部', value: 'all' },
                    { label: '仅在线', value: 'online' },
                    { label: '仅离线', value: 'offline' },
                  ]}
                />
              </div>
              <span className="am-agent-filter-divider" aria-hidden />
              <Input
                className="am-agent-filter-input"
                variant="borderless"
                allowClear
                placeholder="搜索姓名、工号或 IP（模糊 · 任一命中）"
                value={agentFilterKeyword}
                onChange={(e) => setAgentFilterKeyword(e.target.value)}
                prefix={<SearchOutlined className="am-agent-filter-search-icon" />}
              />
            </div>
            <p className="am-agent-filter-hint">筛选实时生效；清空输入框即恢复全部关键词匹配。</p>
          </div>
          <OnlineAgentTable
            online={pagedOnline}
            navigate={navigate}
            latestPublishedVersion={overview?.latest_agent_version ?? null}
          />
          {totalAgents > 0 && (
            <div style={{ padding: '12px 16px', borderTop: '1px solid var(--am-surface-sunken)', textAlign: 'right' }}>
              <Pagination
                current={agentPage}
                pageSize={agentPageSize}
                total={totalAgents}
                onChange={setAgentPage}
                pageSizeOptions={AGENT_PAGE_SIZE_OPTIONS.map(String)}
                showSizeChanger
                onShowSizeChange={(_, size) => {
                  setAgentPageSize(size);
                  setAgentPage(1);
                }}
                hideOnSinglePage={false}
                showTotal={(t) => `共 ${t} 台机器`}
                size="small"
              />
            </div>
          )}
        </Card>

        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <Card size="small" title="今日 Top 项目">
              <Table<TopItem>
                dataSource={topProjects}
                rowKey="key"
                size="small"
                pagination={false}
                locale={{ emptyText: '今日暂无项目活动' }}
                columns={[
                  { title: '项目', dataIndex: 'key', ellipsis: true },
                  {
                    title: '会话',
                    dataIndex: 'session_count',
                    width: 80,
                    align: 'right',
                    // 与"项目透视"页 goSessions 的链路一致：带上 project_name；today 维度用 days=1
                    // 让 Sessions 页时间窗收紧到今天，避免默认 7 天把历史会话也圈进来。
                    render: (v: number, row) => (
                      <a
                        onClick={(e) => {
                          e.stopPropagation();
                          navigate(`/sessions?project_name=${encodeURIComponent(row.key)}&days=1`);
                        }}
                      >
                        {v}
                      </a>
                    ),
                  },
                  {
                    title: '消息',
                    width: 100,
                    align: 'right',
                    // 与 AI 会话列表「X / Y」同口径：用户 / 助手；后端老版本没下发拆分时回退总数
                    render: renderUserAssistantMessages,
                  },
                  {
                    title: 'Token',
                    key: 'tokens_io',
                    width: 140,
                    align: 'right',
                    render: renderTopItemIoTokens,
                  },
                  { title: '员工', dataIndex: 'extra_count', width: 70, align: 'right' },
                ]}
              />
            </Card>
          </Col>
          <Col xs={24} lg={12}>
            <Card size="small" title="今日 Top 员工">
              <Table<TopItem>
                dataSource={topEmployees}
                rowKey="key"
                size="small"
                pagination={false}
                locale={{ emptyText: '今日暂无员工活动' }}
                columns={[
                  {
                    title: '员工',
                    dataIndex: 'display_label',
                    // display_label = 姓名|工号；后端未返回（老接口）时退回到 key（工号）
                    render: (v: string | undefined, row) => v || row.key,
                  },
                  { title: '会话', dataIndex: 'session_count', width: 80, align: 'right' },
                  {
                    title: '消息',
                    width: 100,
                    align: 'right',
                    render: renderUserAssistantMessages,
                  },
                  {
                    title: 'Token',
                    key: 'tokens_io',
                    width: 140,
                    align: 'right',
                    render: renderTopItemIoTokens,
                  },
                  { title: '项目', dataIndex: 'extra_count', width: 70, align: 'right' },
                ]}
                onRow={(row) => ({
                  onClick: () => navigate(`/sessions?user_code=${row.key}`),
                  style: { cursor: 'pointer' },
                })}
              />
            </Card>
          </Col>
        </Row>
      </Space>
    </Spin>
  );
}

// ===== Top 项目 / Top 员工 列渲染辅助 ===================================
//
// 与 AI 会话列表同口径展示「用户条数 / 助手条数」，
// 后端 v2.7 起返回拆分；老后端字段缺失时回退为单一总数 message_count，
// 避免 UA=0 时被错误渲染成 "0/0"。
function renderUserAssistantMessages(_: unknown, row: TopItem): React.ReactNode {
  const u = row.user_message_count;
  const a = row.assistant_message_count;
  if (u != null && a != null && (u > 0 || a > 0)) {
    return <span style={{ fontVariantNumeric: 'tabular-nums' }}>{u} / {a}</span>;
  }
  return <span style={{ fontVariantNumeric: 'tabular-nums' }}>{row.message_count}</span>;
}

/**
 * in / out 来自 ai_session 累计字段（与项目透视 Token 列同源）；token_count 仍为 event 流合计用于排序。
 * 老接口未下发拆分、或会话侧无 in/out 但有 event token 时退回只显示合计。
 */
function renderTopItemIoTokens(_: unknown, row: TopItem): React.ReactNode {
  const inT = row.input_token_count;
  const outT = row.output_token_count;
  if (inT == null || outT == null) {
    return <span style={{ fontVariantNumeric: 'tabular-nums' }}>{formatTokens(row.token_count)}</span>;
  }
  if (inT === 0 && outT === 0 && row.token_count > 0) {
    return <span style={{ fontVariantNumeric: 'tabular-nums' }}>{formatTokens(row.token_count)}</span>;
  }
  return (
    <span style={{ fontVariantNumeric: 'tabular-nums' }}>
      {formatTokens(inT)} / {formatTokens(outT)}
    </span>
  );
}

function InsightAuditProgressCard(props: {
  fast: DashboardInsightAuditFast | null;
  slow: DashboardInsightAuditSlow | null;
}) {
  const { fast, slow } = props;
  const total = slow?.total_valid_session_count ?? 0;
  const stable = fast?.stable_audited_session_count ?? 0;
  const pending = fast?.pending_audit_count ?? 0;
  const unaudited = slow?.unaudited_session_count ?? 0;
  const pct = total > 0 ? Math.min(100, Math.round((stable / total) * 100)) : 0;
  const loadingFast = fast === null;
  const loadingSlow = slow === null;

  return (
    // 外层 Space(size=16) 已负责区块间距；不要再叠 marginBottom，否则此卡下方变成 32px、上方 16px 的不对称留白。
    <Card>
      {/* 结构与分析报告页「报告生成中」卡片一致：标题区 → Progress → 说明段落 */}
      <Space align="start" size={14} style={{ marginBottom: 14 }}>
        <FundProjectionScreenOutlined
          style={{ fontSize: 22, color: 'var(--am-brand)', marginTop: 2 }}
          aria-hidden
        />
        <div style={{ minWidth: 0 }}>
          <Title level={5} style={{ marginTop: 0, marginBottom: 6 }}>
            洞察审计进度
          </Title>
          <Text type="secondary" style={{ fontSize: 12 }}>
            已审计与队列每 {INSIGHT_AUDIT_FAST_MS / 1000}s 刷新 · 未审计与总量每{' '}
            {INSIGHT_AUDIT_SLOW_MS / 60_000} min 刷新
          </Text>
        </div>
      </Space>

      <Progress
        percent={total > 0 ? pct : 0}
        status={total > 0 ? 'active' : 'normal'}
        strokeWidth={10}
        strokeLinecap="round"
        showInfo={total > 0}
        // 仅在进度条尾部显示百分比；明细 stable/total 由下方说明段承载。
        // 若把「（83 / 485）」塞进 line Progress 的 format，文本宽度远超 AntD 预留的
        // .ant-progress-text 宽度，会溢出卡片右边界（见越界截图）。
        format={(p) => `${p}%`}
        aria-label="洞察审计完成比例"
      />

      <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0, fontSize: 13 }}>
        {total > 0 ? (
          <>
            进度 <Text strong>{stable}</Text> / <Text strong>{total}</Text>
            ：稳定口径已审计会话占有效会话总量；
            队列剩余{' '}
            <Text strong style={{ color: pending > 0 ? 'var(--am-warning-fg)' : undefined }}>
              {loadingFast ? '—' : pending}
            </Text>
            （高频）；未审计总会话{' '}
            <Text strong>{loadingSlow ? '—' : unaudited}</Text>
            （低频）。含义与生成分析报告时的队列计数类似：命中缓存或无需再审的不占用队列。
          </>
        ) : (
          <>
            有效会话总量为 0 时不展示完成比例。
            {!loadingFast && !loadingSlow && (
              <>
                {' '}
                当前队列剩余 <Text strong>{pending}</Text> · 低频口径未审计{' '}
                <Text strong>{unaudited}</Text>。
              </>
            )}
          </>
        )}
      </Paragraph>
    </Card>
  );
}

// ===== Hero 卡 / Metric 行 ============================================
//
// 这俩组件足够小、且只在 Dashboard 里复用，先内置在文件里；如果 Realtime / Cost
// 也要 Hero 风格，再抽到 components/HeroCard.tsx。

type Tone = 'indigo' | 'emerald' | 'amber' | 'rose';

// Hero chip 配色：浅底 + 饱和 icon；四个 KPI 各一个语义色，全部走设计 token（CSS 变量）。
const TONE_PALETTE: Record<Tone, { bg: string; fg: string }> = {
  indigo:  { bg: 'var(--am-brand-bg)',   fg: 'var(--am-brand)' },
  emerald: { bg: 'var(--am-success-bg)', fg: 'var(--am-success-fg)' },
  amber:   { bg: 'var(--am-warning-bg)', fg: 'var(--am-warning-fg)' },
  rose:    { bg: 'var(--am-rose-bg)',    fg: 'var(--am-rose-fg)' },
};

interface HeroCardProps {
  label: string;
  value: React.ReactNode;
  suffix?: string;
  subnote?: React.ReactNode;
  icon?: React.ReactNode;
  tone: Tone;
  /** 可点击：例如跳转 AI 会话「仅活跃」视图 */
  onClick?: () => void;
  /** 无障碍名称（onClick 时建议传入） */
  ariaLabel?: string;
  /** 右上角附加控件（如窗口选择器）。 */
  extra?: React.ReactNode;
}

function HeroCard({ label, value, suffix, subnote, icon, tone, onClick, ariaLabel, extra }: HeroCardProps) {
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

interface MetricRowProps {
  label: string;
  value: React.ReactNode;
}

function MetricRow({ label, value }: MetricRowProps) {
  return (
    <div className="am-metric-row">
      <span className="am-metric-label">{label}</span>
      <span className="am-metric-value">{value}</span>
    </div>
  );
}

/** 把"距上次活动多少秒"格式化成简短的人类可读串，给"陈旧"提示用 */
function formatStale(seconds: number): string {
  if (seconds < 0) return '';
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`;
  return `${Math.floor(seconds / 86400)}d`;
}

// ===== 大盘 Agent 表（在线段按 target_device × target_type 展开 + 离线段每设备一行）=====
//
// 后端在线段每行 = (agent_device, target_type)；离线段 device_online=false、offline_seconds>0。
//
// 前端按 agent_id 把"基础信息列"用 rowSpan 合并：员工 / 主机 IP / Git / Cursor /
// 在线时长 / 最近上报 / 离线时长 / 版本 等列只在该 agent_device 第一行显示，
// 其它行返回 rowSpan: 0；"运行中 Agent / 当前状态 / 模型 / 当前项目"分行展开。
function OnlineAgentTable({
  online,
  navigate,
  latestPublishedVersion,
}: {
  online: OnlineAgent[];
  navigate: (path: string) => void;
  /** 来自大盘 overview：install/manifest.json 的 version */
  latestPublishedVersion: string | null;
}) {
  // 每个 agent_id 在表里出现的总行数，以及它在排序后的首行 index
  const groupMeta = useMemo(() => {
    const counts: Record<string, number> = {};
    const firstIndex: Record<string, number> = {};
    online.forEach((row, idx) => {
      counts[row.agent_id] = (counts[row.agent_id] || 0) + 1;
      if (firstIndex[row.agent_id] === undefined) firstIndex[row.agent_id] = idx;
    });
    return { counts, firstIndex };
  }, [online]);

  // 一个 helper：让"按机器不变"的列只在该 agent_device 的第一行渲染。
  // ant Design 的 onCell 签名里 index 是 optional，这里没 index 视同非首行（rowSpan: 0）兜底。
  const mergeCell = (row: OnlineAgent, index?: number) => {
    if (index !== undefined && groupMeta.firstIndex[row.agent_id] === index) {
      return { rowSpan: groupMeta.counts[row.agent_id] };
    }
    return { rowSpan: 0 };
  };

  const columns: ColumnsType<OnlineAgent> = [
    {
      title: '员工',
      dataIndex: 'user_display',
      width: 140,
      fixed: 'left',
      ellipsis: true,
      onCell: mergeCell,
      render: (v: string, row) => {
        const label = v || row.user_code;
        const showSeal = row.device_online === false;
        return (
          <span className="am-employee-cell" title={label}>
            <span className="am-employee-name">{label}</span>
            {showSeal && (
              <span className="am-offline-seal" role="img" aria-label="离线">
                离线
              </span>
            )}
          </span>
        );
      },
    },
    {
      title: '主机 / IP',
      width: 200,
      ellipsis: true,
      onCell: mergeCell,
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <span>
            {row.hostname || '-'}
            {row.os_type ? ` (${row.os_type})` : ''}
          </span>
          <span style={{ color: 'var(--am-ink-3)', fontSize: 12, fontFamily: 'monospace' }}>
            {row.local_ip || '-'}
          </span>
        </Space>
      ),
    },
    {
      title: 'Git 账号',
      width: 200,
      ellipsis: true,
      onCell: mergeCell,
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <span>{row.git_user_name || '-'}</span>
          <span style={{ color: 'var(--am-ink-3)', fontSize: 12 }}>{row.git_user_email || ''}</span>
        </Space>
      ),
    },
    {
      title: 'Cursor 账号',
      width: 220,
      ellipsis: true,
      onCell: mergeCell,
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <Space size={6} wrap>
            <span>{row.cursor_email || '-'}</span>
            {row.cursor_membership_type && (
              <Tag color={membershipColor(row.cursor_membership_type)}>
                {membershipLabel(row.cursor_membership_type)}
              </Tag>
            )}
          </Space>
          <span style={{ color: 'var(--am-ink-3)', fontSize: 12 }}>
            {row.cursor_subscription_status === 'active'
              ? '订阅中'
              : row.cursor_subscription_status || ''}
            {row.cursor_signup_type && row.cursor_subscription_status ? ' · ' : ''}
            {signupTypeLabel(row.cursor_signup_type)}
          </span>
        </Space>
      ),
    },
    // ↓↓ 以下三列每行单独渲染（不合并），把同一员工跑的多个 AI agent 分行展开
    {
      title: '运行中 Agent',
      dataIndex: 'target_type',
      width: 160,
      // v2.8 起这一列只展示"真的在跑活的 agent"——active=false（idle / stale）一律按"无"处理。
      // 旧版的"[Cursor] 空闲"组合既显示 Cursor 又显示空闲，让用户对"这台机器到底有没有在跑"产生
      // 二义性；本意是"在线但闲着"，但与列名"运行中 Agent"语义冲突。
      // 真正的"开机但闲着"信号已经由"当前状态"列呈现（idle + stale 灰点），不需要在这里再叠加。
      render: (v: string | null, row) => {
        if (!v || !row.active) {
          return <span style={{ color: 'var(--am-ink-3)', fontSize: 12 }}>无运行中 Agent</span>;
        }
        return (
          <Tag color={targetTypeColor(v)} style={{ fontWeight: 600 }}>
            {targetTypeLabel(v)}
          </Tag>
        );
      },
    },
    {
      title: '当前项目',
      dataIndex: 'project_name',
      width: 200,
      ellipsis: true,
      render: (v, row) => (
        <Space direction="vertical" size={0}>
          <span>{v || '-'}</span>
          {row.branch_name && (
            <span style={{ color: 'var(--am-ink-3)', fontSize: 12 }}>@{row.branch_name}</span>
          )}
        </Space>
      ),
    },
    {
      title: '当前状态',
      dataIndex: 'current_status',
      width: 170,
      render: (v: string | null, row) => {
        if (!v) return <span style={{ color: 'var(--am-ink-5)' }}>—</span>;
        // staleSinceSeconds 仅做「多久未动」视觉提示；status 以 DB 为准（卡僵由 AiSessionStaleCloser 兜底）
        const stale = row.stale_since_seconds > STALE_VISUAL_THRESHOLD_SECONDS;
        return (
          <Space size={4}>
            <StatusDot status={v} stale={stale} />
            <span style={{ color: stale ? 'var(--am-ink-3)' : undefined }}>{statusLabel(v)}</span>
            {row.current_tool && isToolStatus(v) && !stale && (
              <Tag color="blue">{row.current_tool}</Tag>
            )}
            {stale && (
              <span style={{ color: 'var(--am-ink-3)', fontSize: 12 }}>
                （{formatStale(row.stale_since_seconds)}未动）
              </span>
            )}
          </Space>
        );
      },
    },
    {
      title: '模型',
      dataIndex: 'current_model',
      width: 180,
      ellipsis: true,
      render: (v: string | null) => v || <span style={{ color: 'var(--am-ink-5)' }}>—</span>,
    },
    // ↓↓ 以下又是按机器合并
    {
      title: '在线 / 活跃',
      width: 160,
      onCell: mergeCell,
      render: (_, row) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatDuration(row.duration_seconds)} / {formatDuration(row.active_seconds)}
        </span>
      ),
    },
    {
      title: '最近上报',
      dataIndex: 'last_seen_time',
      width: 120,
      onCell: mergeCell,
      render: (v: string | null) =>
        v ? (
          formatTimeFromNow(v)
        ) : (
          <span style={{ color: 'var(--am-ink-3)', fontSize: 12 }}>从未上报</span>
        ),
    },
    {
      title: '离线时长',
      width: 110,
      align: 'right',
      onCell: mergeCell,
      render: (_, row) =>
        row.device_online === false ? (
          <span style={{ fontVariantNumeric: 'tabular-nums' }}>
            {formatDuration(row.offline_seconds ?? 0)}
          </span>
        ) : (
          <span style={{ color: 'var(--am-ink-5)' }}>—</span>
        ),
    },
    {
      title: '客户端安装的版本',
      dataIndex: 'agent_version',
      width: 130,
      onCell: mergeCell,
      render: (v: string | null) => {
        const installed = v?.trim() || null;
        const latest = latestPublishedVersion?.trim() || null;
        const mismatch = Boolean(installed && latest && installed !== latest);
        if (!installed) return <span style={{ color: 'var(--am-ink-5)' }}>—</span>;
        return mismatch ? (
          <Tag color="orange" style={{ marginInlineEnd: 0 }}>
            {installed}
          </Tag>
        ) : (
          <span>{installed}</span>
        );
      },
    },
    {
      title: '最新版本',
      width: 110,
      onCell: mergeCell,
      render: () => {
        const latest = latestPublishedVersion?.trim() || null;
        return latest ? (
          <span style={{ fontVariantNumeric: 'tabular-nums' }}>{latest}</span>
        ) : (
          <span style={{ color: 'var(--am-ink-5)' }}>—</span>
        );
      },
    },
  ];

  return (
    <Table<OnlineAgent>
      dataSource={online}
      // 同一 agent_device 不同 target_type 拼合成唯一 rowKey，
      // 让 ant Table 在 rowSpan 模式下也能稳定 diff，不出"row key duplicated"警告。
      rowKey={(row) =>
        `${row.agent_id}|${row.target_type ?? '__none__'}|${row.device_online === false ? '0' : '1'}`
      }
      size="small"
      pagination={false}
      locale={{ emptyText: '暂无 Agent 设备（无 ACTIVE 台账或列表为空）' }}
      scroll={{ x: 2260 }}
      className="am-sticky-table am-online-table"
      columns={columns}
      onRow={(row) => ({
        onClick: () => navigate(`/sessions?user_code=${row.user_code}`),
        style: { cursor: 'pointer' },
      })}
    />
  );
}
