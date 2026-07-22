import { useEffect, useMemo, useState } from 'react';
import { Card, Segmented, Select, Table, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { fetchAttributionPivot } from '../../api/client';
import type {
  AttributionColDim,
  AttributionPivot,
  AttributionRowDim,
  AttributionTier,
} from '../../api/types';
import { indigo } from '../../styles/tokens';
import { employeeName, modelLabel, targetTypeLabel } from '../../utils/format';
import { EMPTY_DASH, NUM_STYLE } from '../../utils/table';
import MetricLabel from '../Analysis/MetricLabel';
import AttrLabel from './AttrLabel';
import type { CommitDrillRequest } from './CommitDrawer';
import {
  ATTR_HELP,
  DIM_COMMIT_PARAM,
  DIM_OPTIONS,
  PIVOT_PAGE_SIZE,
  TIER_META,
  UNATTRIBUTED_LABEL,
} from './constants';

const { Text } = Typography;

type PivotMetric = 'commits' | 'lines' | 'ratio';

/** 单档累计：commit 数 + 净行数（added - deleted）。 */
interface TierAgg {
  commits: number;
  lines: number;
}
type TierRecord = Record<AttributionTier, TierAgg>;

function newTierRecord(): TierRecord {
  return { B: { commits: 0, lines: 0 }, A: { commits: 0, lines: 0 }, NONE: { commits: 0, lines: 0 } };
}

/** 透视行：total 跨列合计；cols 按 col_key 分桶（col=none 时只有 "*" 一桶）。 */
interface PivotRow {
  key: string;
  total: TierRecord;
  cols: Map<string, TierRecord>;
}

const fmt = (n: number) => n.toLocaleString();

/** AI 占比 =（B+A）/（B+A+NONE），按 commit 数口径；分母 0 → null（渲染 —）。 */
function aiRatio(rec: TierRecord): number | null {
  const ai = rec.B.commits + rec.A.commits;
  const denom = ai + rec.NONE.commits;
  return denom > 0 ? (ai / denom) * 100 : null;
}

interface Props {
  from: string;
  to: string;
  /** 单元格/行点击 → commit 明细 Drawer（挂在 Attribution.tsx）。 */
  onDrill: (req: CommitDrillRequest) => void;
  /** 把透视聚出的 user_code → 显示名回传给父级（Drawer 员工列复用）。 */
  onUserNames: (names: Record<string, string>) => void;
}

/**
 * 交叉透视卡：行/列维度 + 指标 Segmented + 四维组合筛选；
 * B/A 分列可见（col=none 时 B/A 独立成列；选列维度时格内「B x / A x」双色小字），
 * 行按合计降序；点击单元格/行下钻 commit 明细。
 */
export default function PivotCard({ from, to, onDrill, onUserNames }: Props) {
  const [rowDim, setRowDim] = useState<AttributionRowDim>('user');
  const [colDim, setColDim] = useState<AttributionColDim>('none');
  const [metric, setMetric] = useState<PivotMetric>('commits');
  const [fUser, setFUser] = useState<string | undefined>();
  const [fProject, setFProject] = useState<string | undefined>();
  const [fTool, setFTool] = useState<string | undefined>();
  const [fModel, setFModel] = useState<string | undefined>();

  const [pivot, setPivot] = useState<AttributionPivot | null>(null);
  const [loading, setLoading] = useState(false);
  /** 组合筛选候选值（时间窗内出现过的维度值，按 commit 量降序；空串「未归因」不可选）。 */
  const [candidates, setCandidates] = useState<Record<AttributionRowDim, string[]>>({
    user: [],
    project: [],
    tool: [],
    model: [],
  });
  const [names, setNames] = useState<Record<string, string>>({});

  // 候选值：两次无筛选透视（user×project / tool×model）把四个维度的值域一次聚全
  useEffect(() => {
    let alive = true;
    Promise.all([
      fetchAttributionPivot({ row: 'user', col: 'project', from, to }),
      fetchAttributionPivot({ row: 'tool', col: 'model', from, to }),
    ]).then(([up, tm]) => {
      if (!alive) return;
      const collect = (cells: AttributionPivot['cells'], side: 'row_key' | 'col_key') => {
        const counts = new Map<string, number>();
        for (const c of cells) {
          const k = c[side];
          if (k === '' || k === '*') continue;
          counts.set(k, (counts.get(k) ?? 0) + c.commit_count);
        }
        return [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([k]) => k);
      };
      setCandidates({
        user: collect(up.cells, 'row_key'),
        project: collect(up.cells, 'col_key'),
        tool: collect(tm.cells, 'row_key'),
        model: collect(tm.cells, 'col_key'),
      });
      if (up.user_names) setNames((prev) => ({ ...prev, ...up.user_names }));
    });
    return () => {
      alive = false;
    };
  }, [from, to]);

  // 主透视查询：维度/筛选/时间窗任一变更即带参重查
  useEffect(() => {
    let alive = true;
    setLoading(true);
    fetchAttributionPivot({
      row: rowDim,
      col: colDim,
      user_code: fUser,
      project_name: fProject,
      target_type: fTool,
      model: fModel,
      from,
      to,
    })
      .then((p) => {
        if (!alive) return;
        setPivot(p);
        if (p.user_names) setNames((prev) => ({ ...prev, ...p.user_names }));
      })
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [rowDim, colDim, fUser, fProject, fTool, fModel, from, to]);

  useEffect(() => {
    onUserNames(names);
  }, [names, onUserNames]);

  /** 维度值展示名（人→显示名、工具→Agent 名、模型→短名；空串由调用方渲染「未归因」）。 */
  const displayKey = (dim: AttributionRowDim, key: string): string => {
    if (dim === 'user') return employeeName(names[key], key) || key;
    if (dim === 'tool') return targetTypeLabel(key);
    if (dim === 'model') return modelLabel(key);
    return key;
  };

  const baseMeasure: 'commits' | 'lines' = metric === 'lines' ? 'lines' : 'commits';

  // cells → 行聚合 + 列 key 序（按 AI commit 量降序）
  const { rows, colKeys } = useMemo(() => {
    const map = new Map<string, PivotRow>();
    const colTotals = new Map<string, number>();
    for (const c of pivot?.cells ?? []) {
      let r = map.get(c.row_key);
      if (!r) {
        r = { key: c.row_key, total: newTierRecord(), cols: new Map() };
        map.set(c.row_key, r);
      }
      const tier: AttributionTier = c.tier ?? 'NONE';
      const net = c.lines_added - c.lines_deleted;
      r.total[tier].commits += c.commit_count;
      r.total[tier].lines += net;
      let bucket = r.cols.get(c.col_key);
      if (!bucket) {
        bucket = newTierRecord();
        r.cols.set(c.col_key, bucket);
      }
      bucket[tier].commits += c.commit_count;
      bucket[tier].lines += net;
      if (tier !== 'NONE') {
        colTotals.set(c.col_key, (colTotals.get(c.col_key) ?? 0) + c.commit_count);
      } else if (!colTotals.has(c.col_key)) {
        colTotals.set(c.col_key, 0);
      }
    }
    // 行按合计降序：col=none 按三档总量（B+A+NONE），选列维度按 AI 口径（B+A）
    const sortVal = (r: PivotRow) =>
      colDim === 'none'
        ? r.total.B[baseMeasure] + r.total.A[baseMeasure] + r.total.NONE[baseMeasure]
        : r.total.B[baseMeasure] + r.total.A[baseMeasure];
    const rowsSorted = [...map.values()].sort((a, b) => sortVal(b) - sortVal(a));
    const cols =
      colDim === 'none'
        ? []
        : [...colTotals.entries()].sort((a, b) => b[1] - a[1]).map(([k]) => k);
    return { rows: rowsSorted, colKeys: cols };
  }, [pivot, colDim, baseMeasure]);

  /** 下钻：把行/列维度值映射成 /commits 过滤参数；空串「未归因」降级为 tier=NONE 近似。 */
  const drill = (rowKey: string, colKey: string | null, tier?: AttributionTier) => {
    const filters: CommitDrillRequest['filters'] = {};
    if (fUser) filters.user_code = fUser;
    if (fProject) filters.project_name = fProject;
    if (fTool) filters.target_type = fTool;
    if (fModel) filters.model = fModel;
    const parts: string[] = [];
    let approx = false;
    const apply = (dim: AttributionRowDim, key: string) => {
      if (key === '') {
        approx = true;
        parts.push(`${DIM_OPTIONS.find((d) => d.value === dim)?.label ?? dim}${UNATTRIBUTED_LABEL}`);
        return;
      }
      filters[DIM_COMMIT_PARAM[dim]] = key;
      parts.push(displayKey(dim, key));
    };
    apply(rowDim, rowKey);
    if (colKey != null && colDim !== 'none') apply(colDim, colKey);
    let finalTier = tier;
    if (approx && !finalTier) finalTier = 'NONE';
    if (finalTier) {
      filters.tier = finalTier;
      parts.push(TIER_META[finalTier]?.label ?? finalTier);
    }
    onDrill({
      title: parts.join(' · '),
      filters,
      approxNote: approx
        ? '「未归因」是维度值为空的 commit，无法作为精确过滤条件：该维度条件已忽略'
          + `，改按档位近似查询（当前档位 = ${TIER_META[finalTier ?? 'NONE']?.label}）。`
          + '列表范围可能与单元格数字不完全一致。'
        : undefined,
    });
  };

  /** 行首维度值单元格：空串 → 灰色「未归因」。 */
  const renderRowKey = (key: string) =>
    key === '' ? (
      <Text style={{ color: 'var(--am-ink-4)' }}>{UNATTRIBUTED_LABEL}</Text>
    ) : (
      <span title={key}>{displayKey(rowDim, key)}</span>
    );

  /** 可点击数值格的公共 onCell：阻断冒泡避免同时触发行级下钻。 */
  const clickCell = (onClick: () => void) => ({
    onClick: (e: { stopPropagation: () => void }) => {
      e.stopPropagation();
      onClick();
    },
    style: { ...NUM_STYLE, cursor: 'pointer' },
  });

  /** 选列维度时的格内容：主值（AI 口径 = B+A）+「B x / A x」双色小字。 */
  const renderAiCell = (rec: TierRecord | undefined) => {
    if (!rec) return EMPTY_DASH;
    const main =
      metric === 'ratio'
        ? (() => {
            const r = aiRatio(rec);
            return r == null ? EMPTY_DASH : `${r.toFixed(1)}%`;
          })()
        : fmt(rec.B[baseMeasure] + rec.A[baseMeasure]);
    return (
      <span style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-end' }}>
        <span style={{ fontWeight: 600 }}>{main}</span>
        <span style={{ fontSize: 11, lineHeight: 1.3 }}>
          <span style={{ color: indigo[700] }}>B {fmt(rec.B[baseMeasure])}</span>
          <span style={{ color: 'var(--am-ink-4)' }}> / </span>
          <span style={{ color: indigo[400] }}>A {fmt(rec.A[baseMeasure])}</span>
        </span>
      </span>
    );
  };

  const columns = useMemo<ColumnsType<PivotRow>>(() => {
    const dimLabel = DIM_OPTIONS.find((d) => d.value === rowDim)?.label ?? rowDim;
    const first: ColumnsType<PivotRow>[number] = {
      title: dimLabel,
      dataIndex: 'key',
      key: 'key',
      fixed: 'left',
      width: 180,
      ellipsis: true,
      render: (_: unknown, r) => renderRowKey(r.key),
    };
    if (colDim === 'none') {
      const tierCol = (tier: AttributionTier, help: string): ColumnsType<PivotRow>[number] => ({
        title: <MetricLabel label={`${TIER_META[tier].label}`} help={help} />,
        key: tier,
        width: 110,
        align: 'right',
        onCell: (r) => clickCell(() => drill(r.key, null, tier)),
        render: (_: unknown, r) => {
          const v = r.total[tier][baseMeasure];
          if (tier === 'B')
            return <span style={{ color: indigo[600], fontWeight: 600 }}>{fmt(v)}</span>;
          if (tier === 'A')
            return <span style={{ color: indigo[400], fontWeight: 600 }}>{fmt(v)}</span>;
          return fmt(v);
        },
      });
      return [
        first,
        tierCol('B', ATTR_HELP.tier_b.help),
        tierCol('A', ATTR_HELP.tier_a.help),
        tierCol('NONE', '未命中任何归因规则的 commit（既无 AI trailer，也不在任何会话 ±30 分钟窗口内）。'),
        {
          title: '合计',
          key: 'total',
          width: 100,
          align: 'right',
          onCell: (r) => clickCell(() => drill(r.key, null)),
          render: (_: unknown, r) =>
            fmt(r.total.B[baseMeasure] + r.total.A[baseMeasure] + r.total.NONE[baseMeasure]),
        },
        {
          title: <AttrLabel name="ai_ratio" />,
          key: 'ratio',
          width: 110,
          align: 'right',
          onCell: () => ({ style: NUM_STYLE }),
          render: (_: unknown, r) => {
            const v = aiRatio(r.total);
            return v == null ? EMPTY_DASH : `${v.toFixed(1)}%`;
          },
        },
      ];
    }
    // 选了列维度：每个 col_key 一列 + 行合计（AI 口径）
    const colCols: ColumnsType<PivotRow> = colKeys.map((ck) => ({
      title:
        ck === '' ? (
          <Text style={{ color: 'var(--am-ink-4)' }}>{UNATTRIBUTED_LABEL}</Text>
        ) : (
          <span title={ck}>{displayKey(colDim, ck)}</span>
        ),
      key: `c:${ck}`,
      width: 130,
      align: 'right',
      ellipsis: true,
      onCell: (r) =>
        r.cols.get(ck) ? clickCell(() => drill(r.key, ck)) : { style: NUM_STYLE },
      render: (_: unknown, r) => renderAiCell(r.cols.get(ck)),
    }));
    return [
      first,
      ...colCols,
      {
        title: <MetricLabel label="合计（AI）" help="该行全部列的 AI 口径合计（B+A；占比指标下为整行占比）。" />,
        key: 'total',
        width: 120,
        align: 'right',
        onCell: (r) => clickCell(() => drill(r.key, null)),
        render: (_: unknown, r) => renderAiCell(r.total),
      },
    ];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rowDim, colDim, colKeys, metric, baseMeasure, names, fUser, fProject, fTool, fModel]);

  const filterSelect = (
    dim: AttributionRowDim,
    value: string | undefined,
    onChange: (v: string | undefined) => void,
    width: number,
  ) => (
    <Select
      size="small"
      allowClear
      showSearch
      placeholder={DIM_OPTIONS.find((d) => d.value === dim)?.label}
      style={{ width }}
      value={value}
      optionFilterProp="label"
      options={candidates[dim].map((k) => ({ value: k, label: displayKey(dim, k) }))}
      onChange={(v) => onChange(v ?? undefined)}
    />
  );

  return (
    <Card size="small" title={<AttrLabel name="pivot" size="lg" />}>
      <div className="am-toolbar" style={{ marginBottom: 12 }}>
        <Text type="secondary">行</Text>
        <Select
          size="small"
          style={{ width: 92 }}
          value={rowDim}
          options={DIM_OPTIONS}
          onChange={(v) => {
            setRowDim(v);
            if (colDim === v) setColDim('none');
          }}
        />
        <Text type="secondary">列</Text>
        <Select
          size="small"
          style={{ width: 120 }}
          value={colDim}
          options={[
            { value: 'none', label: '无（仅行）' },
            ...DIM_OPTIONS.filter((d) => d.value !== rowDim),
          ]}
          onChange={(v) => setColDim(v)}
        />
        <Segmented
          size="small"
          value={metric}
          onChange={(v) => setMetric(v as PivotMetric)}
          options={[
            { value: 'commits', label: 'AI commit 数' },
            { value: 'lines', label: 'AI 净行数' },
            { value: 'ratio', label: 'AI 占比' },
          ]}
        />
        <span className="am-toolbar-spacer" />
        <Text type="secondary">筛选</Text>
        {filterSelect('user', fUser, setFUser, 130)}
        {filterSelect('project', fProject, setFProject, 150)}
        {filterSelect('tool', fTool, setFTool, 130)}
        {filterSelect('model', fModel, setFModel, 170)}
      </div>

      <Table<PivotRow>
        rowKey="key"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        locale={{ emptyText: '所选时间窗 / 筛选组合内暂无 commit 归因数据' }}
        scroll={{ x: 'max-content' }}
        onRow={(r) => ({ onClick: () => drill(r.key, null), style: { cursor: 'pointer' } })}
        pagination={{
          pageSize: PIVOT_PAGE_SIZE,
          showSizeChanger: false,
          hideOnSinglePage: true,
          showTotal: (t) => `共 ${t} 行`,
          size: 'small',
        }}
      />
      <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
        单一归属：每个 commit 至多归属一个会话，任意维度组合求和 = 总数；B/A 互斥，读数请 B/A
        分开看（A 档为上限口径）；merge commit 全口径排除。点击单元格或行可查看 commit 明细。
      </Text>
    </Card>
  );
}
