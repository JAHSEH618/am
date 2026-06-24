import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, App as AntdApp, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import './styles/global.css';

// 全局 design tokens（v1.6 设计系统）。
//
// 取舍：
//   - 主色用克制的 #2563eb（slate / indigo 偏冷蓝），与 dashboard 数据色板拉开层级；
//   - 圆角统一 6（卡片 / 按钮 / 输入），不再混用 4 / 6 / 8；
//   - 表面颜色：页面底色 #f5f7fa，卡片白；与 global.css 的 --am-bg-* 对齐；
//   - 字号沿用 AntD 默认 14，但全局走 tabular-nums（global.css），数据表格不抖动；
//   - 控件高度 32（默认）；密集表格里靠 size="small" 单独收口到 28。
const themeConfig: Parameters<typeof ConfigProvider>[0]['theme'] = {
  algorithm: antdTheme.defaultAlgorithm,
  token: {
    colorPrimary: '#2563eb',
    colorInfo: '#2563eb',
    colorSuccess: '#10b981',
    colorWarning: '#f59e0b',
    colorError: '#ef4444',
    colorBgLayout: '#f5f7fa',
    colorBgContainer: '#ffffff',
    colorBorder: '#e2e6ed',
    colorBorderSecondary: '#eef0f4',
    borderRadius: 6,
    borderRadiusLG: 8,
    borderRadiusSM: 4,
    fontSize: 14,
    fontSizeSM: 13,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', 'Helvetica Neue', sans-serif",
    controlHeight: 32,
  },
  components: {
    // 卡片：默认带极淡分割，标题区缩到 12px padding 才不至于"标题占半个卡片"
    Card: {
      headerHeight: 44,
      headerHeightSM: 38,
      paddingLG: 16,
      borderRadiusLG: 8,
    },
    // 表格：sticky thead 由 .am-sticky-table 接管；这里只统一行高
    Table: {
      headerBg: '#fafbfc',
      headerColor: 'rgba(15, 23, 42, 0.72)',
      headerSplitColor: 'transparent',
      rowHoverBg: '#f5f7fa',
      cellPaddingBlockSM: 8,
    },
    Layout: {
      headerBg: '#ffffff',
      headerHeight: 56,
      headerPadding: '0 20px',
      siderBg: '#ffffff',
      bodyBg: '#f5f7fa',
    },
    Menu: {
      itemBorderRadius: 6,
      itemMarginInline: 8,
      itemSelectedBg: '#eef2ff',
      itemSelectedColor: '#2563eb',
    },
    Tag: {
      defaultBg: '#f1f5f9',
    },
    Statistic: {
      titleFontSize: 13,
      contentFontSize: 22,
    },
  },
};

// BFCache 兜底：浏览器从前进 / 后退缓存恢复页面时，DOM 会保留前一次会话的所有 portal 元素
// （包括 antd Modal mask）。React 不会重新跑 useEffect，孤儿 mask 全屏挡住交互。
// 检测 persisted=true 时直接整页 reload，让 React 从 0 重建。
window.addEventListener('pageshow', (e) => {
  if ((e as PageTransitionEvent).persisted) {
    window.location.reload();
  }
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={themeConfig}>
      <AntdApp>
        <BrowserRouter basename="/console">
          <App />
        </BrowserRouter>
      </AntdApp>
    </ConfigProvider>
  </React.StrictMode>,
);
