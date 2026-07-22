import { useEffect, useState } from 'react';
import { Alert, Drawer, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate } from 'react-router-dom';
import { fetchAttributionCommits } from '../../api/client';
import type { AttributionCommitRow, PageDto } from '../../api/types';
import { employeeName, formatTime, modelLabel } from '../../utils/format';
import { EMPTY_DASH, NUM_STYLE } from '../../utils/table';
import AttrLabel from './AttrLabel';
import { COMMITS_PAGE_SIZE, TIER_META } from './constants';

const { Text } = Typography;

/** 透视单元格/行 → 明细抽屉的下钻请求：维度值已映射成 /commits 过滤参数。 */
export interface CommitDrillRequest {
  /** 切片描述（Drawer 标题），如「张三 · Claude Code · B 确定」。 */
  title: string;
  filters: {
    user_code?: string;
    project_name?: string;
    target_type?: string;
    model?: string;
    tier?: string;
  };
  /** 「未归因」空串维度值无法精确过滤时的近似说明（展示在抽屉头部）。 */
  approxNote?: string;
}

interface InnerProps {
  req: CommitDrillRequest;
  from: string;
  to: string;
  userNames: Record<string, string>;
}

/**
 * 抽屉内容体：独立组件 + Drawer destroyOnClose，每次打开自动回到第 1 页，
 * 无需手工重置分页状态。服务端分页（PageDto total/page/size）。
 */
function CommitTable({ req, from, to, userNames }: InnerProps) {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PageDto<AttributionCommitRow> | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    fetchAttributionCommits({ ...req.filters, from, to, page, size: COMMITS_PAGE_SIZE })
      .then((d) => {
        if (alive) setData(d);
      })
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [req, from, to, page]);

  const columns: ColumnsType<AttributionCommitRow> = [
    {
      title: '时间',
      dataIndex: 'commit_time',
      key: 'commit_time',
      width: 120,
      onCell: () => ({ style: NUM_STYLE }),
      render: (v: string) => formatTime(v, 'MM-DD HH:mm'),
    },
    {
      title: 'Commit',
      dataIndex: 'subject',
      key: 'subject',
      ellipsis: { showTitle: false },
      render: (v: string) => (
        <Tooltip title={v} placement="topLeft">
          <span>{v || EMPTY_DASH}</span>
        </Tooltip>
      ),
    },
    {
      title: 'Hash',
      dataIndex: 'commit_hash',
      key: 'commit_hash',
      width: 84,
      render: (v: string) => (
        <Text style={{ fontFamily: 'var(--am-font-mono, monospace)', fontSize: 12 }} title={v}>
          {(v || '').slice(0, 7)}
        </Text>
      ),
    },
    {
      title: '员工',
      dataIndex: 'user_code',
      key: 'user_code',
      width: 90,
      ellipsis: true,
      render: (code: string) => employeeName(userNames[code], code) || code,
    },
    {
      title: '±行数',
      key: 'lines',
      width: 110,
      align: 'right',
      onCell: () => ({ style: NUM_STYLE }),
      render: (_, r) => (
        <span>
          <Text style={{ color: 'var(--am-success-fg)', ...NUM_STYLE }}>+{r.lines_added}</Text>{' '}
          <Text style={{ color: 'var(--am-error-fg)', ...NUM_STYLE }}>-{r.lines_deleted}</Text>
        </span>
      ),
    },
    {
      title: '档位',
      dataIndex: 'tier',
      key: 'tier',
      width: 118,
      render: (tier: string, r) => {
        const meta = TIER_META[tier] ?? { label: tier, tag: 'default' };
        return (
          <span>
            <Tag color={meta.tag}>{meta.label}</Tag>
            {r.backfilled && (
              <Tooltip title="上线前历史回溯推算所得">
                <Tag style={{ marginInlineEnd: 0 }}>回溯</Tag>
              </Tooltip>
            )}
          </span>
        );
      },
    },
    {
      title: 'Trailer',
      dataIndex: 'trailer_kind',
      key: 'trailer_kind',
      width: 110,
      ellipsis: true,
      render: (v: string | null) => (v ? <Text style={{ fontSize: 12 }}>{v}</Text> : EMPTY_DASH),
    },
    {
      title: '模型',
      dataIndex: 'model',
      key: 'model',
      width: 130,
      ellipsis: true,
      render: (v: string | null) =>
        v ? <span title={v}>{modelLabel(v)}</span> : EMPTY_DASH,
    },
    {
      title: '归属会话',
      dataIndex: 'session_id',
      key: 'session_id',
      width: 96,
      onCell: () => ({ style: NUM_STYLE }),
      render: (id: number | null) =>
        id == null ? (
          EMPTY_DASH
        ) : (
          <a onClick={() => navigate(`/sessions/${id}`)}>#{id}</a>
        ),
    },
  ];

  return (
    <>
      {req.approxNote && (
        <Alert
          type="warning"
          showIcon
          message={req.approxNote}
          style={{ marginBottom: 12 }}
        />
      )}
      <Table<AttributionCommitRow>
        rowKey="commit_id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={data?.items ?? []}
        locale={{ emptyText: '该切片在所选时间窗内无 commit' }}
        scroll={{ x: 'max-content' }}
        pagination={{
          current: page + 1,
          pageSize: COMMITS_PAGE_SIZE,
          total: data?.total ?? 0,
          showSizeChanger: false,
          showTotal: (t) => `共 ${t} 个 commit`,
          size: 'small',
          onChange: (p) => setPage(p - 1),
        }}
      />
    </>
  );
}

interface Props {
  open: boolean;
  req: CommitDrillRequest | null;
  from: string;
  to: string;
  /** user_code → 显示名（来自 pivot user_names，可能不全；缺失时回退 user_code）。 */
  userNames: Record<string, string>;
  onClose: () => void;
}

/** commit 明细下钻 Drawer：透视卡的单元格/行点击都落到这里。 */
export default function CommitDrawer({ open, req, from, to, userNames, onClose }: Props) {
  return (
    <Drawer
      width={960}
      open={open}
      onClose={onClose}
      destroyOnClose
      title={
        <span>
          {req?.title} · <AttrLabel name="commit_drawer" />
          <Text type="secondary" style={{ fontSize: 12, marginLeft: 8, fontWeight: 400 }}>
            {from} ~ {to}
          </Text>
        </span>
      }
    >
      {req && <CommitTable req={req} from={from} to={to} userNames={userNames} />}
    </Drawer>
  );
}
