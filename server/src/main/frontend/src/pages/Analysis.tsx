import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Drawer,
  Empty,
  Input,
  Layout,
  List,
  Popconfirm,
  Progress,
  Space,
  Skeleton,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, DownloadOutlined, FilePdfOutlined, ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import isoWeek from 'dayjs/plugin/isoWeek';
import {
  deleteAnalysisReport,
  fetchAnalysisHistory,
  fetchAnalysisReportDetail,
  fetchAnalysisReportProgress,
  findAnalysisReport,
  generateAnalysisReport,
} from '../api/client';
import type {
  AnalysisReportDetail,
  AnalysisReportListItem,
  AnalysisReportProgress,
  AnalysisReportUser,
} from '../api/types';
import TeamOverview from './Analysis/TeamOverview';
import UserDetail from './Analysis/UserDetail';
import MetricLabel from './Analysis/MetricLabel';
import { BUCKET_META, GRADE_META, WATCHLIST_META, watchlistTagColor } from './Analysis/constants';
import { clickableRowProps, EMPTY_DASH, NUM_STYLE } from '../utils/table';
import { employeeName } from '../utils/format';

dayjs.extend(isoWeek);

const { Sider, Content } = Layout;
const { Title, Text, Paragraph } = Typography;
const { RangePicker } = DatePicker;

const POLL_INTERVAL_MS = 2500;

function defaultRange(): [Dayjs, Dayjs] {
  // 默认这一个自然周（周一 ~ 周日）
  const monday = dayjs().isoWeekday(1).startOf('day');
  const sunday = monday.add(6, 'day');
  return [monday, sunday];
}

/**
 * 分析报告 v3.0 主页面。
 *
 * 布局：
 *   左 320px 历史报告侧栏（最近 50 条）+ 时间窗 + [生成报告] 按钮
 *   主区域：报告头部状态条 → TeamOverview → 员工列表 → 点击员工弹 UserDetail Drawer
 */
export default function Analysis() {
  const [range, setRange] = useState<[Dayjs, Dayjs]>(defaultRange);
  const [history, setHistory] = useState<AnalysisReportListItem[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [currentProgress, setCurrentProgress] = useState<AnalysisReportProgress | null>(null);
  const [currentDetail, setCurrentDetail] = useState<AnalysisReportDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [triggering, setTriggering] = useState(false);
  const [triggerKind, setTriggerKind] = useState<'generate' | 'force' | null>(null);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [drawerUser, setDrawerUser] = useState<AnalysisReportUser | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [compareDetail, setCompareDetail] = useState<AnalysisReportDetail | null>(null);

  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const reload = useCallback(async () => {
    setHistoryLoading(true);
    try {
      const list = await fetchAnalysisHistory();
      setHistory(list);
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  // 打开页面就探测当前窗口是否已有报告
  const probeWindow = useCallback(async (from: Dayjs, to: Dayjs) => {
    try {
      const p = await findAnalysisReport({
        from: from.format('YYYY-MM-DD'),
        to: to.format('YYYY-MM-DD'),
      });
      setCurrentProgress(p);
      if (p && p.status === 'completed') {
        const detail = await fetchAnalysisReportDetail(p.id);
        setCurrentDetail(detail);
      } else {
        setCurrentDetail(null);
      }
    } catch {
      setCurrentProgress(null);
      setCurrentDetail(null);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  useEffect(() => {
    probeWindow(range[0], range[1]);
  }, [range, probeWindow]);

  useEffect(() => {
    if (!currentDetail || currentProgress?.status !== 'completed') {
      setCompareDetail(null);
      return;
    }
    const from = dayjs(currentDetail.window_from);
    const to = dayjs(currentDetail.window_to);
    const days = to.diff(from, 'day');
    const prevTo = from.subtract(1, 'day');
    const prevFrom = prevTo.subtract(days, 'day');
    const prevItem = history.find(
      (h) =>
        h.status === 'completed' &&
        h.window_from === prevFrom.format('YYYY-MM-DD') &&
        h.window_to === prevTo.format('YYYY-MM-DD'),
    );
    if (!prevItem) {
      setCompareDetail(null);
      return;
    }
    let cancelled = false;
    fetchAnalysisReportDetail(prevItem.id)
      .then((d) => {
        if (!cancelled) setCompareDetail(d);
      })
      .catch(() => {
        if (!cancelled) setCompareDetail(null);
      });
    return () => {
      cancelled = true;
    };
  }, [currentDetail, currentProgress?.status, history]);

  // 进行中轮询
  useEffect(() => {
    if (!currentProgress) return;
    if (currentProgress.status !== 'pending' && currentProgress.status !== 'running') return;

    const tick = async () => {
      try {
        const p = await fetchAnalysisReportProgress(currentProgress.id);
        setCurrentProgress(p);
        if (p.status === 'completed') {
          const detail = await fetchAnalysisReportDetail(p.id);
          setCurrentDetail(detail);
          reload();
        } else if (p.status === 'failed') {
          reload();
        } else {
          pollTimerRef.current = setTimeout(tick, POLL_INTERVAL_MS);
        }
      } catch {
        pollTimerRef.current = setTimeout(tick, POLL_INTERVAL_MS * 2);
      }
    };
    pollTimerRef.current = setTimeout(tick, POLL_INTERVAL_MS);

    return () => {
      if (pollTimerRef.current) {
        clearTimeout(pollTimerRef.current);
        pollTimerRef.current = null;
      }
    };
  }, [currentProgress, reload]);

  const handleGenerate = async (force = false) => {
    setTriggering(true);
    setTriggerKind(force ? 'force' : 'generate');
    try {
      const p = await generateAnalysisReport({
        from: range[0].format('YYYY-MM-DD'),
        to: range[1].format('YYYY-MM-DD'),
        force,
      });
      setCurrentProgress(p);
      setCurrentDetail(null);
      reload();
    } finally {
      setTriggering(false);
      setTriggerKind(null);
    }
  };

  const handlePickHistory = async (item: AnalysisReportListItem) => {
    setRange([dayjs(item.window_from), dayjs(item.window_to)]);
    setDetailLoading(true);
    try {
      const p = await fetchAnalysisReportProgress(item.id);
      setCurrentProgress(p);
      if (item.status === 'completed') {
        const detail = await fetchAnalysisReportDetail(item.id);
        setCurrentDetail(detail);
      } else {
        setCurrentDetail(null);
      }
    } finally {
      setDetailLoading(false);
    }
  };

  const handleDeleteHistory = async (item: AnalysisReportListItem) => {
    setDeletingId(item.id);
    try {
      await deleteAnalysisReport(item.id);
      message.success('已删除报告');
      const wasCurrent = currentProgress?.id === item.id;
      await reload();
      if (wasCurrent) {
        setCurrentProgress(null);
        setCurrentDetail(null);
        setDrawerUser(null);
        await probeWindow(range[0], range[1]);
      }
    } catch (e: unknown) {
      const err = e as { message?: string };
      message.error(err.message || '删除失败');
    } finally {
      setDeletingId(null);
    }
  };

  const filteredUsers = useMemo(() => {
    if (!currentDetail) return [];
    if (!searchKeyword.trim()) return currentDetail.users;
    const kw = searchKeyword.trim().toLowerCase();
    return currentDetail.users.filter(
      (u) =>
        u.user_code.toLowerCase().includes(kw) ||
        (u.user_display || '').toLowerCase().includes(kw),
    );
  }, [currentDetail, searchKeyword]);

  /** 异步任务仍在后台执行：禁用「生成报告」避免重复触发 */
  const reportBusy =
    currentProgress?.status === 'pending' || currentProgress?.status === 'running';
  /** 已有结果或失败后均可强制重跑；进行中时点过一次也会在服务端排队，前端禁用二次触发 */
  const showForceRerun =
    !!currentProgress &&
    (currentProgress.status === 'completed' || currentProgress.status === 'failed');
  const actionsLocked = reportBusy || triggering;

  return (
    <Layout style={{ background: 'var(--am-surface-sunken)', minHeight: 'calc(100vh - 64px)' }}>
      <Sider width={220} theme="light" style={{ background: 'var(--am-bg-card)', borderRight: '1px solid var(--am-border)' }}>
        <div style={{ padding: '14px 12px', display: 'flex', flexDirection: 'column', height: '100%' }}>
          <Text strong style={{ fontSize: 13, color: 'var(--am-ink-2)' }}>生成报告</Text>
          <Space direction="vertical" style={{ width: '100%', marginTop: 10 }} size={8}>
            <RangePicker
              value={range}
              onChange={(v) => v && v[0] && v[1] && setRange([v[0]!, v[1]!])}
              style={{ width: '100%' }}
              size="small"
              allowClear={false}
            />
            <Tooltip title={reportBusy ? '报告生成中，请等待结束后再操作' : undefined}>
              <Button
                type="primary"
                block
                size="small"
                loading={triggerKind === 'generate'}
                disabled={actionsLocked}
                onClick={() => handleGenerate(false)}
              >
                生成报告
              </Button>
            </Tooltip>
            {showForceRerun && (
              <Tooltip title={reportBusy ? '请等待当前任务结束后再强制重跑' : undefined}>
                <Button
                  block
                  size="small"
                  danger={currentProgress!.status === 'failed'}
                  icon={<SyncOutlined />}
                  disabled={actionsLocked}
                  loading={triggerKind === 'force'}
                  onClick={() => handleGenerate(true)}
                >
                  强制重跑
                </Button>
              </Tooltip>
            )}
            <Text type="secondary" style={{ fontSize: 12, lineHeight: 1.45 }}>
              默认本自然周；同窗口复用已有结果，强制重跑将重新审计。
            </Text>
          </Space>

          <div
            style={{
              marginTop: 20,
              paddingTop: 14,
              borderTop: '1px solid var(--am-border-subtle)',
              flex: 1,
              minHeight: 0,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
              <Text strong style={{ fontSize: 12, color: 'var(--am-ink-3)', flex: 1 }}>历史</Text>
              <Button
                size="small"
                type="text"
                icon={<ReloadOutlined />}
                aria-label="刷新历史"
                onClick={reload}
                loading={historyLoading}
              />
            </div>
            <List
              size="small"
              loading={historyLoading}
              dataSource={history}
              locale={{ emptyText: <Text type="secondary" style={{ fontSize: 12 }}>暂无</Text> }}
              style={{ flex: 1, overflow: 'auto' }}
              renderItem={(item) => {
                const busy = item.status === 'pending' || item.status === 'running';
                const active = currentProgress?.id === item.id;
                return (
              <List.Item
                onClick={() => handlePickHistory(item)}
                style={{
                  cursor: 'pointer',
                  borderRadius: 6,
                  padding: '6px 8px',
                  marginBottom: 4,
                  border: active ? '1px solid var(--am-brand-border)' : '1px solid transparent',
                  background: active ? 'var(--am-brand-bg)' : 'transparent',
                }}
                actions={[
                  <Popconfirm
                    key="delete"
                    title="删除这份报告？"
                    description={busy ? '生成中请稍候' : '删除后不可恢复'}
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                    disabled={busy}
                    onConfirm={() => handleDeleteHistory(item)}
                    onPopupClick={(e) => e.stopPropagation()}
                  >
                    <Button
                      type="text"
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      aria-label="删除报告"
                      loading={deletingId === item.id}
                      disabled={busy}
                      onClick={(e) => e.stopPropagation()}
                    />
                  </Popconfirm>,
                ]}
              >
                <div style={{ width: '100%' }}>
                  <div>
                    <Text strong style={{ fontSize: 12 }}>
                      {item.window_from.slice(5)}~{item.window_to.slice(5)}
                    </Text>
                    <Tag
                      color={statusColor(item.status)}
                      style={{ margin: 0, fontSize: 12, lineHeight: '16px' }}
                    >
                      {statusLabel(item.status)}
                    </Tag>
                  </div>
                  <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 2 }}>
                    {item.status === 'completed'
                      ? `${item.active_user_count ?? 0}人 · ${item.total_session_count ?? 0}会话`
                      : `${item.audited_count}/${item.total_count}`}
                  </Text>
                </div>
              </List.Item>
              );
            }}
          />
          </div>
        </div>
      </Sider>

      <Content style={{ padding: '20px 28px 32px', maxWidth: 1400 }}>
        {renderHeader(currentProgress, currentDetail, () => {
          if (!currentDetail) return;
          import('./Analysis/exportAnalysisReport')
            .then(({ exportAnalysisReportExcel }) => {
              exportAnalysisReportExcel(currentDetail);
              message.success('已开始下载 Excel');
            })
            .catch(() => {
              message.error('导出失败，请稍后重试');
            });
        }, () => {
          if (!currentDetail) return;
          message.loading({ content: '正在生成 PDF…', key: 'pdf' });
          import('./Analysis/exportAnalysisPdf')
            .then(({ exportAnalysisReportPdf }) => exportAnalysisReportPdf(currentDetail))
            .then(() => message.success({ content: '已开始下载 PDF', key: 'pdf' }))
            .catch(() => message.error({ content: 'PDF 导出失败，请稍后重试', key: 'pdf' }));
        })}
        {detailLoading && (
          <Card>
            <Skeleton active paragraph={{ rows: 6 }} />
          </Card>
        )}
        {!currentProgress && !detailLoading && (
          <Card>
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={
                <>
                  当前时间窗（{range[0].format('YYYY-MM-DD')} ~ {range[1].format('YYYY-MM-DD')}）
                  暂无报告，点击左侧"生成报告"开始
                </>
              }
            />
          </Card>
        )}
        {currentProgress && currentProgress.status !== 'completed' && (
          <Card style={{ marginBottom: 16 }}>
            {currentProgress.status === 'failed' ? (
              <Alert
                type="error"
                showIcon
                message="报告生成失败"
                description={currentProgress.error_text}
                action={
                  <Button
                    size="small"
                    type="primary"
                    danger
                    icon={<SyncOutlined />}
                    loading={triggerKind === 'force'}
                    disabled={actionsLocked}
                    onClick={() => handleGenerate(true)}
                  >
                    强制重跑
                  </Button>
                }
              />
            ) : (
              <div>
                <Title level={5} style={{ marginTop: 0 }}>
                  报告生成中…（{currentProgress.status}）
                </Title>
                <Progress percent={currentProgress.progress_percent} status="active" />
                <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                  进度 {currentProgress.audited_count} / {currentProgress.total_count}：
                  进行中多为「本轮仍需调用 LLM 的会话」计数，已命中{' '}
                  <Text code>ai_session_audit</Text> 缓存的不计入，故可能远小于总会话数。
                  普通生成会跨窗口复用缓存；会话消息较上次审计新增超过阈值、或审计版本升级时会再跑；
                  点「强制重跑」则对本窗口全部会话忽略缓存重审（仍受服务端 LLM 次数上限约束）。
                </Paragraph>
              </div>
            )}
          </Card>
        )}
        {currentDetail && currentProgress?.status === 'completed' && (
          <>
            <TeamOverview
              report={currentDetail}
              compareReport={compareDetail}
              onPickUser={(code) => {
                const u = currentDetail.users.find((x) => x.user_code === code);
                if (u) setDrawerUser(u);
              }}
            />
            <div
              style={{
                marginTop: 20,
                border: '1px solid var(--am-border)',
                borderRadius: 'var(--am-r-md)',
                overflow: 'hidden',
                background: 'var(--am-bg-card)',
                boxShadow: 'var(--am-shadow-1), var(--am-inner-hi)',
              }}
            >
              <div
                style={{
                  padding: '12px 16px',
                  borderBottom: '1px solid var(--am-border-subtle)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 12,
                  flexWrap: 'wrap',
                  background: 'var(--am-surface-sunken)',
                }}
              >
                <Text strong style={{ fontSize: 13, color: 'var(--am-ink-2)' }}>员工数据列表</Text>
                <Input.Search
                  placeholder="按姓名 / 工号搜索"
                  size="small"
                  style={{ width: 200 }}
                  allowClear
                  onChange={(e) => setSearchKeyword(e.target.value)}
                />
              </div>
              <div style={{ padding: '0 8px 8px' }}>
                <UserListTable users={filteredUsers} onPick={(u) => setDrawerUser(u)} />
              </div>
            </div>
          </>
        )}

        <Drawer
          width={920}
          open={!!drawerUser}
          onClose={() => setDrawerUser(null)}
          title={
            drawerUser ? (
              <span>
                {employeeName(drawerUser.user_display, drawerUser.user_code)}
                {drawerUser.composite_grade ? (
                  <Tag
                    color={GRADE_META[drawerUser.composite_grade].color}
                    style={{ marginLeft: 8 }}
                  >
                    {GRADE_META[drawerUser.composite_grade].label}
                  </Tag>
                ) : drawerUser.composite_bucket ? (
                  <Tag
                    color={BUCKET_META[drawerUser.composite_bucket].color}
                    style={{ marginLeft: 8 }}
                  >
                    {BUCKET_META[drawerUser.composite_bucket].label}
                  </Tag>
                ) : null}
              </span>
            ) : (
              ''
            )
          }
        >
          {drawerUser && currentDetail && (
            <UserDetail report={currentDetail} user={drawerUser} />
          )}
        </Drawer>
      </Content>
    </Layout>
  );
}

function renderHeader(
  progress: AnalysisReportProgress | null,
  detail: AnalysisReportDetail | null,
  onExportExcel?: () => void,
  onExportPdf?: () => void,
) {
  if (!detail || progress?.status !== 'completed') return null;

  const warnings: string[] = [];
  const days =
    dayjs(detail.window_to).diff(dayjs(detail.window_from), 'day') + 1;
  if (days < 7) warnings.push('窗口不足 7 天，基线稳定性偏弱');
  if ((detail.active_user_count ?? 0) < 10) {
    warnings.push('活跃员工不足 10 人，分位与 watchlist 仅供参考');
  }
  if (
    detail.judge_disagreement_ratio != null &&
    detail.judge_disagreement_ratio > 0.3
  ) {
    warnings.push('双 judge 一致率低于 70%，建议检查 rubric 或模型配置');
  }
  if (
    detail.total_count > 0 &&
    detail.audited_count / detail.total_count < 0.9
  ) {
    warnings.push('部分会话未完成审计，报告可能不完整');
  }

  return (
    <div style={{ marginBottom: 20 }}>
      {warnings.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          }
        />
      )}
    <div
      style={{
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'space-between',
        gap: 16,
        flexWrap: 'wrap',
      }}
    >
      <div>
        <Title level={4} style={{ margin: 0 }}>
          {detail.window_from} ~ {detail.window_to} 分析报告
        </Title>
        <Text type="secondary" style={{ fontSize: 13, marginTop: 4, display: 'block' }}>
          活跃员工 {detail.active_user_count ?? 0} 人 · 会话 {detail.total_session_count ?? 0} 个 · 已审计{' '}
          {detail.audited_count}/{detail.total_count} · 双 judge 不一致率{' '}
          {detail.judge_disagreement_ratio == null
            ? '—'
            : `${(detail.judge_disagreement_ratio * 100).toFixed(1)}%`}
        </Text>
      </div>
      <Space>
        <Button icon={<DownloadOutlined />} onClick={onExportExcel}>
          导出 Excel
        </Button>
        <Button icon={<FilePdfOutlined />} onClick={onExportPdf}>
          导出 PDF
        </Button>
      </Space>
    </div>
    </div>
  );
}

const USER_LIST_PAGE_SIZE = 10;

function UserListTable({
  users,
  onPick,
}: {
  users: AnalysisReportUser[];
  onPick: (u: AnalysisReportUser) => void;
}) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(USER_LIST_PAGE_SIZE);

  // 数据变化时回到第一页（搜索后避免页码越界）
  useEffect(() => {
    setPage(1);
  }, [users]);

  const columns = useMemo<ColumnsType<AnalysisReportUser>>(() => [
    {
      title: '员工',
      dataIndex: 'user_display',
      key: 'employee',
      fixed: 'left',
      width: 160,
      ellipsis: { showTitle: false },
      render: (_: string | undefined, u) => (
        <Tooltip title={u.user_display || u.user_code}>
          <span>
            <strong>{employeeName(u.user_display, u.user_code)}</strong>
            {u.insufficient_data && (
              <Tag color="default" style={{ marginLeft: 6, fontSize: 12 }}>
                样本不足
              </Tag>
            )}
          </span>
        </Tooltip>
      ),
    },
    {
      title: <MetricLabel name="composite_bucket" />,
      key: 'composite_bucket',
      width: 110,
      align: 'center',
      render: (_: unknown, u) =>
        u.composite_grade ? (
          <Tooltip title={`${GRADE_META[u.composite_grade].desc}${u.composite_confidence === 'low' ? ' · 低置信度' : ''}`}>
            <Tag color={GRADE_META[u.composite_grade].color} style={{ marginInlineEnd: 0 }}>
              {GRADE_META[u.composite_grade].label}
            </Tag>
          </Tooltip>
        ) : u.composite_bucket ? (
          <Tag color={BUCKET_META[u.composite_bucket].color} style={{ marginInlineEnd: 0 }}>
            {BUCKET_META[u.composite_bucket].label}
          </Tag>
        ) : (
          EMPTY_DASH
        ),
    },
    {
      title: <MetricLabel name="ai_active_hours_list" />,
      key: 'ai_active_hours',
      width: 112,
      align: 'center',
      render: (_: unknown, u) => <span style={NUM_STYLE}>{u.ai_active_hours.toFixed(1)}</span>,
    },
    {
      title: <MetricLabel name="ai_commit_count" />,
      key: 'ai_commit_count',
      width: 104,
      align: 'center',
      render: (_: unknown, u) => <span style={NUM_STYLE}>{u.ai_commit_count}</span>,
    },
    {
      title: <MetricLabel name="high_difficulty_ratio" />,
      key: 'high_difficulty_ratio',
      width: 124,
      align: 'center',
      render: (_: unknown, u) => <span style={NUM_STYLE}>{fmtPct(u.high_difficulty_ratio)}</span>,
    },
    {
      title: <MetricLabel name="ai_commits_per_active_hour" />,
      key: 'ai_commits_per_active_hour',
      width: 120,
      align: 'center',
      render: (_: unknown, u) => (
        <span style={NUM_STYLE}>
          {u.ai_commits_per_active_hour == null ? '—' : u.ai_commits_per_active_hour.toFixed(2)}
        </span>
      ),
    },
    {
      title: <MetricLabel name="commit_revert_rate" />,
      key: 'commit_revert_rate',
      width: 112,
      align: 'center',
      render: (_: unknown, u) => <span style={NUM_STYLE}>{fmtPct(u.commit_revert_rate)}</span>,
    },
    {
      title: <MetricLabel name="watchlist_flags" />,
      key: 'watchlist_flags',
      width: 220,
      render: (_: unknown, u) => {
        const flags = u.watchlist_flags ?? [];
        if (flags.length === 0) return EMPTY_DASH;
        return flags.map((f) => (
          <Tooltip
            key={f}
            title={WATCHLIST_META[f]?.help ?? '无说明'}
            overlayStyle={{ maxWidth: 360 }}
          >
            <Tag
              color={watchlistTagColor(f)}
              style={{ marginRight: 2, cursor: 'help' }}
            >
              {WATCHLIST_META[f]?.label ?? f}
            </Tag>
          </Tooltip>
        ));
      },
    },
    {
      title: '',
      key: 'action',
      width: 72,
      align: 'center',
      render: (_: unknown, u) => (
        <Button
          type="link"
          size="small"
          onClick={(e) => {
            e.stopPropagation();
            onPick(u);
          }}
        >
          详情
        </Button>
      ),
    },
  ], [onPick]);

  return (
    <Table<AnalysisReportUser>
      className="am-sticky-table"
      size="small"
      rowKey="user_code"
      dataSource={users}
      columns={columns}
      scroll={{ x: 1120 }}
      locale={{
        emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无匹配员工" />,
      }}
      onRow={(u) => clickableRowProps(() => onPick(u))}
      pagination={{
        current: page,
        pageSize,
        total: users.length,
        showSizeChanger: true,
        showTotal: (t) => `共 ${t} 人`,
        pageSizeOptions: ['10', '20', '50', '100'],
        size: 'small',
        onChange: (p, ps) => {
          setPage(p);
          if (ps !== pageSize) setPageSize(ps);
        },
      }}
    />
  );
}

function statusColor(s: string): string {
  switch (s) {
    case 'completed':
      return 'green';
    case 'running':
    case 'pending':
      return 'processing';
    case 'failed':
      return 'red';
    default:
      return 'default';
  }
}

function statusLabel(s: string): string {
  switch (s) {
    case 'completed':
      return '完成';
    case 'running':
      return '进行中';
    case 'pending':
      return '排队';
    case 'failed':
      return '失败';
    default:
      return s;
  }
}

function fmtPct(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return '—';
  return `${(v * 100).toFixed(1)}%`;
}
