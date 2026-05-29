/**
 * 清掉 document.body 下被孤儿化的 antd Portal 元素。
 *
 * 触发场景：
 *  - InstallModal 等 Modal 在 open=true 状态被父组件 unmount（路由切换 / 401 redirect 等），
 *    antd 的 Portal 在某些过渡时机会留下 .ant-modal-mask / .ant-modal-wrap 在 body 下。
 *  - BFCache：浏览器把"前一个 SPA 页面"的 body DOM 保留，重新激活时 React 已经卸载，
 *    但孤儿元素仍在，且 z-index=1000 会全屏挡住所有点击。
 *
 * 这里用一个保守策略：只移除 .ant-modal-root / .ant-drawer / .ant-modal-mask 这几个
 * "明确属于 portal 容器"的孤儿节点，不动 antd-app-message / notification（消息提示）。
 *
 * gz
 */
export function purgeStrayPortals(): void {
  const selectors = [
    '.ant-modal-root',
    '.ant-modal-mask',
    '.ant-modal-wrap',
    '.ant-drawer',
    '.ant-image-preview-root',
  ];
  for (const sel of selectors) {
    document.querySelectorAll<HTMLElement>(sel).forEach((el) => {
      el.remove();
    });
  }
}
