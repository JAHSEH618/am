import { useEffect, useRef, useState } from 'react';

/**
 * 简单 SSE 订阅 hook
 * 路径请使用 /api/v1/dashboard/stream
 * 当 handlers 含有指定 event 名时仅触发该 handler；否则触发 message
 *
 * <p>返回 {@link SseState.connected}：连接 open 为 true、error/未连接为 false。
 * 调用方可据此把"SSE 健康时"的兜底轮询降频、"断线时"回退快轮询（见 Realtime 页）。
 *
 * gz
 */
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

    Object.keys(handlersRef.current).forEach((name) => {
      const fn = (e: MessageEvent) => {
        handlersRef.current[name]?.(e.data);
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
      subscribed.forEach(([n, fn]) => es.removeEventListener(n, fn as EventListener));
      es.close();
      setConnected(false);
    };
  }, [url, enabled]);

  return { connected };
}
