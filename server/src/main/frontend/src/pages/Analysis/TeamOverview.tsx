import { useEffect, useMemo, useState } from 'react';
import { Button, Empty, Input, Select, Statistic, Table, Tag, Tooltip, Typography } from 'antd';
import ReactECharts from 'echarts-for-react';
import type { AnalysisReportDetail } from '../../api/types';
import { ink, accent, indigo, semantic } from '../../styles/tokens';
import { NUM_STYLE } from '../../utils/table';
import { employeeName } from '../../utils/format';
import { categoryAxisGridLeft } from '../../utils/chartAxis';
import { MODE_META, WATCHLIST_META, watchlistTagColor } from './constants';
import MetricLabel from './MetricLabel';

interface WatchlistTableRow {
  key: string;
  flag: string;
  user_code: string;
  triggerLabel: string;
  employeeDisplay: string;
}

const WATCHLIST_TABLE_PAGE_SIZE = 10;

const { Text, Paragraph } = Typography;

const panelStyle: React.CSSProperties = {
  marginBottom: 20,
  border: '1px solid var(--am-border)',
  borderRadius: 'var(--am-r-md)',
  overflow: 'hidden',
  background: 'var(--am-bg-card)',
  boxShadow: 'var(--am-shadow-1), var(--am-inner-hi)',
};

const sectionHeadStyle: React.CSSProperties = {
  padding: '10px 16px',
  borderBottom: '1px solid var(--am-border-subtle)',
  background: 'var(--am-surface-sunken)',
  fontSize: 13,
  fontWeight: 600,
  color: 'var(--am-ink-2)',
};

const cellDivider: React.CSSProperties = {
  borderRight: '1px solid var(--am-border-subtle)',
};

interface Props {
  report: AnalysisReportDetail;
  onPickUser?: (userCode: string) => void;
  compareReport?: AnalysisReportDetail | null;
}

/**
 * 团队级摘要：KPI 单面板 + 分布图合并面板 + 分位/watchlist 双栏，减少 Card 堆叠。
 */
export default function TeamOverview({ report, onPickUser, compareReport }: Props) {
  const diffOption = useMemo(() => {
    const dist = report.team_difficulty_dist || { '1': 0, '2': 0, '3': 0, '4': 0, '5': 0 };
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
  }, [report]);

  const gradeOption = useMemo(() => {
    const dist = report.team_grade_dist || {};
    const grades = ['S', 'A', 'B', 'C', 'D'] as const;
    const colors = [accent.purple.base, semantic.success.base, accent.blue.base, semantic.warning.base, semantic.error.base];
    return {
      tooltip: { trigger: 'axis' as const },
      grid: { left: 36, right: 16, bottom: 28, top: 28 },
      xAxis: { type: 'category' as const, data: [...grades], name: '等级', axisLabel: { color: ink[3] } },
      yAxis: { type: 'value' as const, name: '人数', axisLabel: { color: ink[3] } },
      series: [
        {
          type: 'bar' as const,
          data: grades.map((g) => dist[g] ?? 0),
          itemStyle: { color: (p: { dataIndex: number }) => colors[p.dataIndex] },
          label: { show: true, position: 'top' as const },
        },
      ],
    };
  }, [report]);

  const modeOption = useMemo(() => {
    const dist = (report.team_mode_dist || {}) as Record<string, number>;
    const total = Object.values(dist).reduce((s, v) => s + (v ?? 0), 0) || 1;
    const data = Object.entries(dist)
      .filter(([, v]) => (v ?? 0) > 0)
      .map(([k, v]) => ({
        name: MODE_META[k]?.label ?? k,
        value: Math.round(((v ?? 0) / total) * 1000) / 10,
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
  }, [report]);

  const toolBreakdownChart = useMemo(() => {
    const raw = report.team_tool_breakdown;
    if (!raw) return null;
    const items = [...(raw.items ?? [])]
      .filter((i) => (i?.count ?? 0) > 0)
      .sort((a, b) => {
        const ko = (kind: 'command' | 'skill') => (kind === 'command' ? 0 : 1);
        const byKind = ko(a.kind) - ko(b.kind);
        if (byKind !== 0) return byKind;
        return (b.count ?? 0) - (a.count ?? 0);
      });
    if (items.length === 0 && (raw.command_total ?? 0) === 0 && (raw.skill_total ?? 0) === 0) return null;

    const categories = items.map((i) => i.name);
    const counts = items.map((i) => i.count ?? 0);
    const colors = items.map((i) => (i.kind === 'skill' ? accent.purple.base : indigo[600]));
    const innerChartHeight = Math.max(120, 32 + Math.max(items.length, 1) * 30);
    // 左侧留白按最长标签估算（取代手写 axisLabel.width + containLabel），避免命令名被截断或越界
    const gridLeft = categoryAxisGridLeft(categories);

    const option = {
      tooltip: {
        trigger: 'axis' as const,
        axisPointer: { type: 'shadow' as const },
        formatter: (params: unknown) => {
          const p = (Array.isArray(params) ? params[0] : params) as { name?: string; value?: number };
          const n = Number(p?.value ?? 0);
          return `${p?.name ?? ''}<br/><span style="font-weight:600">${n} 次</span>`;
        },
      },
      grid: { left: gridLeft, right: 48, top: 8, bottom: 28 },
      xAxis: {
        type: 'value' as const,
        name: '次数',
        minInterval: 1,
        axisLabel: { color: ink[3] },
        splitLine: { lineStyle: { type: 'dashed' as const, color: ink[5] } },
      },
      yAxis: {
        type: 'category' as const,
        data: categories,
        inverse: true as const,
        axisLabel: {
          color: ink[3],
          overflow: 'truncate' as const,
          width: gridLeft - 16,
          formatter: (v: string) => v,
        },
        axisTick: { show: false },
      },
      series: [
        {
          type: 'bar' as const,
          data: counts.map((count, idx) => ({ value: count, itemStyle: { color: colors[idx] } })),
          barMaxWidth: 22,
          label: {
            show: true,
            position: 'right' as const,
            formatter: ({ value }: { value?: number }) => `${value ?? 0}`,
          },
        },
      ],
    };
    return { option, innerChartHeight };
  }, [report]);

  const percentilesView = useMemo(() => {
    const p = report.team_percentiles || {};
    const rows: Array<{
      metricKey:
        | 'team_percentile_session_count'
        | 'team_percentile_ai_active_hours'
        | 'team_percentile_ai_commits_per_active_hour';
      bundle?: { p10?: number | null; p25?: number | null; p50?: number | null; p75?: number | null; p90?: number | null } | null;
    }> = [
      { metricKey: 'team_percentile_session_count', bundle: p.session_count },
      { metricKey: 'team_percentile_ai_active_hours', bundle: p.ai_active_hours },
      { metricKey: 'team_percentile_ai_commits_per_active_hour', bundle: p.ai_commits_per_active_hour },
    ];
    return rows;
  }, [report]);

  const insightLine = useMemo(() => {
    const users = report.active_user_count ?? 0;
    const sessions = report.total_session_count ?? 0;
    const dist = report.team_difficulty_dist || { '1': 0, '2': 0, '3': 0, '4': 0, '5': 0 };
    const highDiff =
      sessions > 0
        ? (((dist['4'] ?? 0) + (dist['5'] ?? 0)) / sessions) * 100
        : 0;
    const watchCount = Object.values(report.watchlist_summary || {}).reduce(
      (s, arr) => s + (arr?.length ?? 0),
      0,
    );
    const disagree =
      report.judge_disagreement_ratio == null
        ? null
        : report.judge_disagreement_ratio * 100;
    const agreePct = disagree == null ? '—' : `${(100 - disagree).toFixed(0)}%`;
    return `窗口内 ${users} 人活跃、${sessions} 个会话；高难度（≥4）会话约占 ${highDiff.toFixed(0)}%。Watchlist 命中 ${watchCount} 人次。双 judge 一致率 ${agreePct}。`;
  }, [report]);

  const watchlist = report.watchlist_summary || {};

  const displayByCode = useMemo(() => {
    const m = new Map<string, string>();
    for (const u of report.users) {
      m.set(u.user_code, employeeName(u.user_display, u.user_code));
    }
    return m;
  }, [report.users]);

  const watchTableRows = useMemo(() => {
    const rows: WatchlistTableRow[] = [];
    for (const [flag, codes] of Object.entries(watchlist)) {
      for (const code of codes) {
        rows.push({
          key: `${flag}-${code}`,
          flag,
          user_code: code,
          triggerLabel: WATCHLIST_META[flag]?.label ?? flag,
          employeeDisplay: displayByCode.get(code) ?? code,
        });
      }
    }
    rows.sort((a, b) => {
      const byTrigger = a.triggerLabel.localeCompare(b.triggerLabel, 'zh-CN');
      if (byTrigger !== 0) return byTrigger;
      return a.employeeDisplay.localeCompare(b.employeeDisplay, 'zh-CN');
    });
    return rows;
  }, [watchlist, displayByCode]);

  const watchFlagOptions = useMemo(
    () =>
      Object.keys(watchlist)
        .filter((f) => (watchlist[f]?.length ?? 0) > 0)
        .map((f) => ({ value: f, label: WATCHLIST_META[f]?.label ?? f }))
        .sort((a, b) => a.label.localeCompare(b.label, 'zh-CN')),
    [watchlist],
  );

  const [watchFlagFilter, setWatchFlagFilter] = useState<string | undefined>();
  const [watchEmployeeKeyword, setWatchEmployeeKeyword] = useState('');
  const [watchTablePage, setWatchTablePage] = useState(1);

  const filteredWatchRows = useMemo(() => {
    const q = watchEmployeeKeyword.trim().toLowerCase();
    return watchTableRows.filter((row) => {
      if (watchFlagFilter && row.flag !== watchFlagFilter) return false;
      if (!q) return true;
      return (
        row.employeeDisplay.toLowerCase().includes(q) || row.user_code.toLowerCase().includes(q)
      );
    });
  }, [watchTableRows, watchFlagFilter, watchEmployeeKeyword]);

  useEffect(() => {
    setWatchTablePage(1);
  }, [watchFlagFilter]);

  useEffect(() => {
    const pages = Math.max(1, Math.ceil(filteredWatchRows.length / WATCHLIST_TABLE_PAGE_SIZE));
    setWatchTablePage((p) => Math.min(p, pages));
  }, [filteredWatchRows.length]);

  const watchColumns = useMemo(
    () => [
      {
        title: '触发类型',
        key: 'trigger',
        width: '42%',
        render: (_: unknown, row: WatchlistTableRow) => (
          <Tooltip title={WATCHLIST_META[row.flag]?.help ?? '无说明'} overlayStyle={{ maxWidth: 360 }}>
            <Tag color={watchlistTagColor(row.flag)} style={{ cursor: 'help' }}>
              {row.triggerLabel}
            </Tag>
          </Tooltip>
        ),
      },
      {
        title: '员工姓名｜工号',
        dataIndex: 'employeeDisplay',
        key: 'employee',
        render: (text: string, row: WatchlistTableRow) => (
          <Button
            type="link"
            size="small"
            style={{ padding: 0, height: 'auto' }}
            onClick={() => onPickUser?.(row.user_code)}
          >
            {text}
          </Button>
        ),
      },
    ],
    [onPickUser],
  );

  const judgePct =
    report.judge_disagreement_ratio == null
      ? '—'
      : `${(report.judge_disagreement_ratio * 100).toFixed(1)}%`;

  const kpis = [
    {
      key: 'users',
      title: <MetricLabel name="active_user_count" />,
      value: String(report.active_user_count ?? 0),
      hint: '窗口内有 AI 会话的员工数',
    },
    {
      key: 'sessions',
      title: <MetricLabel name="total_session_count" />,
      value: String(report.total_session_count ?? 0),
      hint: `已审计 ${report.audited_count} / ${report.total_count}`,
    },
    {
      key: 'hours',
      title: <MetricLabel name="total_active_hours" />,
      value: (report.total_active_hours ?? 0).toLocaleString(undefined, { maximumFractionDigits: 1 }),
      hint: '窗口内累计 AI 协作时长（小时）',
    },
    {
      key: 'git',
      title: <MetricLabel name="total_ai_commit" />,
      value: String(report.total_ai_commit ?? 0),
      hint: `双 judge 不一致率 ${judgePct}`,
    },
  ];

  const chartTitles = [
    { key: 'grade', title: <span>等级分布</span>, option: gradeOption },
    { key: 'diff', title: <MetricLabel name="team_difficulty_dist" />, option: diffOption },
    { key: 'mode', title: <MetricLabel name="team_mode_dist" />, option: modeOption },
  ];

  const compareLine = useMemo(() => {
    if (!compareReport) return null;
    const curH = report.total_active_hours ?? 0;
    const prevH = compareReport.total_active_hours ?? 0;
    const curS = report.total_session_count ?? 0;
    const prevS = compareReport.total_session_count ?? 0;
    const fmtDelta = (cur: number, prev: number) => {
      if (prev === 0) return cur === 0 ? '持平' : '新增';
      const pct = Math.round(((cur - prev) / prev) * 100);
      if (pct === 0) return '持平';
      return pct > 0 ? `+${pct}%` : `${pct}%`;
    };
    return `对比上一等长窗口（${compareReport.window_from} ~ ${compareReport.window_to}）：协作时长 ${fmtDelta(Number(curH), Number(prevH))}，会话数 ${fmtDelta(curS, prevS)}。`;
  }, [compareReport, report]);

  return (
    <div>
      <Paragraph type="secondary" style={{ marginBottom: 12, fontSize: 13 }}>
        {insightLine}
      </Paragraph>
      {compareLine && (
        <Paragraph type="secondary" style={{ marginTop: -4, marginBottom: 12, fontSize: 12 }}>
          {compareLine}
        </Paragraph>
      )}
      {report.team_narrative && (
        <div style={panelStyle}>
          <div style={sectionHeadStyle}>团队总评（AI 生成）</div>
          <div style={{ padding: '12px 16px 8px' }}>
            {([
              ['总体', 'overview'],
              ['亮点', 'highlights'],
              ['风险', 'risks'],
              ['建议', 'recommendations'],
            ] as const).map(([t, k]) => (
              <Paragraph key={k} style={{ marginBottom: 8, fontSize: 13 }}>
                <Text strong>{t}：</Text>
                {report.team_narrative![k]}
              </Paragraph>
            ))}
          </div>
        </div>
      )}
      <div style={panelStyle}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))' }}>
          {kpis.map((kpi, i) => (
            <KpiCell key={kpi.key} title={kpi.title} value={kpi.value} hint={kpi.hint} showDivider={i < kpis.length - 1} />
          ))}
        </div>
      </div>

      <div style={panelStyle}>
        {toolBreakdownChart ? (
          <>
            <div style={sectionHeadStyle}>
              <MetricLabel name="team_tool_breakdown" />
            </div>
            <div style={{ padding: '12px 16px 4px', maxHeight: 160, overflowY: 'auto' }}>
              <ReactECharts
                option={toolBreakdownChart.option}
                style={{ height: toolBreakdownChart.innerChartHeight }}
              />
            </div>
            <div style={{ borderBottom: '1px solid var(--am-border-subtle)', margin: '0 16px' }} />
          </>
        ) : (
          <div
            style={{
              ...sectionHeadStyle,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 12,
            }}
          >
            <MetricLabel name="team_tool_breakdown" />
            <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
              暂无 Slash Commands 数据
            </Text>
          </div>
        )}

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
          {chartTitles.map((chart, i) => (
            <div
              key={chart.key}
              style={{
                padding: '12px 8px 16px',
                ...(i < chartTitles.length - 1 ? cellDivider : {}),
              }}
            >
              <Text strong style={{ fontSize: 12, color: 'var(--am-ink-3)', display: 'block', padding: '0 8px 8px' }}>
                {chart.title}
              </Text>
              <ReactECharts option={chart.option} style={{ height: 240 }} />
            </div>
          ))}
        </div>

      </div>

      <div style={{ ...panelStyle, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', alignItems: 'stretch' }}>
        <div style={{ ...cellDivider, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
          <div style={sectionHeadStyle}>
            <MetricLabel name="team_percentiles" />
          </div>
          <div style={{ padding: '12px 16px 16px', flex: 1, display: 'flex', flexDirection: 'column' }}>
            {(report.active_user_count ?? 0) <= 1 && (
              <Text type="secondary" style={{ display: 'block', marginBottom: 8, fontSize: 12 }}>
                当前窗口仅 {(report.active_user_count ?? 0)} 名活跃员工，各分位列数值相同属正常。
              </Text>
            )}
            <Table<(typeof percentilesView)[number]>
              size="small"
              rowKey="metricKey"
              dataSource={percentilesView}
              pagination={false}
              scroll={{ x: 460 }}
              columns={[
                {
                  title: '指标',
                  key: 'metric',
                  width: 160,
                  render: (_, row) => <MetricLabel name={row.metricKey} />,
                },
                {
                  title: 'P10',
                  key: 'p10',
                  width: 60,
                  align: 'center',
                  render: (_, row) => <span style={NUM_STYLE}>{fmtP(row.bundle?.p10)}</span>,
                },
                {
                  title: 'P25',
                  key: 'p25',
                  width: 60,
                  align: 'center',
                  render: (_, row) => <span style={NUM_STYLE}>{fmtP(row.bundle?.p25)}</span>,
                },
                {
                  title: 'P50',
                  key: 'p50',
                  width: 60,
                  align: 'center',
                  render: (_, row) => <span style={NUM_STYLE}>{fmtP(row.bundle?.p50)}</span>,
                },
                {
                  title: 'P75',
                  key: 'p75',
                  width: 60,
                  align: 'center',
                  render: (_, row) => <span style={NUM_STYLE}>{fmtP(row.bundle?.p75)}</span>,
                },
                {
                  title: 'P90',
                  key: 'p90',
                  width: 60,
                  align: 'center',
                  render: (_, row) => <span style={NUM_STYLE}>{fmtP(row.bundle?.p90)}</span>,
                },
              ]}
            />
            <Paragraph type="secondary" style={{ marginTop: 'auto', paddingTop: 10, marginBottom: 0, fontSize: 12 }}>
              团队基线是 watchlist 触发与相对化分位的参照，不与外部基准对比。
            </Paragraph>
          </div>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
          <div style={{ ...sectionHeadStyle, borderBottom: '1px solid var(--am-border-subtle)' }}>
            <MetricLabel name="watchlist_summary" />
          </div>
          <div style={{ padding: '12px 16px 16px', flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
            {watchTableRows.length === 0 ? (
              <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: 200 }}>
                <Empty description="无触发" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              </div>
            ) : (
              <>
                <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' }}>
                  <Select
                    allowClear
                    placeholder="触发类型"
                    size="small"
                    style={{ width: 140, flexShrink: 0 }}
                    options={watchFlagOptions}
                    value={watchFlagFilter}
                    onChange={(v) => setWatchFlagFilter(v)}
                  />
                  <Input.Search
                    allowClear
                    size="small"
                    placeholder="员工姓名 / 工号"
                    style={{ flex: '1 1 120px', minWidth: 0 }}
                    value={watchEmployeeKeyword}
                    onChange={(e) => setWatchEmployeeKeyword(e.target.value)}
                  />
                </div>
                {filteredWatchRows.length === 0 ? (
                  <Empty description="无匹配行" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                ) : (
                  <Table<WatchlistTableRow>
                    size="small"
                    pagination={{
                      current: watchTablePage,
                      pageSize: WATCHLIST_TABLE_PAGE_SIZE,
                      total: filteredWatchRows.length,
                      showSizeChanger: false,
                      size: 'small',
                      onChange: (p) => setWatchTablePage(p),
                    }}
                    columns={watchColumns}
                    dataSource={filteredWatchRows}
                    rowKey="key"
                    scroll={{ y: 260 }}
                  />
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function KpiCell({
  title,
  value,
  hint,
  showDivider,
}: {
  title: React.ReactNode;
  value: string;
  hint?: string;
  showDivider?: boolean;
}) {
  return (
    <div style={{ padding: '16px 18px', ...(showDivider ? cellDivider : {}) }}>
      <Statistic
        title={title}
        value={value}
        valueStyle={{
          fontSize: 'var(--am-fs-2xl)',
          fontWeight: 680,
          letterSpacing: '-0.02em',
          lineHeight: 1.12,
          color: 'var(--am-ink)',
          fontVariantNumeric: 'tabular-nums',
        }}
      />
      {hint && (
        <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 6, lineHeight: 1.4 }}>
          {hint}
        </Text>
      )}
    </div>
  );
}

function fmtP(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return '—';
  if (Math.abs(v) < 0.01) return v.toFixed(3);
  return v.toFixed(2);
}
