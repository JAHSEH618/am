import { useEffect, useMemo, useState } from 'react';
import { Card, Select, Space, Spin, Table, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import ReactECharts from 'echarts-for-react';
import {
  fetchCapabilityMatrix,
  fetchCapabilityRanking,
  fetchCapabilityTrend,
} from '../../api/client';
import type {
  CapabilityMatrix,
  CapabilityRankingChild,
  CapabilityRankingRow,
  CapabilityTrendPoint,
} from '../../api/types';
import { employeeName } from '../../utils/format';
import { NUM_STYLE } from '../../utils/table';
import CapLabel from './CapLabel';
import { CAP_HELP, MATRIX_TOP_N } from './constants';
import { buildMatrixHeatmapOption, buildMcpTrendOption, matrixHeight } from './charts';

const { Text } = Typography;

const TREND_CHART_HEIGHT = 300;
const RANKING_PAGE_SIZE = 10;

interface Props {
  from: string;
  to: string;
  onPickUser: (userCode: string, displayName: string) => void;
}

/** 可展开排行表的二级明细列（mcp=tool / plugin_ns=技能）。 */
const CHILD_COLUMNS: ColumnsType<CapabilityRankingChild> = [
  { title: '明细', dataIndex: 'sub_item', key: 'sub_item', ellipsis: true },
  {
    title: CAP_HELP.invoke_count.label,
    dataIndex: 'invoke_count',
    key: 'invoke_count',
    width: 110,
    align: 'right',
    onCell: () => ({ style: NUM_STYLE }),
  },
  {
    title: CAP_HELP.session_count.label,
    dataIndex: 'session_count',
    key: 'session_count',
    width: 110,
    align: 'right',
    onCell: () => ({ style: NUM_STYLE }),
  },
];

/** 一级排行表（可展开）：mcp 与 plugin_ns 共用，只有首列标题不同。 */
function RankingTable({
  itemTitle,
  rows,
  loading,
  emptyText,
}: {
  itemTitle: string;
  rows: CapabilityRankingRow[];
  loading: boolean;
  emptyText: string;
}) {
  const columns = useMemo<ColumnsType<CapabilityRankingRow>>(
    () => [
      { title: itemTitle, dataIndex: 'item', key: 'item', ellipsis: true },
      {
        title: <CapLabel name="invoke_count" />,
        dataIndex: 'invoke_count',
        key: 'invoke_count',
        width: 100,
        align: 'right',
        onCell: () => ({ style: NUM_STYLE }),
        sorter: (a, b) => a.invoke_count - b.invoke_count,
        defaultSortOrder: 'descend',
      },
      {
        title: <CapLabel name="user_count" />,
        dataIndex: 'user_count',
        key: 'user_count',
        width: 100,
        align: 'right',
        onCell: () => ({ style: NUM_STYLE }),
        sorter: (a, b) => a.user_count - b.user_count,
      },
      {
        title: <CapLabel name="session_count" />,
        dataIndex: 'session_count',
        key: 'session_count',
        width: 100,
        align: 'right',
        onCell: () => ({ style: NUM_STYLE }),
        sorter: (a, b) => a.session_count - b.session_count,
      },
    ],
    [itemTitle],
  );

  return (
    <Table<CapabilityRankingRow>
      rowKey="item"
      size="small"
      loading={loading}
      columns={columns}
      dataSource={rows}
      locale={{ emptyText }}
      scroll={{ x: 'max-content' }}
      expandable={{
        rowExpandable: (row) => (row.children?.length ?? 0) > 0,
        expandedRowRender: (row) => (
          <Table<CapabilityRankingChild>
            rowKey="sub_item"
            size="small"
            columns={CHILD_COLUMNS}
            dataSource={row.children ?? []}
            pagination={false}
          />
        ),
      }}
      pagination={{
        pageSize: RANKING_PAGE_SIZE,
        showSizeChanger: false,
        hideOnSinglePage: true,
        showTotal: (t) => `共 ${t} 项`,
        size: 'small',
      }}
    />
  );
}

/**
 * 插件（MCP）使用分析 tab：MCP server 排行（展开 tool 明细）→ 命名空间技能排行（展开技能明细）
 * → MCP 趋势（单系列）→ 人×server 覆盖矩阵。下钻入口与 Skill tab 同款（热力图点击 + Select）。
 */
export default function PluginTab({ from, to, onPickUser }: Props) {
  const [mcpRanking, setMcpRanking] = useState<CapabilityRankingRow[]>([]);
  const [nsRanking, setNsRanking] = useState<CapabilityRankingRow[]>([]);
  const [trend, setTrend] = useState<CapabilityTrendPoint[]>([]);
  const [matrix, setMatrix] = useState<CapabilityMatrix | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      fetchCapabilityRanking({ kind: 'mcp', from, to, limit: 50 }),
      fetchCapabilityRanking({ kind: 'plugin_ns', from, to, limit: 50 }),
      fetchCapabilityTrend({ kind: 'mcp', from, to }),
      fetchCapabilityMatrix({ kind: 'mcp', from, to }),
    ])
      .then(([mcp, ns, t, m]) => {
        if (!alive) return;
        setMcpRanking(mcp);
        setNsRanking(ns);
        setTrend(t);
        setMatrix(m);
      })
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [from, to]);

  const trendOption = useMemo(() => buildMcpTrendOption(trend), [trend]);
  const heatmapOption = useMemo(
    () => (matrix ? buildMatrixHeatmapOption(matrix) : null),
    [matrix],
  );

  const heatmapEvents = useMemo(
    () => ({
      click: (p: { value?: number[] }) => {
        const userIdx = p?.value?.[1];
        if (userIdx == null || !matrix) return;
        const u = matrix.users[userIdx];
        if (u) onPickUser(u.user_code, u.display_name);
      },
    }),
    [matrix, onPickUser],
  );

  const matrixFolded = (matrix?.items.length ?? 0) > MATRIX_TOP_N;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" title={<CapLabel name="mcp_ranking" size="lg" />}>
        <RankingTable
          itemTitle="MCP Server"
          rows={mcpRanking}
          loading={loading}
          emptyText="所选时间窗内暂无 MCP 调用数据"
        />
      </Card>

      <Card size="small" title={<CapLabel name="plugin_ns_ranking" size="lg" />}>
        <RankingTable
          itemTitle="插件 Namespace"
          rows={nsRanking}
          loading={loading}
          emptyText="所选时间窗内暂无插件命名空间技能调用"
        />
      </Card>

      <Card size="small" title={<CapLabel name="mcp_trend" size="lg" />}>
        <Spin spinning={loading}>
          <ReactECharts
            option={trendOption}
            style={{ height: TREND_CHART_HEIGHT, width: '100%' }}
            notMerge
            lazyUpdate
          />
        </Spin>
      </Card>

      <Card
        size="small"
        title={
          <Space size={8}>
            <CapLabel name="mcp_matrix" size="lg" />
            {matrixFolded && (
              <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                仅展示 Top {MATRIX_TOP_N} / 共 {matrix!.items.length} 项
              </Text>
            )}
          </Space>
        }
        extra={
          <Select
            size="small"
            showSearch
            placeholder="选择员工下钻"
            style={{ width: 180 }}
            optionFilterProp="label"
            options={(matrix?.users ?? []).map((u) => ({
              value: u.user_code,
              label: employeeName(u.display_name, u.user_code),
            }))}
            onSelect={(code: string) => {
              const u = matrix?.users.find((x) => x.user_code === code);
              if (u) onPickUser(u.user_code, u.display_name);
            }}
          />
        }
      >
        <Spin spinning={loading}>
          {heatmapOption ? (
            <ReactECharts
              option={heatmapOption}
              style={{ height: matrixHeight(matrix), width: '100%' }}
              notMerge
              lazyUpdate
              onEvents={heatmapEvents}
            />
          ) : (
            <div style={{ height: 280 }} />
          )}
        </Spin>
      </Card>
    </Space>
  );
}
