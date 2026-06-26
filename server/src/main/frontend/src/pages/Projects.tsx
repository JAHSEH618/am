import { useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, DatePicker, Modal, Row, Space, Spin, Statistic, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { InfoCircleOutlined, ProjectOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import isoWeek from 'dayjs/plugin/isoWeek';
import ReactECharts from 'echarts-for-react';
import { useNavigate } from 'react-router-dom';
import { fetchProjectDetail, fetchProjects, fetchProjectGitCommits } from '../api/client';
import type { NameValuePair, ProjectContributor, ProjectDetail, ProjectGitCommit, ProjectSummary } from '../api/types';
import {
  buildGitCommitTableColumns,
  GIT_COMMIT_MODAL_TABLE_SCROLL_Y,
  gitCommitModalPagination,
} from '../components/gitCommitTableColumns';
import { employeeName, formatTime, formatTokens } from '../utils/format';
import { indigo, semantic } from '../styles/tokens';

/**
 * 项目透视页（v2.1 Phase 2）
 *
 * 数据来自 ai_session.project_name；Git 提交数为时间窗内 git_commit 按 repo_url 聚合。
 *
 * gz
 */
const { Text } = Typography;
const { RangePicker } = DatePicker;

dayjs.extend(isoWeek);

/** 项目详情三栏矩阵：等高卡片 + 表体纵向滚动 */
const MATRIX_PANEL_BODY_HEIGHT = 320;
const MATRIX_TABLE_SCROLL_Y = 272;
const matrixPanelBodyStyle: React.CSSProperties = {
  height: MATRIX_PANEL_BODY_HEIGHT,
  overflow: 'hidden',
  padding: '8px 12px',
};

function defaultRange(): [Dayjs, Dayjs] {
  const monday = dayjs().isoWeekday(1).startOf('day');
  const sunday = monday.add(6, 'day');
  return [monday, sunday];
}

export default function Projects() {
  const navigate = useNavigate();
  const [range, setRange] = useState<[Dayjs, Dayjs]>(defaultRange);
  const [list, setList] = useState<ProjectSummary[]>([]);
  const [loadingList, setLoadingList] = useState(false);

  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<ProjectDetail | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);

  const [gitModalOpen, setGitModalOpen] = useState(false);
  const [gitModalProject, setGitModalProject] = useState<string | null>(null);
  const [gitModalRepo, setGitModalRepo] = useState<string | null>(null);
  const [gitCommits, setGitCommits] = useState<ProjectGitCommit[]>([]);
  const [gitCommitsLoading, setGitCommitsLoading] = useState(false);

  const params = useMemo(() => ({
    from: range[0].format('YYYY-MM-DD'),
    to: range[1].format('YYYY-MM-DD'),
  }), [range]);

  /**
   * 跳转到 Sessions 列表 + project_name 过滤。
   * days 参数不带：让 Sessions 用项目过滤路径，project + range 都不传 user/days，后端自动落到 since 分支。
   */
  const goSessions = (projectName: string, userCode?: string, e?: React.MouseEvent) => {
    e?.stopPropagation();
    const sp = new URLSearchParams();
    sp.set('project_name', projectName);
    if (userCode) sp.set('user_code', userCode);
    // 让 Sessions 页用一个稍宽的窗口（涵盖项目透视当前 range），避免默认 7 天遮蔽更早的会话
    const days = Math.max(7, range[1].diff(range[0], 'day') + 1);
    sp.set('days', String(days));
    navigate(`/sessions?${sp.toString()}`);
  };

  const openGitCommitsModal = (projectName: string, repoUrl: string | null | undefined) => {
    setGitModalProject(projectName);
    setGitModalRepo(repoUrl ?? null);
    setGitModalOpen(true);
  };

  useEffect(() => {
    if (!gitModalOpen || !gitModalProject) return;
    let alive = true;
    setGitCommitsLoading(true);
    fetchProjectGitCommits(gitModalProject, { ...params, limit: 500 })
      .then((rows) => { if (alive) setGitCommits(rows); })
      .catch(() => { if (alive) setGitCommits([]); })
      .finally(() => { if (alive) setGitCommitsLoading(false); });
    return () => { alive = false; };
  }, [gitModalOpen, gitModalProject, params]);

  const gitCommitColumns: ColumnsType<ProjectGitCommit> = useMemo(
    () => buildGitCommitTableColumns({ includeRepo: false }),
    [],
  );

  useEffect(() => {
    let alive = true;
    setLoadingList(true);
    fetchProjects(params)
      .then((rows) => {
        if (!alive) return;
        setList(rows);
        if (selected && !rows.some((r) => r.project_name === selected)) {
          setSelected(null);
        }
      })
      .finally(() => alive && setLoadingList(false));
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  useEffect(() => {
    if (!selected) { setDetail(null); return; }
    let alive = true;
    setLoadingDetail(true);
    fetchProjectDetail(selected, params)
      .then((d) => alive && setDetail(d))
      .catch(() => alive && setDetail(null))
      .finally(() => alive && setLoadingDetail(false));
    return () => { alive = false; };
  }, [selected, params]);

  const columns: ColumnsType<ProjectSummary> = [
    {
      title: '项目',
      dataIndex: 'project_name',
      key: 'project_name',
      width: 240,
      ellipsis: true,
      render: (v: string) => <Space><ProjectOutlined />{v}</Space>,
    },
    {
      title: '会话数',
      dataIndex: 'session_count',
      key: 'session_count',
      width: 110,
      sorter: (a, b) => a.session_count - b.session_count,
      render: (v: number, row) => (
        <Button
          type="link"
          size="small"
          style={{ padding: 0, height: 'auto' }}
          onClick={(e) => goSessions(row.project_name, undefined, e)}
        >
          {v}
        </Button>
      ),
    },
    {
      title: (
        <Space size={4}>
          消息
          <Tooltip title="时间窗内用户消息（发送）/ 助手消息（接收）">
            <InfoCircleOutlined style={{ color: 'var(--am-ink-3)' }} />
          </Tooltip>
        </Space>
      ),
      key: 'messages_pair',
      width: 130,
      sorter: (a, b) =>
        (a.user_message_count + a.assistant_message_count) -
        (b.user_message_count + b.assistant_message_count),
      render: (_: unknown, row: ProjectSummary) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {row.user_message_count} / {row.assistant_message_count}
        </span>
      ),
    },
    {
      title: (
        <Space size={4}>
          Token
          <Tooltip title="时间窗内 input / output token">
            <InfoCircleOutlined style={{ color: 'var(--am-ink-3)' }} />
          </Tooltip>
        </Space>
      ),
      key: 'tokens_pair',
      width: 170,
      sorter: (a, b) =>
        a.input_tokens + a.output_tokens - (b.input_tokens + b.output_tokens),
      render: (_: unknown, row: ProjectSummary) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatTokens(row.input_tokens)} / {formatTokens(row.output_tokens)}
        </span>
      ),
    },
    {
      title: '参与员工',
      dataIndex: 'user_count',
      key: 'user_count',
      width: 110,
    },
    {
      title: 'Git 提交',
      dataIndex: 'git_commit_count',
      key: 'git_commit_count',
      width: 120,
      sorter: (a, b) => a.git_commit_count - b.git_commit_count,
      render: (v: number, row) => {
        if (v > 0 && row.repo_url) {
          return (
            <Button
              type="link"
              size="small"
              style={{ padding: 0, height: 'auto' }}
              onClick={(e) => {
                e.stopPropagation();
                openGitCommitsModal(row.project_name, row.repo_url);
              }}
            >
              {v}
            </Button>
          );
        }
        return v;
      },
    },
    {
      title: 'Top 模型',
      dataIndex: 'top_model',
      key: 'top_model',
      width: 170,
      ellipsis: true,
      render: (v: string | null) => v ? <Tag>{v}</Tag> : <Text type="secondary">-</Text>,
    },
    {
      title: '最后活跃',
      dataIndex: 'last_activity',
      key: 'last_activity',
      width: 170,
      render: (v: string | null) => formatTime(v),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small">
        <div className="am-toolbar">
          <Text type="secondary">时间窗：</Text>
          <RangePicker
            value={range}
            onChange={(v) => v && v[0] && v[1] && setRange([v[0], v[1]])}
            allowClear={false}
            disabledDate={(d) => d.isAfter(dayjs(), 'day')}
          />
        </div>
      </Card>

      <Card size="small" title={`项目列表（${list.length}）`}>
        <Table<ProjectSummary>
          rowKey="project_name"
          size="middle"
          loading={loadingList}
          columns={columns}
          dataSource={list}
          scroll={{ x: 1220 }}
          className="am-sticky-table"
          locale={{ emptyText: '当前时间窗内暂无项目活动' }}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          onRow={(row) => ({
            onClick: () => setSelected(row.project_name),
            style: {
              cursor: 'pointer',
              background: row.project_name === selected ? 'var(--am-brand-bg)' : undefined,
            },
          })}
        />
      </Card>

      {!selected && list.length > 0 && !loadingList && (
        <Card size="small">
          <Text type="secondary">点击上方表格中的项目查看详情面板</Text>
        </Card>
      )}

      {selected && (
        <ProjectDetailPanel
          detail={detail}
          loading={loadingDetail}
          projectName={selected}
          onSessionLink={(userCode) => goSessions(selected, userCode)}
          onOpenGitCommits={() => openGitCommitsModal(selected, detail?.summary.repo_url)}
        />
      )}

      <Modal
        title={
          <Space direction="vertical" size={0}>
            <span>Git 提交明细 — {gitModalProject}</span>
            {gitModalRepo && (
              <Text type="secondary" ellipsis={{ tooltip: true }} style={{ fontSize: 13, fontWeight: 'normal', maxWidth: 900 }}>
                {gitModalRepo}
              </Text>
            )}
          </Space>
        }
        open={gitModalOpen}
        onCancel={() => { setGitModalOpen(false); }}
        footer={null}
        width={1000}
        destroyOnClose
      >
        <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
          时间窗：{params.from} ~ {params.to}（最多拉取 500 条，按提交时间倒序）
        </Text>
        <Spin spinning={gitCommitsLoading}>
          <Table<ProjectGitCommit>
            rowKey={(r) => `${r.repo_url ?? ''}:${r.commit_hash}:${r.commit_time}`}
            size="small"
            columns={gitCommitColumns}
            dataSource={gitCommits}
            pagination={gitCommitModalPagination}
            scroll={{ x: 900, y: GIT_COMMIT_MODAL_TABLE_SCROLL_Y }}
          />
        </Spin>
      </Modal>
    </Space>
  );
}

function ProjectDetailPanel({ detail, loading, projectName, onSessionLink, onOpenGitCommits }: {
  detail: ProjectDetail | null;
  loading: boolean;
  projectName: string;
  onSessionLink: (userCode?: string) => void;
  onOpenGitCommits: () => void;
}) {
  if (loading || !detail) {
    return (
      <Card size="small" title={`项目详情 — ${projectName}`}>
        <Spin spinning={loading}><div style={{ height: 240 }} /></Spin>
      </Card>
    );
  }

  const s = detail.summary;

  const timelineOption = useMemo(() => {
    const dates = detail.daily_timeline.map((p) => p.date);
    const tokens = detail.daily_timeline.map((p) => p.total_tokens);
    const sessions = detail.daily_timeline.map((p) => p.session_count);
    const users = detail.daily_timeline.map((p) => p.user_count);
    return {
      tooltip: {
        trigger: 'axis',
        formatter: (items: { axisValue?: string; seriesName?: string; value?: number }[]) => {
          if (!items?.length) return '';
          const lines = [String(items[0].axisValue ?? '')];
          for (const it of items) {
            const v = Number(it.value ?? 0);
            const label =
              it.seriesName === 'Token' ? formatTokens(v) : String(v);
            lines.push(`${it.seriesName}: ${label}`);
          }
          return lines.join('<br/>');
        },
      },
      legend: { data: ['Token', '会话数', '参与员工'], bottom: 0 },
      grid: { left: 60, right: 60, top: 20, bottom: 40 },
      xAxis: { type: 'category', data: dates, boundaryGap: false },
      yAxis: [
        { type: 'value', name: 'token', position: 'left', axisLabel: { formatter: (v: number) => formatTokens(v) } },
        { type: 'value', name: '人/会话', position: 'right' },
      ],
      series: [
        { name: 'Token', type: 'line', smooth: true, data: tokens, areaStyle: { opacity: 0.15 }, color: indigo[600] },
        { name: '会话数', type: 'bar', yAxisIndex: 1, data: sessions, color: semantic.success.base, barWidth: 12 },
        { name: '参与员工', type: 'bar', yAxisIndex: 1, data: users, color: semantic.warning.base, barWidth: 12 },
      ],
    };
  }, [detail]);

  const contributorColumns: ColumnsType<ProjectContributor> = [
    {
      title: '员工',
      dataIndex: 'user_display',
      key: 'user_display',
      ellipsis: true,
      render: (v: string, row) => employeeName(v, row.user_code),
    },
    {
      title: '会话',
      dataIndex: 'session_count',
      key: 'session_count',
      render: (v: number, row) => (
        <Button
          type="link"
          size="small"
          style={{ padding: 0, height: 'auto' }}
          onClick={() => onSessionLink(row.user_code)}
        >
          {v}
        </Button>
      ),
    },
    {
      title: (
        <Space size={4}>
          消息
          <Tooltip title="时间窗内用户消息（发送）/ 助手消息（接收）">
            <InfoCircleOutlined style={{ color: 'var(--am-ink-3)' }} />
          </Tooltip>
        </Space>
      ),
      key: 'messages_pair',
      sorter: (a, b) =>
        a.user_message_count + a.assistant_message_count
        - (b.user_message_count + b.assistant_message_count),
      defaultSortOrder: 'descend',
      render: (_: unknown, row: ProjectContributor) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {row.user_message_count} / {row.assistant_message_count}
        </span>
      ),
    },
    {
      title: (
        <Space size={4}>
          Token
          <Tooltip title="时间窗内 input / output token">
            <InfoCircleOutlined style={{ color: 'var(--am-ink-3)' }} />
          </Tooltip>
        </Space>
      ),
      key: 'tokens_pair',
      sorter: (a, b) => a.input_tokens + a.output_tokens - (b.input_tokens + b.output_tokens),
      render: (_: unknown, row: ProjectContributor) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatTokens(row.input_tokens)} / {formatTokens(row.output_tokens)}
        </span>
      ),
    },
  ];

  const topCountColumns: ColumnsType<NameValuePair> = [
    { title: '命令', dataIndex: 'name', key: 'name', ellipsis: true },
    {
      title: '次数',
      dataIndex: 'value',
      key: 'value',
      sorter: (a, b) => a.value - b.value,
      defaultSortOrder: 'descend',
    },
  ];

  const topModelColumns: ColumnsType<NameValuePair> = [
    { title: '模型', dataIndex: 'name', key: 'name', ellipsis: true },
    {
      title: 'Token',
      dataIndex: 'value',
      key: 'value',
      render: (v: number) => formatTokens(v),
      sorter: (a, b) => a.value - b.value,
      defaultSortOrder: 'descend',
    },
  ];

  return (
    <Card size="small" title={
      <Space>
        <ProjectOutlined />
        <span>项目详情 — {projectName}</span>
        {s.repo_url && (
          <Text type="secondary" ellipsis={{ tooltip: true }} style={{ fontSize: 12, fontWeight: 'normal', maxWidth: 360 }}>
            {s.repo_url}
          </Text>
        )}
      </Space>
    }>
      <Row gutter={16}>
        <Col xs={12} md={6}>
          {/* "会话数"做成可点击数字，跳到 Sessions 列表的项目过滤态；与其余四项统一为 <Statistic>，
              click 行为靠 formatter 内嵌可聚焦的 link Button 保留（字号/字重 inherit 跟随 Statistic）。 */}
          <Statistic
            title="会话数"
            value={s.session_count}
            valueStyle={{ fontVariantNumeric: 'tabular-nums' }}
            formatter={(v) => (
              <Button
                type="link"
                onClick={() => onSessionLink()}
                style={{ padding: 0, height: 'auto', fontSize: 'inherit', fontWeight: 'inherit', lineHeight: 'inherit' }}
              >
                {v}
              </Button>
            )}
          />
        </Col>
        <Col xs={12} md={6}>
          <Statistic
            title={
              <Space size={4}>
                消息
                <Tooltip title="时间窗内用户消息（发送）/ 助手消息（接收）">
                  <InfoCircleOutlined style={{ color: 'var(--am-ink-3)' }} />
                </Tooltip>
              </Space>
            }
            value={`${s.user_message_count ?? 0} / ${s.assistant_message_count ?? 0}`}
            valueStyle={{ fontVariantNumeric: 'tabular-nums' }}
          />
        </Col>
        <Col xs={12} md={6}>
          <Statistic
            title={
              <Space size={4}>
                Token
                <Tooltip title="时间窗内 input / output token">
                  <InfoCircleOutlined style={{ color: 'var(--am-ink-3)' }} />
                </Tooltip>
              </Space>
            }
            value={`${formatTokens(s.input_tokens ?? 0)} / ${formatTokens(s.output_tokens ?? 0)}`}
            valueStyle={{ fontVariantNumeric: 'tabular-nums' }}
          />
        </Col>
        <Col xs={12} md={6}><Statistic title="参与员工" value={s.user_count} /></Col>
        <Col xs={12} md={6}>
          {/* Git 提交：有提交且有 repo 时数字可点开提交明细，否则纯数字；统一为 <Statistic>。 */}
          <Statistic
            title="Git 提交"
            value={s.git_commit_count}
            valueStyle={{ fontVariantNumeric: 'tabular-nums' }}
            formatter={
              s.git_commit_count > 0 && s.repo_url
                ? (v) => (
                    <Button
                      type="link"
                      onClick={onOpenGitCommits}
                      style={{ padding: 0, height: 'auto', fontSize: 'inherit', fontWeight: 'inherit', lineHeight: 'inherit' }}
                    >
                      {v}
                    </Button>
                  )
                : undefined
            }
          />
        </Col>
      </Row>

      <div style={{ marginTop: 24, height: 280 }}>
        <ReactECharts option={timelineOption} style={{ height: '100%' }} notMerge lazyUpdate />
      </div>

      <Row gutter={16} style={{ marginTop: 24 }} align="stretch">
        <Col xs={24} md={8} style={{ display: 'flex' }}>
          <Card size="small" type="inner" title="贡献者矩阵" style={{ width: '100%' }} styles={{ body: matrixPanelBodyStyle }}>
            <Table<ProjectContributor>
              rowKey="user_code"
              size="small"
              columns={contributorColumns}
              dataSource={detail.contributor_matrix}
              pagination={false}
              scroll={{ y: MATRIX_TABLE_SCROLL_Y }}
              locale={{ emptyText: '暂无贡献者数据' }}
            />
          </Card>
        </Col>
        <Col xs={24} md={8} style={{ display: 'flex' }}>
          <Card size="small" type="inner" title="Slash Commands" style={{ width: '100%' }} styles={{ body: matrixPanelBodyStyle }}>
            <Table<NameValuePair>
              rowKey="name"
              size="small"
              columns={topCountColumns}
              dataSource={detail.top_slash_commands ?? []}
              pagination={false}
              scroll={{ y: MATRIX_TABLE_SCROLL_Y }}
              locale={{ emptyText: '暂无斜杠调用数据' }}
            />
          </Card>
        </Col>
        <Col xs={24} md={8} style={{ display: 'flex' }}>
          <Card size="small" type="inner" title="Top 模型（按 token）" style={{ width: '100%' }} styles={{ body: matrixPanelBodyStyle }}>
            <Table<NameValuePair>
              rowKey="name"
              size="small"
              columns={topModelColumns}
              dataSource={detail.top_models}
              pagination={false}
              scroll={{ y: MATRIX_TABLE_SCROLL_Y }}
              locale={{ emptyText: '暂无模型数据' }}
            />
          </Card>
        </Col>
      </Row>
    </Card>
  );
}
