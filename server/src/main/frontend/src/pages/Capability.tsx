import { useMemo, useState } from 'react';
import { Card, DatePicker, Space, Tabs, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import SkillTab from './Capability/SkillTab';
import PluginTab from './Capability/PluginTab';
import UserDrawer from './Capability/UserDrawer';

const { Text } = Typography;
const { RangePicker } = DatePicker;

/** 默认窗口 = 最近 30 天（含今日），与服务端 /admin/capability 缺省窗口一致。 */
function defaultRange(): [Dayjs, Dayjs] {
  return [dayjs().subtract(29, 'day').startOf('day'), dayjs().startOf('day')];
}

/**
 * 能力使用分析主页面：一页两 tab（Skill / 插件 MCP），顶部时间窗两 tab 共享；
 * 下钻 Drawer（员工能力明细）也共用同一个实例，热力图点击 / 员工 Select 都落到这里。
 */
export default function Capability() {
  const [range, setRange] = useState<[Dayjs, Dayjs]>(defaultRange);
  const [drawerUser, setDrawerUser] = useState<{ userCode: string; displayName: string } | null>(
    null,
  );

  const params = useMemo(
    () => ({
      from: range[0].format('YYYY-MM-DD'),
      to: range[1].format('YYYY-MM-DD'),
    }),
    [range],
  );

  const pickUser = (userCode: string, displayName: string) =>
    setDrawerUser({ userCode, displayName });

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
          <Text type="secondary">默认最近 30 天；两个 Tab 共用同一时间窗</Text>
        </div>
      </Card>

      <Tabs
        defaultActiveKey="skill"
        items={[
          {
            key: 'skill',
            label: 'Skill 使用分析',
            children: <SkillTab from={params.from} to={params.to} onPickUser={pickUser} />,
          },
          {
            key: 'plugin',
            label: '插件（MCP）使用分析',
            children: <PluginTab from={params.from} to={params.to} onPickUser={pickUser} />,
          },
        ]}
      />

      <UserDrawer
        open={!!drawerUser}
        userCode={drawerUser?.userCode ?? null}
        displayName={drawerUser?.displayName ?? ''}
        from={params.from}
        to={params.to}
        onClose={() => setDrawerUser(null)}
      />
    </Space>
  );
}
