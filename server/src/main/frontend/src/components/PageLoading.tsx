import { Skeleton } from 'antd';

/**
 * 整页加载占位 —— 内容形 Skeleton（取代居中 Spin）。用于 lazy 路由的 Suspense fallback
 * 与首屏鉴权校验：用骨架块预演「标题 + 正文」的版式，避免内容区从空白突兀跳出，
 * 也比单个 Spin 更贴近最终布局。动效仅表达加载态，prefers-reduced-motion 已全局兜底。
 */
export default function PageLoading() {
  return (
    <div style={{ width: '100%' }}>
      <Skeleton active title={{ width: 220 }} paragraph={{ rows: 3 }} />
      <Skeleton active paragraph={{ rows: 4 }} style={{ marginTop: 28 }} />
    </div>
  );
}
