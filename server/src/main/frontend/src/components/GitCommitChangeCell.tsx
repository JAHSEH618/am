import { useState } from 'react';
import { Drawer, Popover, Spin, Tag, Tooltip, Typography } from 'antd';
import type { ProjectGitCommit } from '../api/types';
import { fetchGitCommitPatch } from '../api/client';
import { decodeGitQuotedPath } from '../utils/gitPath';
import { NUM_STYLE } from '../utils/table';

const { Text } = Typography;

type Props = {
  row: ProjectGitCommit;
};

function DiffBody({
  patch,
  binary,
  truncated,
  reason,
}: {
  patch: string;
  binary: boolean;
  truncated: boolean;
  reason?: string | null;
}) {
  if (binary) {
    return <Text type="secondary">二进制文件，无文本 diff</Text>;
  }
  if (!patch) {
    // 'expired' = 超过 diff 保留期后被清理（见 GitCommitPatchRetentionCleaner），
    // 与"从未采集到"是两回事，分开提示，避免看起来像采集坏了。
    return (
      <Text type="secondary">
        {reason === 'expired'
          ? '该提交的 diff 已超过保留期，未再留存；如需查看请前往代码仓库'
          : '暂无 patch 内容（旧数据或未采集）'}
      </Text>
    );
  }
  return (
    <>
      {truncated && (
        <Tag color="warning" style={{ marginBottom: 8 }}>
          已截断{reason ? `：${reason}` : ''}
        </Tag>
      )}
      <pre
        style={{
          margin: 0,
          padding: 12,
          fontSize: 12,
          lineHeight: 1.45,
          overflow: 'auto',
          maxHeight: 'calc(100vh - 180px)',
          background: 'var(--am-surface-sunken)',
          border: '1px solid var(--am-border)',
          borderRadius: 6,
        }}
      >
        {patch.split('\n').map((line, i) => {
          let bg: string | undefined;
          if (line.startsWith('+') && !line.startsWith('+++')) bg = 'var(--am-success-bg)';
          else if (line.startsWith('-') && !line.startsWith('---')) bg = 'var(--am-error-bg)';
          else if (line.startsWith('@@')) bg = 'var(--am-brand-bg)';
          return (
            <div
              key={i}
              style={{ background: bg, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}
            >
              {line || ' '}
            </div>
          );
        })}
      </pre>
    </>
  );
}

export function GitCommitChangeCell({ row }: Props) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [patch, setPatch] = useState('');
  const [binary, setBinary] = useState(false);
  const [truncated, setTruncated] = useState(false);
  const [truncateReason, setTruncateReason] = useState<string | null>(null);

  const repoUrl = row.repo_url ?? '';
  const paths = row.path_stats?.filter((p) => p.path) ?? [];

  const summary = (
    <Text type="secondary">
      <Text style={{ color: 'var(--am-success-fg)', ...NUM_STYLE }}>+{row.lines_added}</Text>
      {' / '}
      <Text style={{ color: 'var(--am-error-fg)', ...NUM_STYLE }}>-{row.lines_deleted}</Text>
    </Text>
  );

  const openDiff = async (path: string) => {
    if (!repoUrl) {
      return;
    }
    setSelectedPath(path);
    setDrawerOpen(true);
    setLoading(true);
    setPatch('');
    try {
      const res = await fetchGitCommitPatch({
        repo_url: repoUrl,
        commit_hash: row.commit_hash,
        path,
      });
      setPatch(res.patch ?? '');
      setBinary(res.binary);
      setTruncated(res.patch_truncated);
      setTruncateReason(res.truncate_reason ?? null);
    } catch {
      setPatch('');
      setBinary(false);
      setTruncated(false);
      setTruncateReason(null);
    } finally {
      setLoading(false);
    }
  };

  if (paths.length === 0) {
    return (
      <Tooltip title="暂无文件级明细（旧数据或未上报 path_stats）" mouseEnterDelay={0.25}>
        {summary}
      </Tooltip>
    );
  }

  const popoverBody = (
    <div style={{ maxWidth: 480, maxHeight: 360, overflowY: 'auto' }}>
      <div style={{ fontWeight: 600, marginBottom: 10, fontSize: 13, color: 'var(--am-ink)' }}>
        文件变更（{paths.length}）
        {row.detail_status && row.detail_status !== 'none' && (
          <Tag style={{ marginLeft: 8 }} color={row.detail_status === 'full' ? 'green' : 'default'}>
            {row.detail_status}
          </Tag>
        )}
      </div>
      {paths.map((p, idx) => {
        const displayPath = decodeGitQuotedPath(p.path);
        const clickable = Boolean(repoUrl);
        return (
          <div
            key={`${p.path}-${idx}`}
            role={clickable ? 'button' : undefined}
            tabIndex={clickable ? 0 : undefined}
            onClick={clickable ? () => openDiff(decodeGitQuotedPath(p.path)) : undefined}
            onKeyDown={
              clickable
                ? (e) => {
                    // Space 默认会滚动页面，需先 preventDefault 再触发（与 utils/table 可点击行同款）
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      openDiff(decodeGitQuotedPath(p.path));
                    }
                  }
                : undefined
            }
            style={{
              padding: '8px 0',
              borderBottom: idx < paths.length - 1 ? '1px solid var(--am-border)' : undefined,
              cursor: clickable ? 'pointer' : 'default',
            }}
          >
            <div style={{ fontSize: 12, marginBottom: 4 }}>
              <Text style={{ color: 'var(--am-success-fg)', fontWeight: 500, ...NUM_STYLE }}>+{p.lines_added}</Text>
              <Text style={{ color: 'var(--am-ink-4)', margin: '0 4px' }}>/</Text>
              <Text style={{ color: 'var(--am-error-fg)', fontWeight: 500, ...NUM_STYLE }}>-{p.lines_deleted}</Text>
              {clickable && (
                <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                  点击查看 diff
                </Text>
              )}
            </div>
            <div
              style={{
                fontSize: 12,
                lineHeight: 1.5,
                color: clickable ? 'var(--am-brand)' : 'var(--am-ink-2)',
                wordBreak: 'break-all',
                fontFamily: 'var(--am-font-mono)',
              }}
              title={displayPath}
            >
              {displayPath}
            </div>
          </div>
        );
      })}
    </div>
  );

  return (
    <>
      <Popover
        content={popoverBody}
        trigger="hover"
        placement="leftTop"
        mouseEnterDelay={0.2}
        overlayStyle={{ maxWidth: 500 }}
        overlayInnerStyle={{ padding: '12px 14px' }}
      >
        {summary}
      </Popover>
      <Drawer
        title={selectedPath ? decodeGitQuotedPath(selectedPath) : '文件 diff'}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={Math.min(960, typeof window !== 'undefined' ? window.innerWidth - 48 : 960)}
        destroyOnClose
      >
        <Spin spinning={loading}>
          <DiffBody patch={patch} binary={binary} truncated={truncated} reason={truncateReason} />
        </Spin>
      </Drawer>
    </>
  );
}
