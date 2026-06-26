import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, App as AntdApp, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import './styles/global.css';
import { indigo, semantic, ink, surface, border, radius, shadow, fontFamily, fontSize } from './styles/tokens';

// 全局 design tokens（v3 · Apple-native / indigo）。统一从 styles/tokens.ts 取值，
// 与 global.css 的 :root 变量同源。
//
// 取舍：
//   - 单一强调色 = 品牌钴蓝 cobalt-600 (#1a52dc)，与 logo / 字标同源（v4：去紫，更高级）；
//   - 圆角走苹果连续档：卡片 14 / 控件 8 / 紧凑 6；
//   - Layout 表面透明，交给 global.css 的 Liquid Glass 材质层；
//   - 字号沿用 14，但全局走 tabular-nums（global.css），数据表格不抖动；
//   - 控件高度 32；密集表格靠 size="small" 收口到 28。
const themeConfig: Parameters<typeof ConfigProvider>[0]['theme'] = {
  algorithm: antdTheme.defaultAlgorithm,
  token: {
    colorPrimary: indigo[600],
    colorInfo: indigo[600],
    colorLink: indigo[600],
    colorLinkHover: indigo[500],
    colorLinkActive: indigo[700],
    colorSuccess: semantic.success.base,
    colorWarning: semantic.warning.base,
    colorError: semantic.error.base,
    colorTextBase: ink[1],
    // 墨色 5 级阶梯显式钉死：不再让 AntD 用 alpha 推导次级色，保证信息文字 ≥ 4.5:1 且与 tokens.ts 同源。
    colorText: ink[2],            // 正文 / 主要标签 ≈ 8.4:1
    colorTextSecondary: ink[3],   // 次级标签 / 说明  ≈ 4.9:1（信息文字下限）
    colorTextTertiary: ink[4],    // 弱提示 / 大字辅文（不承载关键信息）
    colorTextQuaternary: ink[5],  // 占位符 / 禁用
    colorTextPlaceholder: ink[5], // 输入占位
    // Layout 表面交给 global.css 的 Liquid Glass 材质层接管（透明 → 露出底层柔光背景）
    colorBgLayout: 'transparent',
    colorBgContainer: surface.card,
    colorBorder: border.default,
    colorBorderSecondary: border.subtle,
    // 苹果式连续大圆角：卡片 14 / 控件 8 / 紧凑 6
    borderRadius: radius.sm,
    borderRadiusLG: 14,
    borderRadiusSM: 6,
    fontSize: fontSize.md,
    fontSizeSM: fontSize.sm,
    fontFamily,
    controlHeight: 32,
    // 苹果分层投影：贴地接触阴影 + 柔和环境阴影。直接取 tokens.shadow（= --am-shadow-1/2），
    // 不再手写第三套数值，坐实「单一事实源」。
    boxShadow: shadow.sm,
    boxShadowSecondary: shadow.md,
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
      headerBg: surface.sunken,
      headerColor: ink[2],
      headerSplitColor: 'transparent',
      rowHoverBg: surface.sunken,
      cellPaddingBlockSM: 8,
    },
    Layout: {
      // 透明：实际表面由 global.css 的 .am-app-sider / .am-app-header 玻璃材质绘制
      headerBg: 'transparent',
      headerHeight: 56,
      headerPadding: '0 20px',
      siderBg: 'transparent',
      bodyBg: 'transparent',
    },
    Menu: {
      itemBorderRadius: 8,
      itemMarginInline: 8,
      itemHeight: 38,
      itemSelectedBg: 'rgba(26, 82, 220, 0.10)',
      itemSelectedColor: indigo[700],
    },
    Tag: {
      defaultBg: semantic.neutral.bg,
    },
    Statistic: {
      titleFontSize: 13,
      contentFontSize: 22,
    },
    Button: {
      primaryShadow: '0 1px 2px rgba(26, 82, 220, 0.28)',
      defaultShadow: 'none',
    },
    Segmented: {
      itemSelectedColor: indigo[700],
      trackBg: 'rgba(15, 23, 42, 0.05)',
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
