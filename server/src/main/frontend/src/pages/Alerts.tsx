import { useEffect, useState } from 'react';
import { Card, DatePicker, Select, Space, Table, Tag } from 'antd';
import dayjs, { Dayjs } from 'dayjs';
import { fetchAlerts } from '../api/client';
import type { AgentAlert, PageDto } from '../api/types';
import { formatTime } from '../utils/format';

const { RangePicker } = DatePicker;

const TYPE_OPTIONS = [
  { label: '全部', value: '' },
  { label: '签名失败', value: 'SIGNATURE_INVALID' },
  { label: 'Nonce 重放', value: 'NONCE_REPLAY' },
  { label: '版本过期', value: 'AGENT_VERSION_EXPIRED' },
  { label: '二进制 Hash 不匹配', value: 'BINARY_HASH_MISMATCH' },
  { label: 'Token 篡改', value: 'TOKEN_TAMPER' },
];

const LEVEL_COLOR: Record<string, string> = {
  WARN: 'orange',
  ERROR: 'red',
};

export default function Alerts() {
  const [type, setType] = useState('');
  const [range, setRange] = useState<[Dayjs, Dayjs]>([
    dayjs().subtract(7, 'day'),
    dayjs(),
  ]);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [data, setData] = useState<PageDto<AgentAlert> | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    fetchAlerts({
      type: type || undefined,
      from: range[0].format('YYYY-MM-DD'),
      to: range[1].format('YYYY-MM-DD'),
      page,
      size,
    })
      .then(setData)
      .finally(() => setLoading(false));
  }, [type, range, page, size]);

  return (
    <Card
      size="small"
      title={
        <Space wrap>
          <span>异常告警</span>
          <Select
            value={type}
            onChange={(v) => { setType(v); setPage(0); }}
            options={TYPE_OPTIONS}
            style={{ width: 200 }}
          />
          <RangePicker
            value={range}
            onChange={(v) => v && setRange([v[0]!, v[1]!])}
            allowClear={false}
          />
        </Space>
      }
    >
      <Table<AgentAlert>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={data?.items ?? []}
        pagination={{
          current: page + 1,
          pageSize: size,
          total: data?.total ?? 0,
          showSizeChanger: true,
          onChange: (p, s) => { setPage(p - 1); setSize(s); },
        }}
        columns={[
          {
            title: '级别',
            dataIndex: 'alert_level',
            width: 80,
            render: (v) => <Tag color={LEVEL_COLOR[v] || 'default'}>{v}</Tag>,
          },
          { title: '类型', dataIndex: 'alert_type', width: 200, ellipsis: true },
          { title: '员工', dataIndex: 'user_display', width: 160, ellipsis: true },
          { title: 'Agent', dataIndex: 'agent_id', width: 200, ellipsis: true },
          { title: '消息', dataIndex: 'message', ellipsis: true },
          {
            title: '时间',
            dataIndex: 'event_time',
            width: 160,
            render: (v) => formatTime(v, 'YYYY-MM-DD HH:mm:ss'),
          },
        ]}
      />
    </Card>
  );
}
