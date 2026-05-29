import { useEffect, useRef } from 'react';

/**
 * 简单 SSE 订阅 hook
 * 路径请使用 /api/v1/dashboard/stream
 * 当 handlers 含有指定 event 名时仅触发该 handler；否则触发 message
 *
 * gz
 */
export function useSse(
  url: string,
  handlers: Record<string, (data: string) => void>,
  enabled = true,
) {
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

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

    es.onerror = () => {
      // EventSource 内置重连，不主动 close 即可
    };

    return () => {
      subscribed.forEach(([n, fn]) => es.removeEventListener(n, fn as EventListener));
      es.close();
    };
  }, [url, enabled]);
}
