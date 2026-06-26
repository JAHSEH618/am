import { useEffect, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { Card, Col, DatePicker, Modal, Row, Space, Spin, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  GitlabOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import isoWeek from 'dayjs/plugin/isoWeek';
import ReactECharts from 'echarts-for-react';
import { fetchPeople, fetchPersonDetail, fetchPersonGitCommits, fetchPersonSlashCommands } from '../api/client';
import type { NameValuePair, PeopleDetail, PeopleSummary, ProjectGitCommit, WowMetric } from '../api/types';
import {
  buildGitCommitTableColumns,
  GIT_COMMIT_MODAL_TABLE_SCROLL_Y,
  gitCommitModalPagination,
} from '../components/gitCommitTableColumns';
import { categoryAxisGridLeft } from '../utils/chartAxis';
import { formatDuration, formatTokens, formatTokensM } from '../utils/format';
import { ink, semantic, indigo } from '../styles/tokens';

dayjs.extend(isoWeek);

/**
 * 员工数据页（v2.1 Phase 2）
 *
 * 左侧员工列表（按 total_tokens 降序）；
 * 右侧选中员工的详情面板：KPI + 时间线 + Top 模型 / 工具 / 项目。
 *
 * gz
 */
const { Text, Link } = Typography;
const { RangePicker } = DatePicker;

function defaultRange(): [Dayjs, Dayjs] {
  // 默认窗口：本自然周（周一 ~ 周日），与分析报告口径一致。
  const monday = dayjs().isoWeekday(1).startOf('day');
  const sunday = monday.add(6, 'day');
  return [monday, sunday];
}

function parseRangeFromSearch(from: string | null, to: string | null): [Dayjs, Dayjs] | null {
  if (!from || !to) return null;
  const f = dayjs(from, 'YYYY-MM-DD', true);
  const t = dayjs(to, 'YYYY-MM-DD', true);
  if (!f.isValid() || !t.isValid()) return null;
  return [f.startOf('day'), t.startOf('day')];
}

export default function People() {
  const { userCode: pathUserCode } = useParams<{ userCode?: string }>();
  const [searchParams] = useSearchParams();
  const deepLinkUser = searchParams.get('user') || pathUserCode || null;
  const deepLinkRange = useMemo(
    () => parseRangeFromSearch(searchParams.get('from'), searchParams.get('to')),
    [searchParams],
  );

  const [range, setRange] = useState<[Dayjs, Dayjs]>(() => deepLinkRange ?? defaultRange());
  const [list, setList] = useState<PeopleSummary[]>([]);
  const [loadingList, setLoadingList] = useState(false);

  const [selectedUser, setSelectedUser] = useState<string | null>(deepLinkUser);
  const [detail, setDetail] = useState<PeopleDetail | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);

  const [gitModalOpen, setGitModalOpen] = useState(false);
  const [gitModalUserCode, setGitModalUserCode] = useState<string | null>(null);
  const [gitModalDisplay, setGitModalDisplay] = useState<string | null>(null);
  const [gitCommits, setGitCommits] = useState<ProjectGitCommit[]>([]);
  const [gitCommitsLoading, setGitCommitsLoading] = useState(false);

  const [slashModalOpen, setSlashModalOpen] = useState(false);
  const [slashModalUserCode, setSlashModalUserCode] = useState<string | null>(null);
  const [slashModalDisplay, setSlashModalDisplay] = useState<string | null>(null);
  const [slashCommands, setSlashCommands] = useState<NameValuePair[]>([]);
  const [slashCommandsLoading, setSlashCommandsLoading] = useState(false);

  const params = useMemo(() => ({
    from: range[0].format('YYYY-MM-DD'),
    to: range[1].format('YYYY-MM-DD'),
  }), [range]);

  // 分析报告等页面深链：/people/:userCode?from=&to= 或 /people?user=&from=&to=
  useEffect(() => {
    if (deepLinkUser) setSelectedUser(deepLinkUser);
    if (deepLinkRange) setRange(deepLinkRange);
  }, [deepLinkUser, deepLinkRange]);

  useEffect(() => {
    let alive = true;
    setLoadingList(true);
    fetchPeople(params)
      .then((rows) => {
        if (!alive) return;
        setList(rows);
        if (selectedUser && !rows.some((r) => r.user_code === selectedUser)) {
          setSelectedUser(null);
        }
      })
      .finally(() => alive && setLoadingList(false));
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  useEffect(() => {
    if (!selectedUser) { setDetail(null); return; }
    let alive = true;
    setLoadingDetail(true);
    fetchPersonDetail(selectedUser, params)
      .then((d) => alive && setDetail(d))
      .finally(() => alive && setLoadingDetail(false));
    return () => { alive = false; };
  }, [selectedUser, params]);

  useEffect(() => {
    if (!gitModalOpen || !gitModalUserCode) return;
    let alive = true;
    setGitCommitsLoading(true);
    fetchPersonGitCommits(gitModalUserCode, { ...params, limit: 500 })
      .then((rows) => { if (alive) setGitCommits(rows); })
      .catch(() => { if (alive) setGitCommits([]); })
      .finally(() => { if (alive) setGitCommitsLoading(false); });
    return () => { alive = false; };
  }, [gitModalOpen, gitModalUserCode, params]);

  const openPersonGitModal = (userCode: string, display: string | null | undefined) => {
    setGitModalUserCode(userCode);
    setGitModalDisplay(display ?? null);
    setGitModalOpen(true);
  };

  useEffect(() => {
    if (!slashModalOpen || !slashModalUserCode) return;
    let alive = true;
    setSlashCommandsLoading(true);
    fetchPersonSlashCommands(slashModalUserCode, { ...params, limit: 100 })
      .then((rows) => { if (alive) setSlashCommands(rows); })
      .catch(() => { if (alive) setSlashCommands([]); })
      .finally(() => { if (alive) setSlashCommandsLoading(false); });
    return () => { alive = false; };
  }, [slashModalOpen, slashModalUserCode, params]);

  const openPersonSlashModal = (userCode: string, display: string | null | undefined) => {
    setSlashModalUserCode(userCode);
    setSlashModalDisplay(display ?? null);
    setSlashModalOpen(true);
  };

  const gitCommitColumns = useMemo(
    () => buildGitCommitTableColumns({ includeRepo: true }),
    [],
  );

  const columns: ColumnsType<PeopleSummary> = [
    {
      title: '员工',
      dataIndex: 'user_display',
      key: 'user_display',
      fixed: 'left',
      width: 180,
      ellipsis: true,
      render: (v: string, row) => <Space><UserOutlined />{v || row.user_code}</Space>,
    },
    {
      title: (
        <Tooltip title="窗口内协作并集累计 ÷ 窗口内已过的自然日数（不含未到日期）；无汇总行视为 0。">
          <span>日均 AI 协作</span>
        </Tooltip>
      ),
      dataIndex: 'ai_active_seconds_union_avg',
      key: 'ai_active_seconds_union_avg',
      sorter: (a, b) => a.ai_active_seconds_union_avg - b.ai_active_seconds_union_avg,
      render: (v: number) => formatDuration(v),
    },
    {
      title: (
        <Tooltip title="窗口期内 ai_session_message 中 role=user 的条数；依赖客户端上报的 message 样本。">
          <span>提问次数</span>
        </Tooltip>
      ),
      dataIndex: 'user_message_count',
      key: 'user_message_count',
      width: 104,
      sorter: (a, b) => a.user_message_count - b.user_message_count,
    },
    {
      title: (
        <Tooltip title="窗口期内助手回复条数 ÷ 用户发言条数；依赖客户端上报的 message 样本，含工具轮次时比值可能偏高。">
          <span>问答比</span>
        </Tooltip>
      ),
      dataIndex: 'qa_ratio',
      key: 'qa_ratio',
      width: 100,
      sorter: (a, b) => {
        const av = a.qa_ratio ?? -1;
        const bv = b.qa_ratio ?? -1;
        return av - bv;
      },
      render: (_: number | null, row) => {
        if (row.qa_ratio == null) return <Text type="secondary">-</Text>;
        return (
          <Tooltip title={`用户 ${row.user_message_count} · 助手 ${row.assistant_message_count}`}>
            <span>{row.qa_ratio.toFixed(2)}</span>
          </Tooltip>
        );
      },
    },
    {
      title: '会话数',
      dataIndex: 'ai_session_count_total',
      key: 'ai_session_count_total',
      sorter: (a, b) => a.ai_session_count_total - b.ai_session_count_total,
    },
    {
      title: (
        <Tooltip title="时间窗内 git_commit 提交条数（与项目透视 Git 同源）；点击打开明细表。">
          <span>Git 提交</span>
        </Tooltip>
      ),
      dataIndex: 'git_commit_window_count',
      key: 'git_commit_window_count',
      width: 96,
      sorter: (a, b) => a.git_commit_window_count - b.git_commit_window_count,
      render: (v: number, row) => (
        <Link
          onClick={(e) => {
            e.stopPropagation();
            openPersonGitModal(row.user_code, row.user_display);
          }}
        >
          {v}
        </Link>
      ),
    },
    {
      title: '输入 Token',
      dataIndex: 'total_input_tokens',
      key: 'total_input_tokens',
      sorter: (a, b) => a.total_input_tokens - b.total_input_tokens,
      render: (v: number) => formatTokens(v),
    },
    {
      title: '输出 Token',
      dataIndex: 'total_output_tokens',
      key: 'total_output_tokens',
      sorter: (a, b) => a.total_output_tokens - b.total_output_tokens,
      render: (v: number) => formatTokens(v),
    },
    {
      title: 'Token 总量',
      dataIndex: 'total_tokens',
      key: 'total_tokens',
      sorter: (a, b) => a.total_tokens - b.total_tokens,
      render: (v: number) => formatTokens(v),
    },
    {
      title: (
        <Tooltip title="窗口内用户主动斜杠调用次数（command + skill）；点击打开各命令明细。">
          <span>Slash Commands</span>
        </Tooltip>
      ),
      dataIndex: 'tool_call_count_total',
      key: 'tool_call_count_total',
      width: 120,
      sorter: (a, b) => a.tool_call_count_total - b.tool_call_count_total,
      render: (v: number, row) => (
        v > 0 ? (
          <Link
            onClick={(e) => {
              e.stopPropagation();
              openPersonSlashModal(row.user_code, row.user_display);
            }}
          >
            {v}
          </Link>
        ) : (
          <Text type="secondary">0</Text>
        )
      ),
    },
    {
      title: '卡壳',
      dataIndex: 'retry_count_total',
      key: 'retry_count_total',
      render: (v: number) => v > 0 ? <Tag color="volcano">{v}</Tag> : <Text type="secondary">0</Text>,
    },
    {
      title: '首响均值',
      dataIndex: 'first_response_avg_ms',
      key: 'first_response_avg_ms',
      render: (v: number) => v > 0 ? `${(v / 1000).toFixed(1)} s` : '-',
    },
    {
      title: 'Top 模型',
      dataIndex: 'top_model',
      key: 'top_model',
      render: (v: string | null) => v ? <Tag>{v}</Tag> : <Text type="secondary">-</Text>,
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small">
        <Space wrap>
          <Text type="secondary">时间窗：</Text>
          <RangePicker
            value={range}
            onChange={(v) => v && v[0] && v[1] && setRange([v[0], v[1]])}
            allowClear={false}
            disabledDate={(d) => d.isAfter(dayjs(), 'day')}
          />
        </Space>
      </Card>

      <Card size="small" title={`员工列表（${list.length} 人）`}>
        <Spin spinning={loadingList}>
          <Table<PeopleSummary>
            rowKey="user_code"
            size="middle"
            columns={columns}
            dataSource={list}
            pagination={{ pageSize: 20, showSizeChanger: false }}
            scroll={{ x: 1180 }}
            onRow={(row) => ({
              onClick: () => setSelectedUser(row.user_code),
              style: {
                cursor: 'pointer',
                background: row.user_code === selectedUser ? 'var(--am-brand-bg)' : undefined,
              },
            })}
          />
        </Spin>
      </Card>

      {!selectedUser && list.length > 0 && !loadingList && (
        <Card size="small">
          <Text type="secondary">点击上方表格中的员工查看详情面板</Text>
        </Card>
      )}

      {selectedUser && (
        <PersonDetailPanel
          detail={detail}
          loading={loadingDetail}
          userCode={selectedUser}
          onGitCommitsClick={() => {
            const row = list.find((r) => r.user_code === selectedUser);
            openPersonGitModal(selectedUser, row?.user_display);
          }}
        />
      )}

      <Modal
        title={
          <Space direction="vertical" size={0}>
            <span>Git 提交明细 — {gitModalDisplay || gitModalUserCode}</span>
            {gitModalUserCode && gitModalDisplay && gitModalDisplay !== gitModalUserCode && (
              <Text type="secondary" style={{ fontSize: 13, fontWeight: 'normal' }}>
                {gitModalUserCode}
              </Text>
            )}
          </Space>
        }
        open={gitModalOpen}
        onCancel={() => setGitModalOpen(false)}
        footer={null}
        width={1080}
        destroyOnClose
      >
        <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
          时间窗：{params.from} ~ {params.to}（最多拉取 500 条，按提交时间倒序；可跨多个仓库）
        </Text>
        <Spin spinning={gitCommitsLoading}>
          <Table<ProjectGitCommit>
            rowKey={(r) => `${r.repo_url ?? ''}:${r.commit_hash}:${r.commit_time}`}
            size="small"
            columns={gitCommitColumns}
            dataSource={gitCommits}
            pagination={gitCommitModalPagination}
            scroll={{ x: 1040, y: GIT_COMMIT_MODAL_TABLE_SCROLL_Y }}
          />
        </Spin>
      </Modal>

      <Modal
        title={
          <Space direction="vertical" size={0}>
            <span>Slash Commands 明细 — {slashModalDisplay || slashModalUserCode}</span>
            {slashModalUserCode && slashModalDisplay && slashModalDisplay !== slashModalUserCode && (
              <Text type="secondary" style={{ fontSize: 13, fontWeight: 'normal' }}>
                {slashModalUserCode}
              </Text>
            )}
          </Space>
        }
        open={slashModalOpen}
        onCancel={() => setSlashModalOpen(false)}
        footer={null}
        width={560}
        destroyOnClose
      >
        <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
          时间窗：{params.from} ~ {params.to}（按出现次数降序，最多 100 条）
        </Text>
        <Spin spinning={slashCommandsLoading}>
          <Table<NameValuePair>
            rowKey="name"
            size="small"
            dataSource={slashCommands}
            pagination={{ pageSize: 15, showSizeChanger: false, hideOnSinglePage: true }}
            locale={{ emptyText: '暂无斜杠调用记录' }}
            columns={[
              { title: '命令', dataIndex: 'name', ellipsis: true },
              { title: '次数', dataIndex: 'value', width: 88, align: 'right' as const },
            ]}
          />
        </Spin>
      </Modal>
    </Space>
  );
}

function PersonDetailPanel({
  detail,
  loading,
  userCode,
  onGitCommitsClick,
}: {
  detail: PeopleDetail | null;
  loading: boolean;
  userCode: string;
  onGitCommitsClick: () => void;
}) {
  if (loading || !detail) {
    return (
      <Card size="small" title={`员工详情 — ${userCode}`}>
        <Spin spinning={loading}><div style={{ height: 240 }} /></Spin>
      </Card>
    );
  }

  const s = detail.summary;
  const display = s.user_display || s.user_code || userCode;

  const timelineOption = useMemo(() => {
    const dates = detail.daily_timeline.map((p) => p.date);
    const activeHours = detail.daily_timeline.map((p) => +(p.ai_active_seconds_union / 3600).toFixed(2));
    const sessions = detail.daily_timeline.map((p) => p.ai_session_count);
    const retries = detail.daily_timeline.map((p) => p.retry_count);
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['AI 协作（h，并集）', '会话数', '卡壳次数'], bottom: 0 },
      grid: { left: 40, right: 40, top: 20, bottom: 40 },
      xAxis: { type: 'category', data: dates, boundaryGap: false },
      yAxis: [
        { type: 'value', name: 'h', position: 'left' },
        { type: 'value', name: '次', position: 'right' },
      ],
      series: [
        { name: 'AI 协作（h，并集）', type: 'line', smooth: true, data: activeHours, areaStyle: { opacity: 0.15 }, color: indigo[600] },
        { name: '会话数', type: 'bar', yAxisIndex: 1, data: sessions, color: semantic.success.base, barWidth: 12 },
        { name: '卡壳次数', type: 'bar', yAxisIndex: 1, data: retries, color: semantic.warning.base, barWidth: 12 },
      ],
    };
  }, [detail]);

  const topBarOption = (
    title: string,
    items: { name: string; value: number }[],
    opts?: { valueInM?: boolean; emptyText?: string; fullLabels?: boolean },
  ) => {
    const fmt = opts?.valueInM
      ? (v: number) => formatTokensM(v)
      : (v: number) => String(v);
    if (items.length === 0) {
      return {
        title: { text: title, left: 0, top: 0, textStyle: { fontSize: 13, fontWeight: 600 } },
        graphic: {
          type: 'text',
          left: 'center',
          top: 'middle',
          style: { text: opts?.emptyText ?? '暂无数据', fill: ink[3], fontSize: 13 },
        },
      };
    }
    const names = items.map((i) => i.name);
    const gridLeft = opts?.fullLabels ? categoryAxisGridLeft(names, { min: 110 }) : 110;
    const axisLabel = opts?.fullLabels
      ? { fontSize: 12 }
      : { fontSize: 12, width: 96, overflow: 'truncate' as const };
    return {
      title: { text: title, left: 0, top: 0, textStyle: { fontSize: 13, fontWeight: 600 } },
      grid: { left: gridLeft, right: 48, top: 30, bottom: 10 },
      tooltip: {
        trigger: 'item',
        formatter: (p: { name?: string; value?: number }) =>
          `${p.name ?? ''}: ${fmt(Number(p.value ?? 0))}`,
      },
      xAxis: { type: 'value', show: false },
      yAxis: { type: 'category', data: [...names].reverse(), axisLabel },
      series: [{
        type: 'bar',
        data: items.map((i) => i.value).reverse(),
        barWidth: 12,
        itemStyle: { color: indigo[600], borderRadius: 4 },
        label: { show: true, position: 'right', formatter: (p: { value: number }) => fmt(Number(p.value)) },
      }],
    };
  };

  return (
    <Card size="small" title={
      <Space>
        <UserOutlined />
        <span>员工详情 — {display}</span>
      </Space>
    }>
      <PersonDetailMetrics
        summary={s}
        wow={detail.wow}
        tokenDetail={`输入 ${formatTokens(s.total_input_tokens)} · 输出 ${formatTokens(s.total_output_tokens)}`}
        onGitCommitsClick={onGitCommitsClick}
      />

      <div style={{ marginTop: 24, height: 280 }}>
        <ReactECharts option={timelineOption} style={{ height: '100%' }} notMerge lazyUpdate />
      </div>

      <Row gutter={16} style={{ marginTop: 24 }}>
        <Col xs={24} md={8}>
          <div style={{ height: 240 }}>
            <ReactECharts
              option={topBarOption('Top 模型（按 token）', detail.top_models, { valueInM: true })}
              style={{ height: '100%' }}
              notMerge
              lazyUpdate
            />
          </div>
        </Col>
        <Col xs={24} md={8}>
          <div style={{ height: 240 }}>
            <ReactECharts
              option={topBarOption('Top Slash Commands', detail.top_tools, {
                emptyText: '暂无斜杠调用数据',
                fullLabels: true,
              })}
              style={{ height: '100%' }}
              notMerge
              lazyUpdate
            />
          </div>
        </Col>
        <Col xs={24} md={8}>
          <div style={{ height: 240 }}>
            <ReactECharts
              option={topBarOption('Top 项目（按 token）', detail.top_projects, { valueInM: true })}
              style={{ height: '100%' }}
              notMerge
              lazyUpdate
            />
          </div>
        </Col>
      </Row>
    </Card>
  );
}

const metricsPanelStyle: React.CSSProperties = {
  marginBottom: 20,
  border: '1px solid var(--am-border)',
  borderRadius: 12,
  overflow: 'hidden',
  background: 'var(--am-bg-card)',
};

const metricsCellDivider: React.CSSProperties = {
  borderRight: '1px solid var(--am-border-subtle)',
};

function PersonDetailMetrics({
  summary: s,
  wow,
  tokenDetail,
  onGitCommitsClick,
}: {
  summary: PeopleDetail['summary'];
  wow: PeopleDetail['wow'];
  tokenDetail: string;
  onGitCommitsClick: () => void;
}) {
  const periodLabel = `${wow.this_week_from} ~ ${wow.this_week_to}`;
  const lastWeekShort = `${wow.last_week_from.slice(5)} ~ ${wow.last_week_to.slice(5)}`;
  const gitMetric = wow.git_commits ?? { this_week: 0, last_week: 0, change_pct: null };

  const primary: {
    key: string;
    label: string;
    icon: React.ReactNode;
    value: string;
    sub?: string;
    metric: WowMetric;
    formatLast: (n: number) => string;
    inverted?: boolean;
    onClick?: () => void;
  }[] = [
    {
      key: 'active',
      label: '窗口期协作（并集）',
      icon: <ClockCircleOutlined />,
      value: formatDuration(wow.active_seconds_union.this_week) || '0s',
      metric: wow.active_seconds_union,
      formatLast: (n) => formatDuration(n) || '0s',
    },
    {
      key: 'tokens',
      label: '窗口期 Token',
      icon: <ThunderboltOutlined />,
      value: formatTokens(wow.tokens.this_week),
      sub: tokenDetail,
      metric: wow.tokens,
      formatLast: (n) => formatTokens(n),
    },
    {
      key: 'retries',
      label: '窗口期卡壳',
      icon: <ExclamationCircleOutlined />,
      value: `${wow.retries.this_week}`,
      metric: wow.retries,
      formatLast: (n) => `${n}`,
      inverted: true,
    },
    {
      key: 'git',
      label: 'Git 提交',
      icon: <GitlabOutlined />,
      value: `${gitMetric.this_week}`,
      sub: '点击查看明细',
      metric: gitMetric,
      formatLast: (n) => `${n}`,
      onClick: onGitCommitsClick,
    },
  ];

  const secondary: { key: string; label: string; value: string; hint?: string; tooltip?: string }[] = [
    {
      key: 'avg',
      label: '日均协作',
      value: formatDuration(s.ai_active_seconds_union_avg),
      hint: s.window_elapsed_days > 0 ? `÷ 已过 ${s.window_elapsed_days} 天` : undefined,
      tooltip: s.window_elapsed_days > 0 ? `协作并集 ÷ 已过自然日` : undefined,
    },
    { key: 'sessions', label: '会话数', value: `${s.ai_session_count_total}`, hint: '窗口合计' },
    {
      key: 'questions',
      label: '提问次数',
      value: `${s.user_message_count}`,
      tooltip: '窗口期内 role=user 消息条数',
    },
    {
      key: 'qa',
      label: '问答比',
      value: s.qa_ratio == null ? '—' : s.qa_ratio.toFixed(2),
      hint: `用户 ${s.user_message_count} · 助手 ${s.assistant_message_count}`,
    },
    {
      key: 'fr',
      label: '首响均值',
      value: s.first_response_avg_ms > 0 ? `${(s.first_response_avg_ms / 1000).toFixed(1)} s` : '—',
    },
  ];

  return (
    <div style={metricsPanelStyle}>
      <div
        style={{
          padding: '10px 16px',
          borderBottom: '1px solid var(--am-border-subtle)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          background: 'var(--am-surface-sunken)',
          flexWrap: 'wrap',
          gap: 4,
        }}
      >
        <Text style={{ fontSize: 12, color: 'var(--am-ink-3)' }}>窗口期 {periodLabel}</Text>
        <Text style={{ fontSize: 11, color: 'var(--am-ink-3)' }}>环比 · 上周 {lastWeekShort}</Text>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))' }}>
        {primary.map((item, i) => {
          const { key: itemKey, ...rest } = item;
          return <PrimaryMetricCell key={itemKey} {...rest} showDivider={i < primary.length - 1} />;
        })}
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(5, minmax(0, 1fr))',
          borderTop: '1px solid var(--am-border-subtle)',
          background: 'var(--am-surface-sunken)',
        }}
      >
        {secondary.map((item, i) => {
          const { key: itemKey, ...rest } = item;
          return <SecondaryMetricCell key={itemKey} {...rest} showDivider={i < secondary.length - 1} />;
        })}
      </div>
    </div>
  );
}

function PrimaryMetricCell({
  label,
  icon,
  value,
  sub,
  metric,
  formatLast,
  inverted,
  onClick,
  showDivider,
}: {
  label: string;
  icon: React.ReactNode;
  value: string;
  sub?: string;
  metric: WowMetric;
  formatLast: (n: number) => string;
  inverted?: boolean;
  onClick?: () => void;
  showDivider: boolean;
}) {
  const pct = metric.change_pct;
  let trendColor = 'var(--am-ink-3)';
  if (pct != null) {
    trendColor = (inverted ? pct < 0 : pct >= 0) ? 'var(--am-success)' : 'var(--am-warning)';
  }
  const arrow = pct == null ? null : pct >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />;

  return (
    <div
      style={{
        padding: '14px 16px',
        minHeight: 112,
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        cursor: onClick ? 'pointer' : undefined,
        ...(showDivider ? metricsCellDivider : {}),
      }}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onClick={onClick}
      onKeyDown={
        onClick
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onClick();
              }
            }
          : undefined
      }
    >
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--am-ink-3)' }}>
          <span style={{ color: 'var(--am-brand)', fontSize: 13 }}>{icon}</span>
          <span>{label}</span>
        </div>
        <div
          style={{
            marginTop: 6,
            fontSize: 26,
            fontWeight: 680,
            letterSpacing: '-0.02em',
            lineHeight: 1.12,
            fontVariantNumeric: 'tabular-nums',
            color: onClick ? 'var(--am-brand)' : 'var(--am-ink)',
          }}
        >
          {value}
        </div>
        <div style={{ marginTop: 4, minHeight: 16, fontSize: 11, color: 'var(--am-ink-3)' }}>{sub ?? '\u00a0'}</div>
      </div>
      <div style={{ marginTop: 10, fontSize: 11, color: 'var(--am-ink-3)', lineHeight: 1.45 }}>
        上周 {formatLast(metric.last_week)}
        {' · '}
        {pct == null ? (
          <span style={{ color: 'var(--am-ink-5)' }}>—</span>
        ) : (
          <span style={{ color: trendColor, fontWeight: 500 }}>
            {arrow} {Math.abs(pct)}%
          </span>
        )}
      </div>
    </div>
  );
}

function SecondaryMetricCell({
  label,
  value,
  hint,
  tooltip,
  showDivider,
}: {
  label: string;
  value: string;
  hint?: string;
  tooltip?: string;
  showDivider: boolean;
}) {
  const body = (
    <div style={{ padding: '12px 16px', ...(showDivider ? metricsCellDivider : {}) }}>
      <div style={{ fontSize: 11, color: 'var(--am-ink-3)' }}>{label}</div>
      <div style={{ marginTop: 4, fontSize: 17, fontWeight: 600, fontVariantNumeric: 'tabular-nums', color: 'var(--am-ink)' }}>
        {value}
      </div>
      <div style={{ marginTop: 2, minHeight: 14, fontSize: 11, color: 'var(--am-ink-5)' }}>{hint ?? '\u00a0'}</div>
    </div>
  );
  return tooltip ? <Tooltip title={tooltip}>{body}</Tooltip> : body;
}
