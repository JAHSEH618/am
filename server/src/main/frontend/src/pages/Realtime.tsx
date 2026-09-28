import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Card, Col, List, Row, Space, Spin, Tag, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { fetchOnline } from '../api/client';
import type { AiSession, AiSessionEvent, OnlineAgent } from '../api/types';
import { useSse } from '../hooks/useSse';
import {
  eventTypeColor,
  eventTypeLabel,
  employeeName,
  formatMessageDelta,
  formatTime,
  formatTimeFromNow,
  formatTokens,
  isToolStatus,
  messageDeltaTagColor,
  modelLabel,
  statusLabel,
  STALE_VISUAL_THRESHOLD_SECONDS,
} from '../utils/format';
import StatusDot from '../components/StatusDot';
import { clickableRowProps } from '../utils/table';

const { Text } = Typography;
// SSE 健康时只做兜底慢轮询（活跃期由 SSE 增量 + scheduleOnlineRefresh 保鲜）；断线时回退快轮询。
const ONLINE_POLL_CONNECTED_MS = 30_000;
const ONLINE_POLL_FALLBACK_MS = 5_000;
/** SSE 事件后补拉 /online 的延迟：须 ≥ 服务端 online 缓存 TTL（3s）。 */
const ONLINE_SSE_REFRESH_DELAY_MS = 3_500;
const MAX_EVENTS = 100;
/**
 * 会话快照只为给事件流里的行补展示信息，按「最近变更」保留有限条即可。
 * 不设上限时页面开一整天会攒下当天所有会话，且每条事件都要整表拷贝一次。
 */
const MAX_SESSIONS = 500;

interface FlowEvent extends AiSessionEvent {
  receivedAt: string;
}

export default function Realtime() {
  const navigate = useNavigate();
  const [agents, setAgents] = useState<OnlineAgent[]>([]);
  const [events, setEvents] = useState<FlowEvent[]>([]);
  const [sessionsById, setSessionsById] = useState<Map<number, AiSession>>(() => new Map());
  // 首屏占位：在线表第一次 fetchOnline 落地前别让左卡闪「暂无在线 Agent」。
  const [loading, setLoading] = useState(true);

  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // 服务端 /online 有 3s single-flight 缓存：事件后等满一个 TTL 再补拉，拿到的才一定是事件之后算的，
  // 不会把 onSessionChanged 刚 patch 上的状态刷回旧值。节流而非防抖：事件不断时也保证 ≤ 3.5s 收敛。
  const scheduleOnlineRefresh = useCallback(() => {
    if (refreshTimerRef.current) return;
    refreshTimerRef.current = setTimeout(async () => {
      refreshTimerRef.current = null;
      try {
        setAgents(await fetchOnline());
      } catch {
        // 忽略；定时轮询会兜底
      }
    }, ONLINE_SSE_REFRESH_DELAY_MS);
  }, []);
  useEffect(
    () => () => {
      if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
    },
    [],
  );

  const onSessionEvent = useCallback((data: string) => {
    try {
      const e: AiSessionEvent = JSON.parse(data);
      const flow: FlowEvent = { ...e, receivedAt: new Date().toISOString() };
      setEvents((prev) => [flow, ...prev].slice(0, MAX_EVENTS));
      scheduleOnlineRefresh();
    } catch {
      // ignore malformed payload
    }
  }, [scheduleOnlineRefresh]);

  const onSessionChanged = useCallback((data: string) => {
    try {
      const s: AiSession = JSON.parse(data);
      setSessionsById((prev) => {
        // Map 保留插入顺序：先删再插把它挪到队尾，超限时从队头（最久未变更）淘汰。
        const next = new Map(prev);
        next.delete(s.id);
        next.set(s.id, s);
        if (next.size > MAX_SESSIONS) {
          const oldest = next.keys().next();
          if (!oldest.done) next.delete(oldest.value);
        }
        return next;
      });
      setAgents((prev) => {
        let changed = false;
        const next = prev.map((a) => {
          if (a.agent_id !== s.agent_id) return a;
          if (a.target_type != null && a.target_type !== s.target_type) return a;
          changed = true;
          return {
            ...a,
            target_type: a.target_type ?? s.target_type,
            current_status: s.status,
            current_tool: s.current_tool,
            current_model: s.model,
            project_name: s.project_name || a.project_name,
            branch_name: s.git_branch || a.branch_name,
            stale_since_seconds: 0,
            active: s.status !== 'idle' && s.status != null,
          };
        });
        // 无任何在线行匹配该会话时返回原引用，避免无谓整列表重渲（事件高频时收益明显）。
        return changed ? next : prev;
      });
      scheduleOnlineRefresh();
    } catch {
      // ignore
    }
  }, [scheduleOnlineRefresh]);

  const handlers = useMemo(
    () => ({
      session_event: onSessionEvent,
      session_changed: onSessionChanged,
    }),
    [onSessionEvent, onSessionChanged],
  );

  const { connected } = useSse('/api/v1/dashboard/stream', handlers);

  // 周期拉 online 表，保证卡片 status / active / last_seen 与后端一致：
  // SSE 健康时只做 30s 兜底慢轮询（活跃期由 SSE 增量 + scheduleOnlineRefresh 保鲜，新上线 agent 在任一
  // SSE 事件触发的补拉里也会出现）；SSE 断线时回退 5s 快轮询。connected 变化即重建定时器并立即补拉一次。
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const data = await fetchOnline();
        if (!alive) return;
        setAgents(data);
      } catch {
        // 忽略，错误会由全局拦截器提示
      } finally {
        if (alive) setLoading(false);
      }
    };
    tick();
    const id = window.setInterval(tick, connected ? ONLINE_POLL_CONNECTED_MS : ONLINE_POLL_FALLBACK_MS);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, [connected]);

  // 刚打开页面时 EventSource 还没 open，给 3s 宽限再提示断线，避免首屏一闪而过的「已断开」。
  const [sseDownNotice, setSseDownNotice] = useState(false);
  useEffect(() => {
    if (connected) {
      setSseDownNotice(false);
      return;
    }
    const t = window.setTimeout(() => setSseDownNotice(true), 3000);
    return () => window.clearTimeout(t);
  }, [connected]);

  // /dashboard/online 同时返回离线设备（device_online=false，供 Dashboard 注册员工表用）；
  // 这里是「实时活动」，离线机器既没有活动也不该和在线空闲卡片长得一样，直接不列。
  const liveAgents = useMemo(() => agents.filter((a) => a.device_online !== false), [agents]);

  return (
    <Row gutter={[16, 16]}>
      <Col xs={24} lg={14}>
        <Spin spinning={loading && agents.length === 0}>
          <Card
            title={
              // 标题圆点反映推送通道本身：断线时不再假装「实时」，改为灰点 + 说明正在靠轮询兜底。
              <Space>
                <StatusDot color={connected ? 'var(--am-brand)' : 'var(--am-ink-4)'} pulse={connected} />
                实时活动
                {sseDownNotice && (
                  <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                    推送已断开 · 每 {ONLINE_POLL_FALLBACK_MS / 1000} 秒轮询
                  </Text>
                )}
              </Space>
            }
            size="small"
          >
            <List
              dataSource={liveAgents}
              rowKey={(a) => `${a.agent_id}|${a.target_type ?? '__none__'}|${a.device_online === false ? '0' : '1'}`}
              locale={{ emptyText: '暂无在线 Agent' }}
              grid={{ gutter: 12, xs: 1, sm: 2, md: 2, lg: 2, xl: 3 }}
              renderItem={(a) => (
                <List.Item>
                  <Card
                    size="small"
                    className="am-clickable"
                    role="button"
                    tabIndex={0}
                    onClick={() => navigate(`/sessions?user_code=${a.user_code}`)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        navigate(`/sessions?user_code=${a.user_code}`);
                      }
                    }}
                    title={
                      // 姓名不省略、主机名在剩余宽度内省略：此前主机名不截断，"09-26 00:54" 这类长时间戳
                      // 一出现就和主机名撞成 "e1009-mb09-26 00:54"。
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                        {/* 标题区唯一的活跃脉冲圆点（active 时脉冲）；下方 body 圆点只承载状态色+灰化，不重复脉冲 */}
                        <StatusDot status={a.current_status} pulse={a.active} />
                        <Text strong style={{ flexShrink: 0 }}>{employeeName(a.user_display, a.user_code)}</Text>
                        <Text type="secondary" ellipsis={{ tooltip: a.hostname }} style={{ fontSize: 12, minWidth: 0 }}>
                          {a.hostname}
                        </Text>
                      </div>
                    }
                    extra={
                      <Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap', marginLeft: 8 }}>
                        {formatTimeFromNow(a.last_seen_time)}
                      </Text>
                    }
                    styles={{ body: { padding: 12 } }}
                  >
                    <Space direction="vertical" size={4} style={{ width: '100%' }}>
                      {!a.current_status ? (
                        // 设备在线但没有 AI 会话：给一句人话，而不是一个孤零零的 "● -"
                        <Text type="secondary" style={{ fontSize: 13 }}>暂无运行中的 AI 会话</Text>
                      ) : (
                      <Space>
                        {/* last_activity 超过阈值时仅做视觉灰化；status 以 DB 为准。脉冲已由标题圆点承载，这里不再叠加 */}
                        <StatusDot
                          status={a.current_status}
                          stale={a.stale_since_seconds > STALE_VISUAL_THRESHOLD_SECONDS}
                        />
                        <Text type={a.stale_since_seconds > STALE_VISUAL_THRESHOLD_SECONDS ? 'secondary' : undefined}>
                          {statusLabel(a.current_status)}
                        </Text>
                        {a.current_tool && isToolStatus(a.current_status) && a.stale_since_seconds <= STALE_VISUAL_THRESHOLD_SECONDS && (
                          <Tag>{a.current_tool}</Tag>
                        )}
                      </Space>
                      )}
                      {a.project_name && (
                        <Text type="secondary" ellipsis={{ tooltip: true }} style={{ fontSize: 12 }}>
                          {a.project_name}{a.branch_name ? ` @${a.branch_name}` : ''}
                        </Text>
                      )}
                      {a.current_model && (
                        <Text type="secondary" ellipsis={{ tooltip: a.current_model }} style={{ fontSize: 12 }}>
                          {modelLabel(a.current_model)}
                        </Text>
                      )}
                    </Space>
                  </Card>
                </List.Item>
              )}
            />
          </Card>
        </Spin>
      </Col>

      <Col xs={24} lg={10}>
        <Card title={<Space>实时事件流 <Tag>{events.length}</Tag></Space>} size="small">
          <div style={{ maxHeight: '70vh', overflow: 'auto' }}>
            <List
              size="small"
              dataSource={events}
              rowKey={(e) => `${e.id}-${e.receivedAt}`}
              locale={{ emptyText: '等待事件中...（启动 agent 后会自动流入）' }}
              renderItem={(e) => {
                const sess = sessionsById.get(e.ai_session_id);
                return (
                  <List.Item {...clickableRowProps(() => navigate(`/sessions/${e.ai_session_id}`))}>
                    <Space direction="vertical" size={2} style={{ width: '100%' }}>
                      <Space wrap>
                        <Tag color={eventTypeColor(e.event_type)}>{eventTypeLabel(e.event_type)}</Tag>
                        {e.tool_name && <Tag>{e.tool_name}</Tag>}
                        {e.tokens_delta > 0 && <Tag color="gold">+{formatTokens(e.tokens_delta)} tk</Tag>}
                        {formatMessageDelta(e.messages_delta) && (
                          <Tag color={messageDeltaTagColor(e.messages_delta)}>
                            {formatMessageDelta(e.messages_delta)}
                          </Tag>
                        )}
                        {e.status && (
                          <Space size={4}>
                            <StatusDot status={e.status} />
                            {statusLabel(e.status)}
                          </Space>
                        )}
                      </Space>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {formatTime(e.event_time)} · {employeeName(e.user_display, e.user_code)}{sess?.project_name ? ` · ${sess.project_name}` : ''}
                      </Text>
                    </Space>
                  </List.Item>
                );
              }}
            />
          </div>
        </Card>
      </Col>
    </Row>
  );
}
