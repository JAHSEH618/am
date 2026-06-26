import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Card, Col, Empty, List, Row, Skeleton, Statistic, Tabs, Tag, Tooltip, Typography } from 'antd';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts-for-react';
import { Link } from 'react-router-dom';
import { fetchPersonDetail } from '../../api/client';
import type {
  AnalysisReportDetail,
  AnalysisReportUser,
  HighlightSessionCard,
  NameValuePair,
  PeopleDetail,
} from '../../api/types';
import { formatTokens } from '../../utils/format';
import { ink, indigo } from '../../styles/tokens';
import { BUCKET_META, CAPABILITY_DIMENSIONS, MODE_META, WATCHLIST_META, watchlistTagColor } from './constants';
import MetricLabel from './MetricLabel';

const { Text, Paragraph } = Typography;

const CAP_TEAM_KEY: Record<
  (typeof CAPABILITY_DIMENSIONS)[number]['key'],
  | 'problem_decomposition'
  | 'context_management'
  | 'debugging_skill'
  | 'tool_orchestration'
  | 'self_correction'
> = {
  cap_problem_decomposition: 'problem_decomposition',
  cap_context_management: 'context_management',
  cap_debugging_skill: 'debugging_skill',
  cap_tool_orchestration: 'tool_orchestration',
  cap_self_correction: 'self_correction',
};

function EChartsAutoBox({ option, height }: { option: EChartsOption; height: number }) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<InstanceType<typeof ReactECharts>>(null);

  useLayoutEffect(() => {
    const wrap = wrapRef.current;
    if (!wrap) return;

    const resize = () => {
      chartRef.current?.getEchartsInstance()?.resize();
    };

    const ro = new ResizeObserver(() => resize());
    ro.observe(wrap);
    resize();
    requestAnimationFrame(() => resize());

    return () => ro.disconnect();
  }, [option]);

  return (
    <div ref={wrapRef} style={{ width: '100%', height }}>
      <ReactECharts ref={chartRef} option={option} style={{ height: '100%', width: '100%' }} />
    </div>
  );
}

interface Props {
  report: AnalysisReportDetail;
  user: AnalysisReportUser;
}

export default function UserDetail({ report, user }: Props) {
  const slashTotal = (user.tool_command_count ?? 0) + (user.tool_skill_count ?? 0);
  const [peopleDetail, setPeopleDetail] = useState<PeopleDetail | null>(null);
  // 员工数据（日趋势）异步拉取期间的加载态：避免在到位前一闪而过地显示「暂无 timeline」兜底
  const [peopleLoading, setPeopleLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setPeopleLoading(true);
    fetchPersonDetail(user.user_code, {
      from: report.window_from,
      to: report.window_to,
    })
      .then((d) => {
        if (!cancelled) setPeopleDetail(d);
      })
      .catch(() => {
        if (!cancelled) setPeopleDetail(null);
      })
      .finally(() => {
        if (!cancelled) setPeopleLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [user.user_code, report.window_from, report.window_to]);

  const teamP50Radar = useMemo(() => {
    const caps = report.team_capability_percentiles;
    if (!caps) return [3, 3, 3, 3, 3];
    return CAPABILITY_DIMENSIONS.map((d) => {
      const bundle = caps[CAP_TEAM_KEY[d.key]];
      const p50 = bundle?.p50;
      return p50 == null || Number.isNaN(p50) ? 3 : p50;
    });
  }, [report.team_capability_percentiles]);

  const radarOption = useMemo(() => {
    const userRadar = CAPABILITY_DIMENSIONS.map((d) => Number(user[d.key] ?? 0));
    return {
      tooltip: { trigger: 'item' as const },
      legend: { data: ['该员工', '团队 P50'], top: 0 },
      radar: {
        indicator: CAPABILITY_DIMENSIONS.map((d) => ({ name: d.label, max: 5 })),
        radius: 92,
      },
      series: [
        {
          type: 'radar',
          areaStyle: { opacity: 0.2 },
          data: [
            { name: '该员工', value: userRadar, itemStyle: { color: indigo[600] } },
            { name: '团队 P50', value: teamP50Radar, itemStyle: { color: ink[3] } },
          ],
        },
      ],
    };
  }, [user, teamP50Radar]);

  const difficultyOption = useMemo(() => {
    const dist = user.difficulty_dist || { '1': 0, '2': 0, '3': 0, '4': 0, '5': 0 };
    return {
      tooltip: { trigger: 'axis' as const },
      grid: { left: 36, right: 16, bottom: 28, top: 28 },
      xAxis: { type: 'category' as const, data: ['1', '2', '3', '4', '5'], name: '难度', axisLabel: { color: ink[3] } },
      yAxis: { type: 'value' as const, name: '会话数', axisLabel: { color: ink[3] } },
      series: [
        {
          type: 'bar' as const,
          data: ['1', '2', '3', '4', '5'].map((k) => dist[k as keyof typeof dist] ?? 0),
          itemStyle: {
            // 难度 1→5 单色顺序阶（浅→深品牌钴蓝）：难度高=正面信号，不涂红
            color: (p: { dataIndex: number }) =>
              [indigo[200], indigo[300], indigo[400], indigo[500], indigo[600]][p.dataIndex],
          },
          label: { show: true, position: 'top' as const },
        },
      ],
    };
  }, [user]);

  const modeOption = useMemo(() => {
    const dist = user.mode_dist || {};
    const data = Object.entries(dist)
      .filter(([, v]) => (v ?? 0) > 0)
      .map(([k, v]) => ({
        name: MODE_META[k]?.label ?? k,
        value: Math.round((v ?? 0) * 1000) / 10,
        itemStyle: { color: MODE_META[k]?.color ?? ink[3] },
      }));
    return {
      tooltip: { trigger: 'item' as const, formatter: '{b}: {c}%' },
      legend: { bottom: 0 },
      series: [
        {
          type: 'pie' as const,
          radius: ['40%', '70%'],
          center: ['50%', '45%'],
          data,
          label: { formatter: '{b}\n{c}%' },
        },
      ],
    };
  }, [user]);

  const activeHoursTimelineOption = useMemo(() => {
    const timeline = peopleDetail?.daily_timeline ?? [];
    if (timeline.length === 0) return null;
    const dates = [...timeline].reverse().map((p) => p.date);
    const hours = [...timeline]
      .reverse()
      .map((p) => Math.round(((p.ai_active_seconds_union ?? 0) / 3600) * 10) / 10);
    return {
      tooltip: { trigger: 'axis' as const },
      grid: { left: 40, right: 16, bottom: 28, top: 28 },
      xAxis: { type: 'category' as const, data: dates, axisLabel: { color: ink[3] } },
      yAxis: { type: 'value' as const, name: '协作 h', axisLabel: { color: ink[3] } },
      series: [{ type: 'line' as const, data: hours, smooth: true, color: indigo[600], areaStyle: { opacity: 0.15 } }],
    };
  }, [peopleDetail]);

  const watchlistFlags = user.watchlist_flags || [];
  const peopleLink = `/people/${encodeURIComponent(user.user_code)}?from=${encodeURIComponent(report.window_from)}&to=${encodeURIComponent(report.window_to)}`;

  return (
    <div>
      <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'flex-end' }}>
        <Link to={peopleLink}>
          <Button type="link" size="small">
            查看员工数据（同窗口）→
          </Button>
        </Link>
      </div>

      <div
        style={{
          marginBottom: 16,
          border: '1px solid var(--am-border)',
          borderRadius: 'var(--am-r-md)',
          overflow: 'hidden',
          background: 'var(--am-bg-card)',
          boxShadow: 'var(--am-shadow-1), var(--am-inner-hi)',
        }}
      >
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(6, minmax(0, 1fr))' }}>
          <KpiCell
            title={<MetricLabel name="composite_bucket" />}
            value={user.composite_bucket ? BUCKET_META[user.composite_bucket].label : '—'}
            footer={
              user.composite_percentile != null ? `百分位 ${user.composite_percentile.toFixed(1)}` : undefined
            }
            showDivider
          />
          <KpiCell
            title={<MetricLabel name="ai_active_hours" />}
            value={user.ai_active_hours.toFixed(1)}
            showDivider
          />
          <KpiCell title={<MetricLabel name="ai_commit_count" />} value={user.ai_commit_count} showDivider />
          <KpiCell
            title={<MetricLabel name="high_difficulty_ratio" />}
            value={fmtPct(user.high_difficulty_ratio)}
            showDivider
          />
          <KpiCell
            title={<MetricLabel name="ai_commits_per_active_hour" />}
            value={user.ai_commits_per_active_hour == null ? '—' : user.ai_commits_per_active_hour.toFixed(2)}
            showDivider
          />
          <KpiCell
            title={<MetricLabel name="session_count" />}
            value={user.session_count}
            footer={`Slash ${slashTotal}`}
          />
        </div>
      </div>

      {watchlistFlags.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={
            <span>
              该员工命中以下 watchlist：
              {watchlistFlags.map((f) => (
                <Tooltip
                  key={f}
                  title={WATCHLIST_META[f]?.help ?? '无说明'}
                  overlayStyle={{ maxWidth: 360 }}
                >
                  <Tag
                    color={watchlistTagColor(f)}
                    style={{ marginLeft: 6, cursor: 'help' }}
                  >
                    {WATCHLIST_META[f]?.label ?? f}
                  </Tag>
                </Tooltip>
              ))}
            </span>
          }
        />
      )}

      {user.insufficient_data && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="样本不足，仅展示基本量。能力评判需要窗口内 ≥ 10 个已审计会话。"
        />
      )}

      <Tabs
        destroyInactiveTabPane
        items={[
          {
            key: 'radar',
            label: '能力雷达',
            children: (
              <Card>
                <EChartsAutoBox option={radarOption} height={360} />
                <Row gutter={16} style={{ marginTop: 8 }}>
                  {CAPABILITY_DIMENSIONS.map((d) => (
                    <Col key={d.key} span={4}>
                      <Statistic
                        title={<MetricLabel name={d.key} />}
                        value={user[d.key] == null ? '—' : Number(user[d.key]).toFixed(2)}
                        suffix="/ 5"
                      />
                    </Col>
                  ))}
                </Row>
                <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
                  五维能力为难度加权均值；灰色参考圈为窗口内团队 P50（非固定 3）。
                </Paragraph>
              </Card>
            ),
          },
          {
            key: 'difficulty',
            label: '任务难度',
            children: (
              <Card>
                <EChartsAutoBox option={difficultyOption} height={300} />
                <Row gutter={16} style={{ marginTop: 12 }}>
                  <Col span={8}>
                    <Statistic
                      title={<MetricLabel name="avg_difficulty" />}
                      value={user.avg_difficulty == null ? '—' : user.avg_difficulty.toFixed(2)}
                      suffix="/ 5"
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={<MetricLabel name="high_difficulty_ratio" />}
                      value={fmtPct(user.high_difficulty_ratio)}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={<MetricLabel name="high_difficulty_commit_ratio" />}
                      value={fmtPct(user.high_difficulty_commit_ratio)}
                    />
                  </Col>
                </Row>
              </Card>
            ),
          },
          {
            key: 'mode',
            label: '协作模式',
            children: (
              <Card>
                <EChartsAutoBox option={modeOption} height={360} />
                <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                  leverage / learning / exploratory 偏良性；dependent / debugging 占比高时配合 watchlist 关注。
                </Paragraph>
              </Card>
            ),
          },
          {
            key: 'output',
            label: '产出验证',
            children: (
              <Card>
                <Row gutter={16}>
                  <Col span={6}>
                    <Statistic
                      title={<MetricLabel name="ai_commits_per_active_hour" />}
                      value={
                        user.ai_commits_per_active_hour == null
                          ? '—'
                          : user.ai_commits_per_active_hour.toFixed(2)
                      }
                    />
                  </Col>
                  <Col span={6}>
                    <Statistic
                      title={<MetricLabel name="ai_lines_per_1k_token" />}
                      value={
                        user.ai_lines_per_1k_token == null
                          ? '—'
                          : user.ai_lines_per_1k_token.toFixed(2)
                      }
                    />
                  </Col>
                  <Col span={6}>
                    <Statistic title={<MetricLabel name="commit_revert_rate" />} value={fmtPct(user.commit_revert_rate)} />
                  </Col>
                  <Col span={6}>
                    <Statistic
                      title={<MetricLabel name="ai_lines_added" />}
                      value={formatTokens(user.ai_lines_added)}
                    />
                  </Col>
                </Row>
                {peopleLoading ? (
                  <div style={{ marginTop: 16 }}>
                    <Skeleton active paragraph={{ rows: 5 }} />
                  </div>
                ) : activeHoursTimelineOption ? (
                  <div style={{ marginTop: 16 }}>
                    <Text strong style={{ fontSize: 12, color: 'var(--am-ink-3)' }}>
                      日协作时长趋势（员工数据同源）
                    </Text>
                    <EChartsAutoBox option={activeHoursTimelineOption} height={220} />
                  </div>
                ) : (
                  <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
                    产出指标基于 git_commit；日趋势需员工数据页日汇总，当前窗口暂无 timeline。
                  </Paragraph>
                )}
              </Card>
            ),
          },
          {
            key: 'usage',
            label: 'Top 使用',
            children: (
              <Card>
                <TopUsageSection title="Top 模型" items={user.top_models} />
                <TopUsageSection title="Top 项目" items={user.top_projects} />
                <TopUsageSection title="Agent 分布（会话数）" items={user.agent_dist} />
                <TopUsageSection
                  title="Slash Commands"
                  items={(user.tool_breakdown ?? []).map((i) => ({
                    name: i.name,
                    value: i.count,
                  }))}
                />
              </Card>
            ),
          },
          {
            key: 'highlights',
            label: '典型会话',
            children: (
              <HighlightSessions
                cards={user.highlight_sessions ?? []}
                fallbackIds={user.highlight_session_ids ?? []}
              />
            ),
          },
        ]}
      />
    </div>
  );
}

function TopUsageSection({ title, items }: { title: string; items?: NameValuePair[] | null }) {
  const list = items ?? [];
  return (
    <div style={{ marginBottom: 16 }}>
      <Text strong style={{ display: 'block', marginBottom: 8 }}>
        {title}
      </Text>
      {list.length === 0 ? (
        <Text type="secondary">暂无数据</Text>
      ) : (
        <List
          size="small"
          dataSource={list}
          renderItem={(item) => (
            <List.Item style={{ padding: '4px 0', gap: 12 }}>
              <span className="am-break" style={{ minWidth: 0 }}>{item.name}</span>
              <span style={{ flexShrink: 0 }}>{item.value}</span>
            </List.Item>
          )}
        />
      )}
    </div>
  );
}

function HighlightSessions({
  cards,
  fallbackIds,
}: {
  cards: HighlightSessionCard[];
  fallbackIds: number[];
}) {
  const rows = cards.length > 0
    ? cards
    : fallbackIds.map((id) => ({ session_id: id, difficulty: 0, mode: '', reason: '', nearby_commit: false }));

  if (rows.length === 0) {
    return <Empty description="本窗口未挑出典型会话样本（可能样本不足）" />;
  }

  return (
    <Card>
      <Paragraph type="secondary" style={{ marginBottom: 12 }}>
        代表本窗口画像的样本：高难度完成、高能力体现、或值得讨论的卡点会话（不含会话结果标签）。
      </Paragraph>
      <List
        dataSource={rows}
        renderItem={(item) => (
          <List.Item>
            <div style={{ width: '100%' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                <Link to={`/sessions/${item.session_id}`}>会话 #{item.session_id}</Link>
                {item.difficulty > 0 && <Tag>难度 {item.difficulty}</Tag>}
                {item.mode && (
                  <Tag color={MODE_META[item.mode]?.color}>{MODE_META[item.mode]?.label ?? item.mode}</Tag>
                )}
                {item.nearby_commit && <Tag color="green">±30min 有提交</Tag>}
              </div>
              {item.reason ? (
                <Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 6, fontSize: 12 }}>
                  {item.reason}
                </Paragraph>
              ) : null}
            </div>
          </List.Item>
        )}
      />
    </Card>
  );
}

/** 顶部 KPI 单元格：与团队概览同款「单面板 + 竖向分隔」，去掉逐卡边框堆叠感。 */
function KpiCell({
  title,
  value,
  footer,
  showDivider,
}: {
  title: React.ReactNode;
  value: string | number;
  footer?: string;
  showDivider?: boolean;
}) {
  return (
    <div
      style={{
        padding: '14px 16px',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 104,
        ...(showDivider ? { borderRight: '1px solid var(--am-border-subtle)' } : {}),
      }}
    >
      <Statistic title={title} value={value} />
      <div style={{ marginTop: 'auto', paddingTop: 8, minHeight: 20 }}>
        {footer ? (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {footer}
          </Text>
        ) : null}
      </div>
    </div>
  );
}

function fmtPct(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return '—';
  return `${(v * 100).toFixed(1)}%`;
}
