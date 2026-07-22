import { useMemo, useState } from 'react';
import { Card, DatePicker, Space, Tooltip, Typography } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import TrendCard from './Attribution/TrendCard';
import PivotCard from './Attribution/PivotCard';
import CommitDrawer, { type CommitDrillRequest } from './Attribution/CommitDrawer';
import { ATTR_HELP } from './Attribution/constants';

const { Text } = Typography;
const { RangePicker } = DatePicker;

/** 默认窗口 = 最近 30 天（含今日），与服务端 /admin/attribution 缺省窗口一致。 */
function defaultRange(): [Dayjs, Dayjs] {
  return [dayjs().subtract(29, 'day').startOf('day'), dayjs().startOf('day')];
}

/**
 * AI 产出归因分析主页面：顶部时间窗 + 趋势卡（B/A 堆叠柱 + 占比线）+ 交叉透视卡；
 * commit 明细 Drawer 全页共用一个实例，透视卡的单元格/行点击都落到这里。
 *
 * 口径（必须讲清楚，详见 Attribution/constants.ts 的 ATTR_HELP）：
 * B 档 = trailer 确定 AI 产出（误报≈0）；A 档 = 会话窗口 ±30min 疑似（上限口径，不得当结论）；
 * B/A 互斥、单一归属（任意维度组合求和 = 总数）；merge commit 全口径排除。
 */
export default function Attribution() {
  const [range, setRange] = useState<[Dayjs, Dayjs]>(defaultRange);
  const [drill, setDrill] = useState<CommitDrillRequest | null>(null);
  const [userNames, setUserNames] = useState<Record<string, string>>({});

  const params = useMemo(
    () => ({
      from: range[0].format('YYYY-MM-DD'),
      to: range[1].format('YYYY-MM-DD'),
    }),
    [range],
  );

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" styles={{ body: { padding: '12px 16px' } }}>
        <div className="am-toolbar">
          <Text type="secondary">时间窗：</Text>
          <RangePicker
            value={range}
            onChange={(v) => v && v[0] && v[1] && setRange([v[0], v[1]])}
            allowClear={false}
            disabledDate={(d) => d.isAfter(dayjs(), 'day')}
          />
          <span className="am-toolbar-spacer" />
          <Text type="secondary">
            B 档 = trailer 确定 AI 产出；A 档 = 会话窗口 ±30 分钟疑似（上限口径，读数勿当结论）
          </Text>
          <Tooltip
            title={`${ATTR_HELP.tier_b.help} ${ATTR_HELP.tier_a.help} merge commit 全口径排除。`}
            overlayStyle={{ maxWidth: 420 }}
          >
            <QuestionCircleOutlined style={{ color: 'var(--am-ink-3)', cursor: 'help' }} />
          </Tooltip>
        </div>
      </Card>

      <TrendCard from={params.from} to={params.to} />

      <PivotCard
        from={params.from}
        to={params.to}
        onDrill={setDrill}
        onUserNames={setUserNames}
      />

      <CommitDrawer
        open={!!drill}
        req={drill}
        from={params.from}
        to={params.to}
        userNames={userNames}
        onClose={() => setDrill(null)}
      />
    </Space>
  );
}
