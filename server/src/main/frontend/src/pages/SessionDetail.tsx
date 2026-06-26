import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  List,
  Pagination,
  Row,
  Segmented,
  Space,
  Spin,
  Tabs,
  Tooltip,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import {
  ArrowLeftOutlined,
  BulbOutlined,
  RobotOutlined,
  ToolOutlined,
  UserOutlined,
  ApartmentOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import MessagePartList from '../components/MessagePartList';
import {
  fetchMonitorTargets,
  fetchSession,
  fetchSessionEvents,
  fetchSessionMessages,
} from '../api/client';
import type {
  AiSession,
  AiSessionEvent,
  AiSessionMessage,
  MonitorTarget,
  PageDto,
} from '../api/types';
import {
  eventTypeColor,
  eventTypeLabel,
  formatMessageDelta,
  messageDeltaTagColor,
  formatTime,
  formatTokens,
  statusColor,
  statusLabel,
  targetTypeColor,
  targetTypeLabel,
  type TargetTypeMap,
} from '../utils/format';

const { Text, Paragraph } = Typography;
const PAGE_SIZE_MSG = 100;
const PAGE_SIZE_EVT = 50;
const MSG_POLL_MS = 12_000;

type MsgView = 'all' | 'conversation' | 'user';

function isToolHeavySession(s: AiSession): boolean {
  if (hasStoredRoleCounts(s)) {
    const conv = conversationCount(s) ?? 0;
    const stored = s.stored_message_count ?? 0;
    return stored > conv * 2 + 20;
  }
  const conv = (s.user_messages ?? 0) + (s.assistant_messages ?? 0);
  return (s.total_messages ?? 0) > conv * 2 + 20;
}

function defaultMsgView(s: AiSession): MsgView {
  return isToolHeavySession(s) ? 'conversation' : 'all';
}

function backfillIncomplete(session: AiSession): boolean {
  const stored = session.stored_message_count;
  const snapshot = session.reported_snapshot_messages ?? 0;
  if (snapshot <= 0) return false;
  // 窗内视图等场景若 API 未回填 stored，不要用 0 误判为「仍在回填」。
  if (stored == null) return false;
  return stored < snapshot;
}

function hasStoredRoleCounts(session: AiSession): boolean {
  return (session.stored_message_count ?? 0) > 0
    && session.stored_user_messages != null
    && session.stored_assistant_messages != null;
}

/** 详情页摘要区：优先 DB 已入库计数，与下方对话 Tab 口径一致。 */
function messageSummaryLabel(session: AiSession): string {
  if (hasStoredRoleCounts(session)) {
    const u = session.stored_user_messages ?? 0;
    const a = session.stored_assistant_messages ?? 0;
    const conv = session.stored_conversation_count ?? u + a;
    return `${u} / ${a} (对话 ${conv})`;
  }
  return `${session.user_messages} / ${session.assistant_messages} (合计 ${session.total_messages})`;
}

/** 对话视图 Tab 的预期总数（用于与 msgs.total 交叉校验时的语义说明）。 */
function conversationCount(session: AiSession): number | null {
  if (hasStoredRoleCounts(session)) {
    return session.stored_conversation_count
      ?? (session.stored_user_messages ?? 0) + (session.stored_assistant_messages ?? 0);
  }
  return null;
}

function msgViewLabel(view: MsgView): string {
  if (view === 'conversation') return '对话';
  if (view === 'user') return '仅用户';
  return '全部';
}

// 长消息（thinking / 大段 markdown / 工具大输出）默认折起的字符阈值。
// 选 600 比较克制：典型一条用户问话 / 短回答都不会触发折叠。
const COLLAPSE_THRESHOLD = 600;

export default function SessionDetail() {
  const { id } = useParams<{ id: string }>();
  const sessionId = Number(id);
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // 时间窗口（v2.4）：列表页带过来的 ?from=&to=。一旦命中，详情页只展示这段时间内的消息 / 事件，
  // 同时显示一条 banner 提示用户当前是窗内视图，可一键切回全量。
  const fromParam = searchParams.get('from') || undefined;
  const toParam = searchParams.get('to') || undefined;
  const hasWindow = !!(fromParam && toParam);

  const [session, setSession] = useState<AiSession | null>(null);
  const [loading, setLoading] = useState(true);

  const [msgs, setMsgs] = useState<PageDto<AiSessionMessage> | null>(null);
  const [msgPage, setMsgPage] = useState(0);
  const [msgView, setMsgView] = useState<MsgView>('conversation');
  const [msgViewTouched, setMsgViewTouched] = useState(false);

  const [evts, setEvts] = useState<PageDto<AiSessionEvent> | null>(null);
  const [evtPage, setEvtPage] = useState(0);

  // 字典：让 target_type Tag 用上 monitor_target 表里的颜色与名称
  const [targets, setTargets] = useState<MonitorTarget[]>([]);
  useEffect(() => {
    fetchMonitorTargets().then(setTargets).catch(() => setTargets([]));
  }, []);
  const targetMap = useMemo<TargetTypeMap>(() => {
    const m: TargetTypeMap = {};
    for (const t of targets) m[t.type_code] = { label: t.type_name, color: t.display_color };
    return m;
  }, [targets]);

  useEffect(() => {
    if (!sessionId) return;
    setLoading(true);
    setMsgViewTouched(false);
    fetchSession(sessionId, { from: fromParam, to: toParam })
      .then((s) => {
        setSession(s);
        setMsgView(defaultMsgView(s));
      })
      .finally(() => setLoading(false));
  }, [sessionId, fromParam, toParam]);

  const messageRoleParams = useMemo(() => {
    if (msgView === 'user') return { role: 'user' as const };
    if (msgView === 'conversation') return { roles: 'conversation' as const };
    return {};
  }, [msgView]);

  const loadMessages = useCallback(() => {
    if (!sessionId) return Promise.resolve();
    return fetchSessionMessages(sessionId, {
      page: msgPage,
      size: PAGE_SIZE_MSG,
      from: fromParam,
      to: toParam,
      ...messageRoleParams,
    }).then(setMsgs);
  }, [sessionId, msgPage, fromParam, toParam, messageRoleParams]);

  useEffect(() => {
    loadMessages();
  }, [loadMessages]);

  const backfillPending = !!session && backfillIncomplete(session);

  useEffect(() => {
    if (!sessionId || !backfillPending) return;
    const timer = window.setInterval(() => {
      loadMessages();
      fetchSession(sessionId, { from: fromParam, to: toParam }).then(setSession);
    }, MSG_POLL_MS);
    return () => window.clearInterval(timer);
  }, [sessionId, backfillPending, loadMessages, fromParam, toParam]);

  useEffect(() => {
    if (!sessionId) return;
    fetchSessionEvents(sessionId, {
      page: evtPage,
      size: PAGE_SIZE_EVT,
      from: fromParam,
      to: toParam,
    }).then(setEvts);
  }, [sessionId, evtPage, fromParam, toParam]);

  const clearWindow = () => {
    const sp = new URLSearchParams(searchParams);
    sp.delete('from');
    sp.delete('to');
    setSearchParams(sp);
    setMsgPage(0);
    setEvtPage(0);
  };

  if (loading || !session) {
    // antd 5.x 单独使用 <Spin /> 时不能直接 tip，必须 nest 子组件，否则 5.21+
    // 会退化成 fullscreen mask（覆盖整页且不消失）。这里直接放一个占位容器
    return (
      <div style={{ minHeight: '60vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Spin />
      </div>
    );
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {/* 顶部 header：返回 + 会话编号 + 来源 / 状态标签，间距对齐新 token rhythm */}
      <Space size={12} wrap>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)} type="text">
          返回
        </Button>
        <Text strong style={{ fontSize: 18, color: 'var(--am-ink)' }}>
          会话 #{session.id}
        </Text>
        <Tag
          color={targetTypeColor(session.target_type, targetMap)}
          style={{ marginInlineEnd: 0 }}
        >
          {targetTypeLabel(session.target_type, targetMap)}
        </Tag>
        <Space size={6}>
          {/* v2.8 起去掉"离线"灰化降级标签，只展示原始 status，与 Sessions 列表口径一致。 */}
          <span
            style={{
              display: 'inline-block',
              width: 8,
              height: 8,
              borderRadius: 4,
              background: statusColor(session.status),
            }}
          />
          <Text type="secondary" style={{ fontSize: 13 }}>
            {statusLabel(session.status)}
          </Text>
        </Space>
      </Space>

      {/*
        v2.11：无效会话提示。invalid_reason 非空表示模型从未真正回应过这个会话——典型场景是
        Claude Code 用户敲了 /usage、/exit 等本地斜杠命令，CLI 把它们当作"用户消息"上报上来，
        但模型从未被调用。SessionDetail 仍允许直接访问（运维排查需要），只是顶部明确警示，
        让人不要把它当成正常工作会话评估。
      */}
      {session.invalid_reason && (
        <Alert
          type="warning"
          showIcon
          message={
            <span>
              这是一个<strong>无效会话</strong>
              （{session.invalid_reason === 'local_command_only'
                ? 'local_command_only：用户只发过 /usage、/exit 等本地命令'
                : session.invalid_reason === 'merged_subagent'
                ? 'merged_subagent：已归并到父 chat 的子 Task composer'
                : `${session.invalid_reason}：模型从未真正回应过`}），
              已从列表 / 实时活跃统计中默认隐藏。
            </span>
          }
        />
      )}

      {hasWindow && (
        <Alert
          type="info"
          showIcon
          message={
            <Space size={6} wrap>
              <span>
                当前为窗内视图：仅展示 <strong>{fromParam}</strong> ~ <strong>{toParam}</strong> 期间的消息 / 事件 / 统计。
              </span>
              <Button size="small" type="link" onClick={clearWindow}>
                查看完整会话
              </Button>
            </Space>
          }
        />
      )}

      <Card size="small">
        <Descriptions column={{ xs: 1, sm: 2, md: 3 }} size="small">
          <Descriptions.Item label="员工">{session.user_display || session.user_code}</Descriptions.Item>
          <Descriptions.Item label="Agent">{session.agent_id}</Descriptions.Item>
          <Descriptions.Item label="模型">{session.model || '-'}</Descriptions.Item>
          <Descriptions.Item label="项目">{session.project_name || '-'}</Descriptions.Item>
          <Descriptions.Item label="分支">{session.git_branch || '-'}</Descriptions.Item>
          <Descriptions.Item label="仓库">
            <span className="am-break">{session.repo_url || '-'}</span>
          </Descriptions.Item>
          <Descriptions.Item label="工作目录" span={3}>
            <Text code style={{ wordBreak: 'break-all' }}>{session.cwd || '-'}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="开始时间">{formatTime(session.started_at, 'YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
          <Descriptions.Item label="最近活动">{formatTime(session.last_activity, 'YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
          <Descriptions.Item label="结束">{session.ended_at ? formatTime(session.ended_at, 'YYYY-MM-DD HH:mm:ss') : '进行中'}</Descriptions.Item>
          {hasWindow && session.window_message_count != null ? (
            <Descriptions.Item
              label={
                <Tooltip title="对话 = user + assistant (+ subagent)；与下方「对话」Tab 同口径，不含 tool/thinking 入库行">
                  窗内对话
                </Tooltip>
              }
            >
              <strong>{session.window_message_count}</strong>
              <span style={{ color: 'var(--am-ink-3)', marginLeft: 6 }}>
                / 累计 {conversationCount(session) ?? session.user_messages + session.assistant_messages}
              </span>
            </Descriptions.Item>
          ) : (
            <Descriptions.Item label="消息 (用户/助手)">{messageSummaryLabel(session)}</Descriptions.Item>
          )}
          {hasWindow && session.window_tokens != null ? (
            <Descriptions.Item label="窗内 Token">
              <strong>{formatTokens(session.window_tokens)}</strong>
              <span style={{ color: 'var(--am-ink-3)', marginLeft: 6 }}>
                / 累计 {formatTokens(session.input_tokens + session.output_tokens)}
              </span>
            </Descriptions.Item>
          ) : (
            <Descriptions.Item label="Token (in/out)">{`${formatTokens(session.input_tokens)} / ${formatTokens(session.output_tokens)}`}</Descriptions.Item>
          )}
          <Descriptions.Item label="缓存 Token (create/read)">{`${formatTokens(session.cache_create_tokens)} / ${formatTokens(session.cache_read_tokens)}`}</Descriptions.Item>
          <Descriptions.Item label="当前工具">{session.current_tool || '-'}</Descriptions.Item>
          <Descriptions.Item label="external_session_id" span={3}>
            <Text code copyable style={{ wordBreak: 'break-all' }}>{session.external_session_id}</Text>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Tabs
        defaultActiveKey="messages"
        items={[
          {
            key: 'messages',
            label: `${msgViewLabel(msgView)} (${msgs?.total ?? 0})`,
            children: (
              <Card size="small">
                {backfillPending && (
                  <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message={
                      <span>
                        消息仍在回填中：已入库 <strong>{session.stored_message_count ?? '—'}</strong> 条，
                        Agent 快照共 <strong>{session.reported_snapshot_messages ?? 0}</strong> 条。
                        页面将自动刷新。
                      </span>
                    }
                  />
                )}
                <Row justify="space-between" align="middle" style={{ marginBottom: 12 }} wrap>
                  <Tooltip title="对话视图隐藏 tool/thinking，更接近 Cursor 聊天窗口；工具很多的会话默认开启">
                    <Segmented
                      size="small"
                      value={msgView}
                      options={[
                        { label: '对话', value: 'conversation' },
                        { label: '仅用户', value: 'user' },
                        { label: '全部', value: 'all' },
                      ]}
                      onChange={(v) => {
                        setMsgViewTouched(true);
                        setMsgView(v as MsgView);
                        setMsgPage(0);
                      }}
                    />
                  </Tooltip>
                  {isToolHeavySession(session) && !msgViewTouched && msgView === 'conversation' && (
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      工具消息较多，已默认展示对话视图
                    </Text>
                  )}
                </Row>
                <MessageTimeline items={msgs?.items ?? []} targetType={session.target_type} />
                <Row justify="end" style={{ marginTop: 16 }}>
                  <Pagination
                    current={(msgs?.page ?? 0) + 1}
                    pageSize={msgs?.size ?? PAGE_SIZE_MSG}
                    total={msgs?.total ?? 0}
                    onChange={(p) => setMsgPage(p - 1)}
                    showSizeChanger={false}
                  />
                </Row>
              </Card>
            ),
          },
          {
            key: 'events',
            label: (
              <Tooltip title="Agent 上报的原始事件流（含 TOKEN_DELTA / MESSAGE_DELTA / TOOL_CALL 等），条数通常远大于对话轮次">
                {`事件流水 (${evts?.total ?? 0})`}
              </Tooltip>
            ),
            children: (
              <Card size="small">
                <List
                  size="small"
                  dataSource={evts?.items ?? []}
                  locale={{ emptyText: '暂无事件' }}
                  renderItem={(e) => (
                    <List.Item>
                      <Space wrap>
                        <Tag color={eventTypeColor(e.event_type)}>{eventTypeLabel(e.event_type)}</Tag>
                        {e.tool_name && <Tag color="blue">{e.tool_name}</Tag>}
                        {e.tokens_delta > 0 && <Tag color="gold">+{formatTokens(e.tokens_delta)} tk</Tag>}
                        {formatMessageDelta(e.messages_delta) && (
                          <Tag color={messageDeltaTagColor(e.messages_delta)}>
                            {formatMessageDelta(e.messages_delta)}
                          </Tag>
                        )}
                        {e.status && <Tag>{statusLabel(e.status)}</Tag>}
                        <Text type="secondary" style={{ fontSize: 12 }}>{formatTime(e.event_time, 'YYYY-MM-DD HH:mm:ss')}</Text>
                      </Space>
                    </List.Item>
                  )}
                />
                <Row justify="end" style={{ marginTop: 16 }}>
                  <Pagination
                    current={(evts?.page ?? 0) + 1}
                    pageSize={evts?.size ?? PAGE_SIZE_EVT}
                    total={evts?.total ?? 0}
                    onChange={(p) => setEvtPage(p - 1)}
                    showSizeChanger={false}
                  />
                </Row>
              </Card>
            ),
          },
        ]}
      />
    </Space>
  );
}

// ---------------- 消息时间轴 ----------------

interface RoleStyle {
  color: string;
  icon: React.ReactNode;
  label: string;
  bg: string;
  border: string;
}

// 消息气泡配色（v1.6 设计系统，与 Hero 卡 Tone 同源）：
//   user      indigo   主操作语义色
//   assistant emerald  执行成功 / 输出
//   thinking  violet   辅助 / 思考
//   tool      amber    工具 / 状态注意
const roleStyles: Record<string, RoleStyle> = {
  user:      { color: 'var(--am-brand)', icon: <UserOutlined />,  label: '用户',     bg: 'var(--am-brand-bg)', border: 'var(--am-brand-border)' },
  subagent:  { color: 'var(--am-sky)', icon: <ApartmentOutlined />, label: 'Task', bg: 'var(--am-sky-bg)', border: 'var(--am-sky-border)' },
  assistant: { color: 'var(--am-success-fg)', icon: <RobotOutlined />, label: '助手',     bg: 'var(--am-success-bg)', border: 'var(--am-success-border)' },
  thinking:  { color: 'var(--am-violet)', icon: <BulbOutlined />,  label: 'Thinking', bg: 'var(--am-violet-bg)', border: 'var(--am-violet-border)' },
  tool:      { color: 'var(--am-warning-fg)', icon: <ToolOutlined />,  label: '工具',     bg: 'var(--am-warning-bg)', border: 'var(--am-warning-border)' },
};

function styleOf(role: string): RoleStyle {
  return (
    roleStyles[role] || {
      color: 'var(--am-ink-3)',
      icon: <UserOutlined />,
      label: role,
      bg: 'var(--am-surface-sunken)',
      border: 'var(--am-border)',
    }
  );
}

function MessageTimeline({ items, targetType }: { items: AiSessionMessage[]; targetType?: string }) {
  if (items.length === 0) {
    return <Text type="secondary">暂无消息</Text>;
  }
  const tt = (targetType || '').toLowerCase();
  return (
    <Timeline
      mode="left"
      items={items.map((m) => ({
        key: m.id,
        dot: <span style={{ color: styleOf(m.role).color, fontSize: 16 }}>{styleOf(m.role).icon}</span>,
        children: <MessageBubble m={m} targetType={tt} sessionId={m.ai_session_id} />,
      }))}
    />
  );
}

function MessageBubble({
  m,
  targetType,
  sessionId,
}: {
  m: AiSessionMessage;
  targetType?: string;
  sessionId: number;
}) {
  const s = styleOf(m.role);
  const hasParts = (m.content_parts?.length ?? 0) > 0;
  const text = m.content_text || '';
  const long = text.length > COLLAPSE_THRESHOLD;
  // thinking 默认折叠（噪声多、辅助信息），其它默认展开
  const [expanded, setExpanded] = useState(m.role !== 'thinking');

  const visible = useMemo(() => {
    if (!long || expanded) return text;
    return text.slice(0, COLLAPSE_THRESHOLD) + '…';
  }, [text, long, expanded]);

  // 工具内容是 buildToolText 的多区块结构（[tool]/[args]/[result]），用等宽字体更易读
  const isMono = m.role === 'tool';

  return (
    <div style={{ marginBottom: 4 }}>
      <Space wrap size={6} style={{ marginBottom: 4 }}>
        <Tag color={s.color} style={{ marginInlineEnd: 0 }}>{s.label}</Tag>
        {m.role === 'user' &&
          ((m.slash_command_count ?? 0) > 0 || (m.slash_skill_count ?? 0) > 0) && (
            <>
              {(m.slash_command_count ?? 0) > 0 && (
                <Tag color="blue">斜杠命令 ×{m.slash_command_count}</Tag>
              )}
              {(m.slash_skill_count ?? 0) > 0 && (
                <Tag color="cyan">
                  {(targetType === 'codex' ? '技能' : '斜杠技能')}
                  {' '}
                  ×
                  {m.slash_skill_count}
                </Tag>
              )}
            </>
          )}
        {(m.ingest_version ?? 1) < 1 && (
          <Tag color="default">仅文本（历史）</Tag>
        )}
        {m.has_binary && (m.ingest_version ?? 0) >= 1 && (
          <Tag color="gold">含附件</Tag>
        )}
        {m.tool_name && <Tag color="purple">{m.tool_name}</Tag>}
        <Text type="secondary" style={{ fontSize: 12 }}>
          #{m.sequence_no} · {formatTime(m.message_time, 'YYYY-MM-DD HH:mm:ss')}
        </Text>
        {(m.input_tokens > 0 || m.output_tokens > 0) && (
          <Text type="secondary" style={{ fontSize: 12 }}>
            token: {formatTokens(m.input_tokens)} / {formatTokens(m.output_tokens)}
          </Text>
        )}
      </Space>
      <div
        style={{
          background: s.bg,
          padding: 12,
          borderRadius: 6,
          border: `1px solid ${s.border}`,
        }}
      >
        {hasParts ? (
          <MessagePartList
            parts={m.content_parts!}
            role={m.role}
            sessionId={sessionId}
            messageId={m.id}
          />
        ) : (
          <>
            <Paragraph
              style={{
                whiteSpace: 'pre-wrap',
                // 无空格长 token 也折行，避免长 URL / 路径 / JSON 横向撑破消息容器
                overflowWrap: 'anywhere',
                wordBreak: 'break-word',
                margin: 0,
                fontSize: 13,
                fontStyle: m.role === 'thinking' ? 'italic' : 'normal',
                fontFamily: isMono ? 'ui-monospace, SFMono-Regular, Menlo, monospace' : undefined,
                color: m.role === 'thinking' ? 'var(--am-violet-fg)' : undefined,
              }}
            >
              {visible || '(无内容)'}
            </Paragraph>
            {long && (
              <Button type="link" size="small" style={{ paddingLeft: 0 }} onClick={() => setExpanded((v) => !v)}>
                {expanded ? '收起' : `展开（剩余 ${text.length - COLLAPSE_THRESHOLD} 字）`}
              </Button>
            )}
          </>
        )}
      </div>
    </div>
  );
}
