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
  CapabilityRankingRow,
  CapabilityTrendPoint,
} from '../../api/types';
import { accent, indigo } from '../../styles/tokens';
import { employeeName } from '../../utils/format';
import { EMPTY_DASH, NUM_STYLE } from '../../utils/table';
import CapLabel from './CapLabel';
import { MATRIX_TOP_N } from './constants';
import { buildMatrixHeatmapOption, buildSkillTrendOption, matrixHeight } from './charts';

const { Text } = Typography;

const TREND_CHART_HEIGHT = 300;
const RANKING_PAGE_SIZE = 10;

interface Props {
  from: string;
  to: string;
  onPickUser: (userCode: string, displayName: string) => void;
}

/**
 * Skill 使用分析 tab：排行表（显式/NL 双色分列）→ 趋势堆叠柱 → 人×Skill 覆盖矩阵。
 * 下钻入口：矩阵热力图点击色块 + 矩阵卡片右上角员工 Select。
 */
export default function SkillTab({ from, to, onPickUser }: Props) {
  const [ranking, setRanking] = useState<CapabilityRankingRow[]>([]);
  const [trend, setTrend] = useState<CapabilityTrendPoint[]>([]);
  const [matrix, setMatrix] = useState<CapabilityMatrix | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      fetchCapabilityRanking({ kind: 'skill', from, to, limit: 50 }),
      fetchCapabilityTrend({ kind: 'skill', from, to }),
      fetchCapabilityMatrix({ kind: 'skill', from, to }),
    ])
      .then(([r, t, m]) => {
        if (!alive) return;
        setRanking(r);
        setTrend(t);
        setMatrix(m);
      })
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [from, to]);

  const columns = useMemo<ColumnsType<CapabilityRankingRow>>(
    () => [
      { title: '技能名', dataIndex: 'item', key: 'item', ellipsis: true },
      {
        title: <CapLabel name="explicit_count" />,
        dataIndex: 'explicit_count',
        key: 'explicit_count',
        width: 110,
        align: 'right',
        onCell: () => ({ style: NUM_STYLE }),
        sorter: (a, b) => (a.explicit_count ?? 0) - (b.explicit_count ?? 0),
        render: (v: number | null) =>
          v == null ? EMPTY_DASH : <span style={{ color: indigo[600], fontWeight: 600 }}>{v}</span>,
      },
      {
        title: <CapLabel name="nl_count" />,
        dataIndex: 'nl_count',
        key: 'nl_count',
        width: 100,
        align: 'right',
        onCell: () => ({ style: NUM_STYLE }),
        sorter: (a, b) => (a.nl_count ?? 0) - (b.nl_count ?? 0),
        render: (v: number | null) =>
          v == null
            ? EMPTY_DASH
            : <span style={{ color: accent.purple.base, fontWeight: 600 }}>{v}</span>,
      },
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
    [],
  );

  const trendOption = useMemo(() => buildSkillTrendOption(trend), [trend]);
  const heatmapOption = useMemo(
    () => (matrix ? buildMatrixHeatmapOption(matrix) : null),
    [matrix],
  );

  // 热力图点击色块 → 下钻该行员工（value = [itemIdx, userIdx, count]）
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
      <Card size="small" title={<CapLabel name="skill_ranking" size="lg" />}>
        <Table<CapabilityRankingRow>
          rowKey="item"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={ranking}
          locale={{ emptyText: '所选时间窗内暂无技能使用数据' }}
          scroll={{ x: 'max-content' }}
          pagination={{
            pageSize: RANKING_PAGE_SIZE,
            showSizeChanger: false,
            hideOnSinglePage: true,
            showTotal: (t) => `共 ${t} 个技能`,
            size: 'small',
          }}
        />
      </Card>

      <Card size="small" title={<CapLabel name="skill_trend" size="lg" />}>
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
            <CapLabel name="skill_matrix" size="lg" />
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
