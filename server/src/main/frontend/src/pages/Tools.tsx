import { useEffect, useMemo, useState } from 'react';
import { Card, DatePicker, Space, Spin, Table } from 'antd';
import dayjs, { Dayjs } from 'dayjs';
import isoWeek from 'dayjs/plugin/isoWeek';
import ReactECharts from 'echarts-for-react';
import { fetchToolStats } from '../api/client';
import type { ToolStat } from '../api/types';
import { categoryAxisGridLeft } from '../utils/chartAxis';
import { ink, semantic } from '../styles/tokens';
import { NUM_STYLE } from '../utils/table';

const { RangePicker } = DatePicker;

dayjs.extend(isoWeek);

function defaultRange(): [Dayjs, Dayjs] {
  const monday = dayjs().isoWeekday(1).startOf('day');
  const sunday = monday.add(6, 'day');
  return [monday, sunday];
}

type ToolsProps = {
  from?: string;
  to?: string;
  embedded?: boolean;
};

export default function Tools({ from: fromProp, to: toProp, embedded }: ToolsProps = {}) {
  const [range, setRange] = useState<[Dayjs, Dayjs]>(defaultRange);
  const [data, setData] = useState<ToolStat[]>([]);
  const [loading, setLoading] = useState(false);

  const from = fromProp ?? range[0].format('YYYY-MM-DD');
  const to = toProp ?? range[1].format('YYYY-MM-DD');

  useEffect(() => {
    setLoading(true);
    fetchToolStats({ from, to, limit: 30 })
      .then(setData)
      .finally(() => setLoading(false));
  }, [from, to]);

  const option = useMemo(() => {
    const labels = data.map((d) => d.tool_name);
    // 空态：无命令数据时渲染居中占位字，避免只剩坐标轴空架子（与 ModelsTools 占比图同款）
    if (labels.length === 0) {
      return {
        graphic: {
          type: 'text',
          left: 'center',
          top: 'middle',
          style: { text: '暂无数据', fill: ink[3], fontSize: 14 },
        },
        xAxis: { type: 'value', show: false },
        yAxis: { type: 'category', show: false, data: [] },
        series: [],
      };
    }
    return {
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      grid: { left: categoryAxisGridLeft(labels), right: 20, top: 16, bottom: 30 },
      xAxis: { type: 'value' },
      yAxis: {
        type: 'category',
        data: [...labels].reverse(),
        axisLabel: { fontSize: 12 },
      },
      series: [
        {
          name: '次数',
          type: 'bar',
          data: data.map((d) => d.count).reverse(),
          itemStyle: { color: semantic.brand.base },
          barMaxWidth: 18,
        },
      ],
    };
  }, [data]);

  const chartNode = (
    <Spin spinning={loading}>
      <ReactECharts option={option} style={{ height: 480 }} notMerge lazyUpdate />
    </Spin>
  );

  const tableNode = (
    <Table<ToolStat>
      rowKey="tool_name"
      size="small"
      dataSource={data}
      loading={loading}
      pagination={false}
      locale={{ emptyText: '所选时间窗内暂无命令调用' }}
      scroll={{ x: 580 }}
      columns={[
        { title: '排名', width: 60, onCell: () => ({ style: NUM_STYLE }), render: (_, __, i) => i + 1 },
        { title: '命令', dataIndex: 'tool_name', ellipsis: true },
        { title: '次数', dataIndex: 'count', width: 120, onCell: () => ({ style: NUM_STYLE }) },
        { title: '员工数', dataIndex: 'user_count', width: 100, onCell: () => ({ style: NUM_STYLE }) },
        { title: '会话数', dataIndex: 'session_count', width: 100, onCell: () => ({ style: NUM_STYLE }) },
      ]}
    />
  );

  // 嵌入态（ModelsTools 已外包「Slash Commands Top」卡）：脱掉内层卡壳与重复标题，只渲图+表
  if (embedded) {
    return (
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {chartNode}
        {tableNode}
      </Space>
    );
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title={
          <Space>
            <span>Slash Commands 排行</span>
            <RangePicker
              value={range}
              onChange={(v) => v && setRange([v[0]!, v[1]!])}
              allowClear={false}
            />
          </Space>
        }
      >
        {chartNode}
      </Card>

      <Card size="small" title="原始数据">
        {tableNode}
      </Card>
    </Space>
  );
}
