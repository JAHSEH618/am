import { useEffect, useMemo, useState } from 'react';
import { Card, Segmented, Space, Spin, Typography } from 'antd';
import ReactECharts from 'echarts-for-react';
import { fetchAttributionTrend } from '../../api/client';
import type { AttributionTrendPoint } from '../../api/types';
import AttrLabel from './AttrLabel';
import { buildAttributionTrendOption, type TrendMetric } from './charts';

const { Text } = Typography;

const CHART_HEIGHT = 320;

interface Props {
  from: string;
  to: string;
}

/**
 * 顶部趋势卡：B/A 双档堆叠柱（B 深 A 浅）+ 右轴 AI 占比线；指标可切 commit 数 / 净行数。
 * 存在 backfilled 日期时图内灰底 + 卡片角标注明「≤该日为上线前回溯推算」。
 */
export default function TrendCard({ from, to }: Props) {
  const [points, setPoints] = useState<AttributionTrendPoint[]>([]);
  const [loading, setLoading] = useState(false);
  const [metric, setMetric] = useState<TrendMetric>('commits');

  useEffect(() => {
    let alive = true;
    setLoading(true);
    fetchAttributionTrend({ from, to })
      .then((rows) => {
        if (alive) setPoints(rows);
      })
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [from, to]);

  const option = useMemo(() => buildAttributionTrendOption(points, metric), [points, metric]);

  /** 最晚的 backfilled 日期：回溯段通常是"上线前"的前缀区间，角标只报截止日即可。 */
  const lastBackfilled = useMemo(() => {
    let last: string | null = null;
    for (const p of points) if (p.backfilled) last = p.date;
    return last;
  }, [points]);

  return (
    <Card
      size="small"
      title={<AttrLabel name="attribution_trend" size="lg" />}
      extra={
        <Space size={12}>
          {lastBackfilled && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              ≤ {lastBackfilled} 为上线前回溯推算
            </Text>
          )}
          <Segmented
            size="small"
            value={metric}
            onChange={(v) => setMetric(v as TrendMetric)}
            options={[
              { value: 'commits', label: 'commit 数' },
              { value: 'lines', label: '净行数' },
            ]}
          />
        </Space>
      }
    >
      <Spin spinning={loading}>
        <ReactECharts
          option={option}
          style={{ height: CHART_HEIGHT, width: '100%' }}
          notMerge
          lazyUpdate
        />
      </Spin>
    </Card>
  );
}
