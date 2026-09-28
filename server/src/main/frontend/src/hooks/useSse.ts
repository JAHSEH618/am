import { useEffect, useRef, useState } from 'react';

/**
 * 简单 SSE 订阅 hook
 * 路径请使用 /api/v1/dashboard/stream
 * 当 handlers 含有指定 event 名时仅触发该 handler；否则触发 message
 *
 * <p>返回 {@link SseState.connected}：连接 open 为 true、error/未连接为 false。
 * 调用方可据此把"SSE 健康时"的兜底轮询降频、"断线时"回退快轮询（见 Realtime 页）。
 *
 * <p><b>事件按 {@link SSE_BATCH_MS} 攒批投递。</b>每条 SSE 消息是一个独立的浏览器任务，
 * handler 里的 setState 各自触发一次整页重渲；几百台 agent 同时上报时 session_changed
 * 能到每秒几十条，Dashboard / 会话列表这种大表页面就一直在重渲。攒到同一个定时器回调里
 * 顺序调用各 handler，React 18 会把这一批 setState 合成一次渲染。handler 仍逐条、按到达
 * 顺序收到事件，语义不变；只是最多晚 {@link SSE_BATCH_MS} 生效。
 *
 * gz
 */
/** 攒批窗口：人眼无感，又能把高频事件压到每秒至多几次渲染。 */
const SSE_BATCH_MS = 250;

export interface SseState {
  /** EventSource 是否处于已连接（open）状态。EventSource 自带重连，重连成功会再次置 true。 */
  connected: boolean;
}

export function useSse(
  url: string,
  handlers: Record<string, (data: string) => void>,
  enabled = true,
): SseState {
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!enabled) return;
    const es = new EventSource(url, { withCredentials: false });
    const subscribed: Array<[string, (e: MessageEvent) => void]> = [];
    let queue: Array<[string, string]> = [];
    let flushTimer: ReturnType<typeof setTimeout> | undefined;
    const flush = () => {
      flushTimer = undefined;
      const batch = queue;
      queue = [];
      for (const [name, data] of batch) {
        handlersRef.current[name]?.(data);
      }
    };

    Object.keys(handlersRef.current).forEach((name) => {
      const fn = (e: MessageEvent) => {
        queue.push([name, e.data]);
        if (flushTimer === undefined) {
          flushTimer = setTimeout(flush, SSE_BATCH_MS);
        }
      };
      es.addEventListener(name, fn as EventListener);
      subscribed.push([name, fn]);
    });

    es.onopen = () => setConnected(true);
    es.onerror = () => {
      // EventSource 内置重连，不主动 close；仅标记断开，重连成功后 onopen 会再置 true。
      setConnected(false);
    };

    return () => {
      if (flushTimer !== undefined) clearTimeout(flushTimer);
      subscribed.forEach(([n, fn]) => es.removeEventListener(n, fn as EventListener));
      es.close();
      setConnected(false);
    };
  }, [url, enabled]);

  return { connected };
}
