import { Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { TablePaginationConfig } from 'antd/es/table/interface';
import type { ProjectGitCommit } from '../api/types';
import { formatTime } from '../utils/format';
import { GitCommitChangeCell } from './GitCommitChangeCell';

const { Text } = Typography;

/** Git 提交明细弹窗：表体固定高度（px），超出纵向滚动 */
export const GIT_COMMIT_MODAL_TABLE_SCROLL_Y = 420;

/** Git 提交明细弹窗：分页默认 20，可选 50 / 100 */
export const gitCommitModalPagination: TablePaginationConfig = {
  defaultPageSize: 20,
  pageSizeOptions: ['20', '50', '100'],
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`,
  hideOnSinglePage: false,
};

export type GitCommitColumnsOptions = {
  /** 员工视角跨仓库时展示仓库 URL */
  includeRepo?: boolean;
};

/**
 * 项目透视 / 员工数据共用：Git 提交明细弹框表格列（变更列 Popover 行为一致）
 * gz
 */
export function buildGitCommitTableColumns(opts: GitCommitColumnsOptions = {}): ColumnsType<ProjectGitCommit> {
  const { includeRepo = false } = opts;

  const repoColumn: ColumnsType<ProjectGitCommit>[number] = {
    title: '仓库',
    dataIndex: 'repo_url',
    key: 'repo_url',
    width: 200,
    ellipsis: true,
    render: (v: string | null | undefined) =>
      v ? (
        <Text ellipsis={{ tooltip: true }} style={{ maxWidth: 196 }}>
          {v}
        </Text>
      ) : (
        <Text type="secondary">—</Text>
      ),
  };

  const cols: ColumnsType<ProjectGitCommit> = [
    {
      title: '时间',
      dataIndex: 'commit_time',
      key: 'commit_time',
      width: 172,
      render: (v: string) => formatTime(v),
    },
    {
      title: 'Hash',
      dataIndex: 'commit_hash',
      key: 'commit_hash',
      width: 88,
      render: (h: string) => <Text code>{h?.slice(0, 7) || '—'}</Text>,
    },
    ...(includeRepo ? [repoColumn] : []),
    {
      title: '说明',
      dataIndex: 'message_subject',
      key: 'message_subject',
      ellipsis: true,
      render: (v: string | null) => v || <Text type="secondary">—</Text>,
    },
    {
      title: '分支',
      dataIndex: 'branch_name',
      key: 'branch_name',
      width: 120,
      ellipsis: true,
      render: (v: string | null) => v || '—',
    },
    {
      title: '变更',
      key: 'lines',
      width: 100,
      render: (_: unknown, row) => <GitCommitChangeCell row={row} />,
    },
    {
      title: '员工（上报）',
      key: 'user_display',
      width: 140,
      ellipsis: true,
      render: (_: unknown, row) => row.user_display || row.user_code,
    },
  ];

  return cols;
}
