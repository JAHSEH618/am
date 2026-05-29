import { useEffect, useMemo, useState } from 'react';
import { Card, Col, DatePicker, Row, Space, Spin, Table, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs, { type Dayjs } from 'dayjs';
import isoWeek from 'dayjs/plugin/isoWeek';
import ReactECharts from 'echarts-for-react';
import { fetchModelDistribution, fetchModelHeatmap } from '../api/client';
import type { ModelDistribution, ModelHeatmap } from '../api/types';
import { formatTokens } from '../utils/format';
import Tools from './Tools';

/**
 * 模型与工具页（v2.1 Phase 2 增强）
 *
 * 三块：
 *   1. 模型 token 占比（堆叠柱：input/output 分色）+ 列表
 *   2. 模型 × 日期 token 燃烧热力图（窗口期内）
 *   3. Slash Commands Top（复用 Tools 组件）
 *
 * gz
 */
const { Text, Title } = Typography;
const { RangePicker } = DatePicker;

/** 柱状图仅保留 Token Top N，其余合并为「其他」，避免品类过多轴标签重叠 */
const MODEL_CHART_TOP_N = 12;
/** 左右卡片 body 最小高度对齐 */
const MODEL_PANEL_BODY_MIN_HEIGHT = 412;
/** 左侧图表固定可视高度 */
const MODEL_CHART_INNER_HEIGHT = 336;
/** 右侧表 tbody 滚动高度（与左侧图大致同高） */
const MODEL_TABLE_SCROLL_Y = 292;
/** 明细表分页大小 */
const MODEL_TABLE_PAGE_SIZE = 10;

dayjs.extend(isoWeek);

function defaultRange(): [Dayjs, Dayjs] {
  const monday = dayjs().isoWeekday(1).startOf('day');
  const sunday = monday.add(6, 'day');
  return [monday, sunday];
}

export default function ModelsTools() {
  const [range, setRange] = useState<[Dayjs, Dayjs]>(defaultRange);
  const [dist, setDist] = useState<ModelDistribution[]>([]);
  const [heatmap, setHeatmap] = useState<ModelHeatmap | null>(null);
  const [loading, setLoading] = useState(false);

  const params = useMemo(() => ({
    from: range[0].format('YYYY-MM-DD'),
    to: range[1].format('YYYY-MM-DD'),
  }), [range]);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      fetchModelDistribution(params),
      fetchModelHeatmap(params),
    ])
      .then(([d, hm]) => {
        if (!alive) return;
        setDist(d);
        setHeatmap(hm);
      })
      .finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, [params]);

  const distForChart = useMemo((): ModelDistribution[] => {
    if (dist.length === 0) return [];
    const sorted = [...dist].sort((a, b) => b.total_tokens - a.total_tokens);
    if (sorted.length <= MODEL_CHART_TOP_N) return sorted;
    const head = sorted.slice(0, MODEL_CHART_TOP_N);
    const tail = sorted.slice(MODEL_CHART_TOP_N);
    const pctSum = tail.reduce((s, d) => s + d.percent, 0);
    const other: ModelDistribution = {
      model: `其他（${tail.length} 个）`,
      input_tokens: tail.reduce((s, d) => s + d.input_tokens, 0),
      output_tokens: tail.reduce((s, d) => s + d.output_tokens, 0),
      total_tokens: tail.reduce((s, d) => s + d.total_tokens, 0),
      percent: Math.round(pctSum * 10) / 10,
      session_count: tail.reduce((s, d) => s + d.session_count, 0),
      user_count: tail.reduce((s, d) => s + d.user_count, 0),
    };
    return [...head, other];
  }, [dist]);

  /** 横向堆叠柱：Y 为模型名、易读；配合 TopN+其他控制条数 */
  const distOption = useMemo(() => {
    const rows = distForChart;
    if (rows.length === 0) {
      return {
        graphic: {
          type: 'text',
          left: 'center',
          top: 'middle',
          style: { text: '暂无数据', fill: '#94a3b8', fontSize: 14 },
        },
        xAxis: { type: 'value', show: false },
        yAxis: { type: 'category', show: false, data: [] },
        series: [],
      };
    }
    const models = rows.map((d) => d.model);
    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (items: { axisValue: string; seriesName: string; value: number }[]) => {
          if (!items?.length) return '';
          const lines = [String(items[0].axisValue)];
          let total = 0;
          for (const it of items) {
            lines.push(`${it.seriesName}: ${formatTokens(it.value)}`);
            total += it.value;
          }
          lines.push(`合计: ${formatTokens(total)}`);
          return lines.join('<br/>');
        },
      },
      legend: { data: ['input', 'output'], top: 0, left: 'center' },
      grid: { left: 4, right: 20, top: 32, bottom: 8, containLabel: true },
      xAxis: {
        type: 'value',
        axisLabel: { formatter: (v: number) => formatTokens(v), fontSize: 11 },
        splitLine: { lineStyle: { type: 'dashed', opacity: 0.6 } },
      },
      yAxis: {
        type: 'category',
        data: models,
        inverse: true,
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: {
          width: 138,
          overflow: 'truncate',
          ellipsis: '…',
          fontSize: 11,
          color: '#475569',
        },
      },
      series: [
        {
          name: 'input',
          type: 'bar',
          stack: 'tokens',
          barMaxWidth: 22,
          data: rows.map((d) => d.input_tokens),
          itemStyle: { color: '#2563eb' },
        },
        {
          name: 'output',
          type: 'bar',
          stack: 'tokens',
          barMaxWidth: 22,
          data: rows.map((d) => d.output_tokens),
          itemStyle: { color: '#10b981' },
        },
      ],
    };
  }, [distForChart]);

  const heatmapOption = useMemo(() => {
    if (!heatmap) return null;
    const max = heatmap.cells.reduce((m, c) => Math.max(m, c.tokens), 0);
    return {
      tooltip: {
        formatter: (p: { value: [number, number, number] }) => {
          const day = heatmap.days[p.value[0]];
          const model = heatmap.models[p.value[1]];
          return `${day} · ${model}<br/>${formatTokens(p.value[2])}`;
        },
      },
      grid: { left: 200, right: 30, top: 20, bottom: 60 },
      xAxis: {
        type: 'category', data: heatmap.days,
        axisLabel: { rotate: 20, fontSize: 11 }, splitArea: { show: true },
      },
      yAxis: {
        type: 'category', data: heatmap.models,
        axisLabel: { fontSize: 11 }, splitArea: { show: true },
      },
      visualMap: {
        min: 0,
        max: Math.max(max, 1),
        calculable: false,
        orient: 'horizontal',
        left: 'center',
        bottom: 0,
        inRange: { color: ['#eef2ff', '#6366f1', '#1e3a8a'] },
        formatter: (v: number) => formatTokens(v),
      },
      series: [{
        name: 'tokens',
        type: 'heatmap',
        data: heatmap.cells.map((c) => [c.day_index, c.model_index, c.tokens]),
        label: { show: false },
        emphasis: { itemStyle: { borderColor: '#1e293b', borderWidth: 1 } },
      }],
    };
  }, [heatmap]);

  const distColumns: ColumnsType<ModelDistribution> = [
    { title: '模型', dataIndex: 'model', key: 'model', ellipsis: true, width: 120 },
    {
      title: '占比',
      dataIndex: 'percent',
      key: 'percent',
      width: 84,
      sorter: (a, b) => a.percent - b.percent,
      defaultSortOrder: 'descend',
      render: (v: number) => `${v.toFixed(1)}%`,
    },
    { title: 'input', dataIndex: 'input_tokens', key: 'input_tokens', width: 88, render: (v: number) => formatTokens(v) },
    { title: 'output', dataIndex: 'output_tokens', key: 'output_tokens', width: 88, render: (v: number) => formatTokens(v) },
    {
      title: '总量',
      dataIndex: 'total_tokens',
      key: 'total_tokens',
      width: 88,
      sorter: (a, b) => a.total_tokens - b.total_tokens,
      render: (v: number) => formatTokens(v),
    },
    { title: '会话数', dataIndex: 'session_count', key: 'session_count', width: 72 },
    { title: '员工数', dataIndex: 'user_count', key: 'user_count', width: 72 },
  ];

  const cardBodyStyle = {
    minHeight: MODEL_PANEL_BODY_MIN_HEIGHT,
    display: 'flex' as const,
    flexDirection: 'column' as const,
  };

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
          <Text type="secondary" style={{ marginLeft: 8 }}>
            热力图与上方时间窗一致
          </Text>
        </Space>
      </Card>

      <Spin spinning={loading}>
        <Row gutter={16} align="stretch">
          <Col xs={24} md={14} style={{ display: 'flex' }}>
            <Card
              size="small"
              style={{ flex: 1, width: '100%' }}
              styles={{ body: { ...cardBodyStyle, padding: '12px 12px 10px' } }}
              title={<Title level={5} style={{ margin: 0 }}>模型 Token 占比</Title>}
            >
              <div style={{ height: MODEL_CHART_INNER_HEIGHT, flexShrink: 0 }}>
                <ReactECharts option={distOption} style={{ height: '100%', width: '100%' }} notMerge lazyUpdate />
              </div>
              {dist.length > MODEL_CHART_TOP_N ? (
                <Text type="secondary" style={{ fontSize: 12, lineHeight: 1.5, marginTop: 6, display: 'block' }}>
                  图表展示 Token 最高的 {MODEL_CHART_TOP_N} 个模型，长尾已合并为「其他」；完整列表见右侧表格。
                </Text>
              ) : null}
            </Card>
          </Col>
          <Col xs={24} md={10} style={{ display: 'flex' }}>
            <Card
              size="small"
              style={{ flex: 1, width: '100%' }}
              styles={{ body: { ...cardBodyStyle, padding: '8px 0 0' } }}
              title={<Title level={5} style={{ margin: 0 }}>模型分布明细</Title>}
            >
              <Table<ModelDistribution>
                rowKey="model"
                size="small"
                columns={distColumns}
                dataSource={dist}
                scroll={{ x: 'max-content', y: MODEL_TABLE_SCROLL_Y }}
                pagination={{
                  pageSize: MODEL_TABLE_PAGE_SIZE,
                  showSizeChanger: false,
                  hideOnSinglePage: true,
                  showTotal: (t) => `共 ${t} 个模型`,
                  size: 'small',
                  style: { margin: '8px 12px 4px' },
                }}
              />
            </Card>
          </Col>
        </Row>
      </Spin>

      {heatmapOption && (
        <Card size="small" title={<Title level={5} style={{ margin: 0 }}>模型 × 日期 Token 燃烧热力图（窗口期内）</Title>}>
          <div style={{ height: 360 }}>
            <ReactECharts option={heatmapOption} style={{ height: '100%' }} notMerge lazyUpdate />
          </div>
        </Card>
      )}

      <Card size="small" title={<Title level={5} style={{ margin: 0 }}>Slash Commands Top</Title>}>
        <Tools from={params.from} to={params.to} embedded />
      </Card>
    </Space>
  );
}
