import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Badge, Card, Col, List, Row, Space, Tag, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { fetchOnline } from '../api/client';
import type { AiSession, AiSessionEvent, OnlineAgent } from '../api/types';
import { useSse } from '../hooks/useSse';
import {
  eventTypeColor,
  eventTypeLabel,
  formatMessageDelta,
  formatTime,
  formatTimeFromNow,
  formatTokens,
  isToolStatus,
  messageDeltaTagColor,
  statusColor,
  statusLabel,
  STALE_VISUAL_THRESHOLD_SECONDS,
} from '../utils/format';

const { Text } = Typography;
const ONLINE_REFRESH_MS = 5_000;
const MAX_EVENTS = 100;

interface FlowEvent extends AiSessionEvent {
  receivedAt: string;
}

export default function Realtime() {
  const navigate = useNavigate();
  const [agents, setAgents] = useState<OnlineAgent[]>([]);
  const [events, setEvents] = useState<FlowEvent[]>([]);
  const [sessionsById, setSessionsById] = useState<Record<number, AiSession>>({});

  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const scheduleOnlineRefresh = useCallback(() => {
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
    refreshTimerRef.current = setTimeout(async () => {
      try {
        setAgents(await fetchOnline());
      } catch {
        // 忽略；5s polling 会兜底
      }
    }, 400);
  }, []);

  // 周期拉 online 表；SSE 触发时防抖补拉，保证卡片 status / active / last_seen 与后端一致
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const data = await fetchOnline();
        if (!alive) return;
        setAgents(data);
      } catch {
        // 忽略，错误会由全局拦截器提示
      }
    };
    tick();
    const id = window.setInterval(tick, ONLINE_REFRESH_MS);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, []);

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
      setSessionsById((prev) => ({ ...prev, [s.id]: s }));
      setAgents((prev) => prev.map((a) => {
        if (a.agent_id !== s.agent_id) return a;
        if (a.target_type != null && a.target_type !== s.target_type) return a;
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
      }));
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

  useSse('/api/v1/dashboard/stream', handlers);

  return (
    <Row gutter={[16, 16]}>
      <Col xs={24} lg={14}>
        <Card title={<Space><Badge status="processing" />实时活动</Space>} size="small">
          <List
            dataSource={agents}
            locale={{ emptyText: '暂无在线 Agent' }}
            grid={{ gutter: 12, xs: 1, sm: 2, md: 2, lg: 2, xl: 3 }}
            renderItem={(a) => (
              <List.Item>
                <Card
                  size="small"
                  hoverable
                  onClick={() => navigate(`/sessions?user_code=${a.user_code}`)}
                  title={
                    <Space>
                      <Badge status={a.active ? 'processing' : 'default'} />
                      <Text strong>{a.user_display || a.user_code}</Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>{a.hostname}</Text>
                    </Space>
                  }
                  extra={<Text type="secondary" style={{ fontSize: 12 }}>{formatTimeFromNow(a.last_seen_time)}</Text>}
                  styles={{ body: { padding: 12 } }}
                >
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    <Space>
                      {/* last_activity 超过阈值时仅做视觉灰化；status 以 DB 为准 */}
                      <span style={{
                        display: 'inline-block', width: 8, height: 8, borderRadius: 4,
                        background: a.stale_since_seconds > STALE_VISUAL_THRESHOLD_SECONDS ? '#cbd5e1' : statusColor(a.current_status),
                      }} />
                      <Text type={a.stale_since_seconds > STALE_VISUAL_THRESHOLD_SECONDS ? 'secondary' : undefined}>
                        {statusLabel(a.current_status)}
                      </Text>
                      {a.current_tool && isToolStatus(a.current_status) && a.stale_since_seconds <= STALE_VISUAL_THRESHOLD_SECONDS && (
                        <Tag color="blue">{a.current_tool}</Tag>
                      )}
                    </Space>
                    {a.project_name && (
                      <Text type="secondary" ellipsis style={{ fontSize: 12 }}>
                        {a.project_name}{a.branch_name ? ` @${a.branch_name}` : ''}
                      </Text>
                    )}
                    {a.current_model && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {a.current_model}
                      </Text>
                    )}
                  </Space>
                </Card>
              </List.Item>
            )}
          />
        </Card>
      </Col>

      <Col xs={24} lg={10}>
        <Card title={<Space>实时事件流 <Tag>{events.length}</Tag></Space>} size="small">
          <div style={{ maxHeight: '70vh', overflow: 'auto' }}>
            <List
              size="small"
              dataSource={events}
              locale={{ emptyText: '等待事件中...（启动 agent 后会自动流入）' }}
              renderItem={(e) => {
                const sess = sessionsById[e.ai_session_id];
                return (
                  <List.Item
                    style={{ cursor: 'pointer' }}
                    onClick={() => navigate(`/sessions/${e.ai_session_id}`)}
                  >
                    <Space direction="vertical" size={2} style={{ width: '100%' }}>
                      <Space wrap>
                        <Tag color={eventTypeColor(e.event_type)}>{eventTypeLabel(e.event_type)}</Tag>
                        {e.tool_name && <Tag color="blue">{e.tool_name}</Tag>}
                        {e.tokens_delta > 0 && <Tag color="gold">+{formatTokens(e.tokens_delta)} tk</Tag>}
                        {formatMessageDelta(e.messages_delta) && (
                          <Tag color={messageDeltaTagColor(e.messages_delta)}>
                            {formatMessageDelta(e.messages_delta)}
                          </Tag>
                        )}
                        {e.status && (
                          <Space size={4}>
                            <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 4, background: statusColor(e.status) }} />
                            {statusLabel(e.status)}
                          </Space>
                        )}
                      </Space>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {formatTime(e.event_time)} · {e.user_display || e.user_code}{sess?.project_name ? ` · ${sess.project_name}` : ''}
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
