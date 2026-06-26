import { Spin } from 'antd';

/**
 * 整页加载占位 —— 居中 Spin。用于 lazy 路由的 Suspense fallback 与首屏鉴权校验，
 * 取代 App.tsx 的 `null`（白屏）与各处不一致的 loading 写法。
 */
export default function PageLoading() {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: 280,
        width: '100%',
      }}
    >
      <Spin size="large" />
    </div>
  );
}
