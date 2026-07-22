import { useEffect, useMemo, useState } from 'react';
import { Drawer, Empty, Table, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { fetchCapabilityUserItems } from '../../api/client';
import type { CapabilityUserItem } from '../../api/types';
import { employeeName } from '../../utils/format';
import { EMPTY_DASH, NUM_STYLE } from '../../utils/table';
import CapLabel from './CapLabel';
import { KIND_META, KIND_ORDER } from './constants';

const { Text } = Typography;

/** 树表行：sub_item 空串=一级汇总行，非空=挂在同 item 下的二级明细。 */
interface UserTreeRow {
  key: string;
  name: string;
  invoke_count: number;
  session_count: number;
  children?: UserTreeRow[];
}

/** 把平铺的 /user 明细（一级 sub_item='' + 二级 sub_item!=''）拼成 antd 树表结构。 */
function buildTree(rows: CapabilityUserItem[]): UserTreeRow[] {
  const parents = new Map<string, UserTreeRow>();
  const orphans: UserTreeRow[] = [];
  for (const r of rows) {
    if (!r.sub_item) {
      parents.set(r.item, {
        key: r.item,
        name: r.item,
        invoke_count: r.invoke_count,
        session_count: r.session_count,
      });
    }
  }
  for (const r of rows) {
    if (!r.sub_item) continue;
    const child: UserTreeRow = {
      key: `${r.item}::${r.sub_item}`,
      name: r.sub_item,
      invoke_count: r.invoke_count,
      session_count: r.session_count,
    };
    const parent = parents.get(r.item);
    if (parent) {
      (parent.children ??= []).push(child);
    } else {
      // 缺一级汇总行的兜底：直接平铺展示，别丢数据
      orphans.push({ ...child, name: `${r.item} / ${r.sub_item}` });
    }
  }
  return [...parents.values(), ...orphans];
}

const COLUMNS: ColumnsType<UserTreeRow> = [
  { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
  {
    title: <CapLabel name="invoke_count" />,
    dataIndex: 'invoke_count',
    key: 'invoke_count',
    width: 110,
    align: 'right',
    onCell: () => ({ style: NUM_STYLE }),
  },
  {
    title: <CapLabel name="session_count" />,
    dataIndex: 'session_count',
    key: 'session_count',
    width: 110,
    align: 'right',
    onCell: () => ({ style: NUM_STYLE }),
    render: (v: number) => (v > 0 ? v : EMPTY_DASH),
  },
];

interface Props {
  open: boolean;
  userCode: string | null;
  displayName: string;
  from: string;
  to: string;
  onClose: () => void;
}

/**
 * 员工能力使用下钻 Drawer：两 tab 共用同一个实例（挂在 Capability.tsx）。
 * 打开时拉 /admin/capability/user 全量明细，按 kind 分组各渲染一张树表。
 */
export default function UserDrawer({ open, userCode, displayName, from, to, onClose }: Props) {
  const [items, setItems] = useState<CapabilityUserItem[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open || !userCode) return;
    let alive = true;
    setLoading(true);
    setItems([]);
    fetchCapabilityUserItems({ user_code: userCode, from, to })
      .then((rows) => {
        if (alive) setItems(rows);
      })
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [open, userCode, from, to]);

  const groups = useMemo(() => {
    const byKind = new Map<string, CapabilityUserItem[]>();
    for (const it of items) {
      const arr = byKind.get(it.kind) ?? [];
      arr.push(it);
      byKind.set(it.kind, arr);
    }
    const rank = (k: string) => {
      const i = KIND_ORDER.indexOf(k);
      return i === -1 ? KIND_ORDER.length : i;
    };
    return [...byKind.entries()]
      .sort((a, b) => rank(a[0]) - rank(b[0]))
      .map(([kind, rows]) => ({ kind, tree: buildTree(rows) }));
  }, [items]);

  return (
    <Drawer
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
      title={
        <span>
          {employeeName(displayName, userCode)} · <CapLabel name="user_drawer" />
          <Text type="secondary" style={{ fontSize: 12, marginLeft: 8, fontWeight: 400 }}>
            {from} ~ {to}
          </Text>
        </span>
      }
    >
      {!loading && groups.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="该员工在所选时间窗内无能力使用记录"
        />
      ) : (
        groups.map(({ kind, tree }) => (
          <div key={kind} style={{ marginBottom: 24 }}>
            <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>
              {KIND_META[kind]?.label ?? kind}
            </Text>
            <Table<UserTreeRow>
              rowKey="key"
              size="small"
              loading={loading}
              columns={COLUMNS}
              dataSource={tree}
              pagination={false}
              scroll={{ x: 'max-content' }}
            />
          </div>
        ))
      )}
      {loading && groups.length === 0 && (
        <Table<UserTreeRow>
          rowKey="key"
          size="small"
          loading
          columns={COLUMNS}
          dataSource={[]}
          pagination={false}
        />
      )}
    </Drawer>
  );
}
