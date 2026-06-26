import { useMemo, useState } from 'react';
import { Button, Collapse, Image, Space, Tag, Typography } from 'antd';
import type { ContentPart } from '../api/types';

const { Paragraph, Text } = Typography;

const COLLAPSE_THRESHOLD = 600;
const THUMB_WIDTH = 160;
const THUMB_HEIGHT = 120;

type Props = {
  parts: ContentPart[];
  role: string;
  sessionId: number;
  messageId: number;
};

type PartGroup =
  | { kind: 'single'; part: ContentPart; index: number }
  | { kind: 'images'; parts: ContentPart[]; index: number };

function groupParts(parts: ContentPart[]): PartGroup[] {
  const sorted = [...parts].sort((a, b) => (a.sort_order ?? 0) - (b.sort_order ?? 0));
  const groups: PartGroup[] = [];
  let imageBatch: ContentPart[] = [];
  let groupIndex = 0;

  const flushImages = () => {
    if (imageBatch.length === 0) return;
    groups.push({ kind: 'images', parts: imageBatch, index: groupIndex++ });
    imageBatch = [];
  };

  for (let i = 0; i < sorted.length; i++) {
    const p = sorted[i];
    if (p.type === 'image') {
      imageBatch.push(p);
      continue;
    }
    flushImages();
    groups.push({ kind: 'single', part: p, index: groupIndex++ });
  }
  flushImages();
  return groups;
}

export default function MessagePartList({ parts, role, sessionId, messageId }: Props) {
  const groups = useMemo(() => groupParts(parts), [parts]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {groups.map((g) =>
        g.kind === 'images' ? (
          <ImageAttachments
            key={`images-${g.index}`}
            parts={g.parts}
            sessionId={sessionId}
            messageId={messageId}
          />
        ) : (
          <PartBlock
            key={`${g.part.type}-${g.index}-${g.part.sort_order ?? 0}`}
            part={g.part}
            role={role}
            sessionId={sessionId}
            messageId={messageId}
          />
        ),
      )}
    </div>
  );
}

function ImageAttachments({
  parts,
  sessionId,
  messageId,
}: {
  parts: ContentPart[];
  sessionId: number;
  messageId: number;
}) {
  const withBlob = parts.filter((p) => p.blob_id);
  const missing = parts.filter((p) => !p.blob_id);

  return (
    <div>
      <Space wrap size={4} style={{ marginBottom: withBlob.length > 0 ? 8 : 0 }}>
        <Tag color="gold">图片 ×{parts.length}</Tag>
      </Space>
      {withBlob.length > 0 ? (
        <Image.PreviewGroup>
          <div
            style={{
              display: 'flex',
              flexDirection: 'row',
              flexWrap: 'wrap',
              gap: 8,
              alignItems: 'flex-start',
            }}
          >
            {withBlob.map((p, idx) => {
              const src = `/api/v1/ai-sessions/${sessionId}/messages/${messageId}/blobs/${p.blob_id}`;
              return (
                <div
                  key={`${p.blob_id}-${idx}`}
                  style={{
                    width: THUMB_WIDTH,
                    flexShrink: 0,
                  }}
                >
                  <Image
                    src={src}
                    alt="attachment"
                    width={THUMB_WIDTH}
                    height={THUMB_HEIGHT}
                    style={{
                      objectFit: 'cover',
                      borderRadius: 6,
                      border: '1px solid var(--am-border)',
                      cursor: 'pointer',
                    }}
                    preview={{ src }}
                  />
                  {p.width && p.height ? (
                    <Text type="secondary" style={{ display: 'block', fontSize: 11, marginTop: 4 }}>
                      {p.width}×{p.height}
                    </Text>
                  ) : null}
                </div>
              );
            })}
          </div>
        </Image.PreviewGroup>
      ) : null}
      {missing.map((p, idx) => (
        <Tag key={`missing-${idx}`} color="warning" style={{ marginTop: 4 }}>
          图片未入库{p.truncate_reason ? ` (${p.truncate_reason})` : ''}
        </Tag>
      ))}
    </div>
  );
}

function PartBlock({
  part: p,
  role,
  sessionId,
  messageId,
}: {
  part: ContentPart;
  role: string;
  sessionId: number;
  messageId: number;
}) {
  const isMono = p.type === 'tool_call' || p.type === 'tool_result';
  const isThinking = p.type === 'thinking' || role === 'thinking';

  if (p.type === 'system_context') {
    return (
      <Collapse
        size="small"
        items={[
          {
            key: 'sys',
            label: <Text type="secondary">系统上下文</Text>,
            children: (
              <Paragraph style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>
                {p.text || ''}
              </Paragraph>
            ),
          },
        ]}
      />
    );
  }

  if (p.type === 'image') {
    return (
      <ImageAttachments parts={[p]} sessionId={sessionId} messageId={messageId} />
    );
  }

  if (p.type === 'file_ref' || p.type === 'file_snippet') {
    return (
      <div>
        <Space wrap size={4}>
          <Tag>{p.type === 'file_snippet' ? '文件片段' : '路径'}</Tag>
          <Text code copyable style={{ fontSize: 12 }}>
            {p.path}
          </Text>
          {p.start_line != null && p.end_line != null ? (
            <Text type="secondary" style={{ fontSize: 12 }}>
              L{p.start_line}–{p.end_line}
            </Text>
          ) : null}
        </Space>
        {p.text ? <TextBlock text={p.text} mono /> : null}
      </div>
    );
  }

  if (p.type === 'tool_call') {
    return (
      <div>
        <Tag color="purple">工具调用 {p.tool_name}</Tag>
        {p.arguments_json ? <TextBlock text={p.arguments_json} mono /> : null}
      </div>
    );
  }

  const text = p.text || '';
  if (!text && p.type !== 'tool_result') {
    return p.truncated ? <Tag color="warning">已截断: {p.truncate_reason}</Tag> : null;
  }

  return (
    <div>
      {p.type === 'tool_result' && p.tool_name ? <Tag color="purple">{p.tool_name}</Tag> : null}
      <TextBlock text={text} mono={isMono} italic={isThinking} />
      {p.truncated ? (
        <Tag color="warning" style={{ marginTop: 4 }}>
          已截断: {p.truncate_reason}
        </Tag>
      ) : null}
    </div>
  );
}

function TextBlock({ text, mono, italic }: { text: string; mono?: boolean; italic?: boolean }) {
  const long = text.length > COLLAPSE_THRESHOLD;
  const [expanded, setExpanded] = useState(!long);
  const visible = long && !expanded ? text.slice(0, COLLAPSE_THRESHOLD) + '…' : text;
  return (
    <>
      <Paragraph
        style={{
          whiteSpace: 'pre-wrap',
          // 保留换行的同时，让无空格的长 token（URL / 路径 / 哈希 / 压缩 JSON）也能折行，
          // 否则单行会横向撑破会话详情容器。
          overflowWrap: 'anywhere',
          wordBreak: 'break-word',
          margin: '4px 0 0',
          fontSize: 13,
          fontFamily: mono ? 'ui-monospace, Menlo, monospace' : undefined,
          fontStyle: italic ? 'italic' : 'normal',
        }}
      >
        {visible}
      </Paragraph>
      {long && (
        <Button type="link" size="small" style={{ paddingLeft: 0 }} onClick={() => setExpanded((v) => !v)}>
          {expanded ? '收起' : `展开（剩余 ${text.length - COLLAPSE_THRESHOLD} 字）`}
        </Button>
      )}
    </>
  );
}
