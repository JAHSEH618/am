# DESIGN.md — AIWatch 控制台设计系统（v3 · Apple-native / indigo）

> 这是 `aiwatch-web` 视觉系统的**单一事实源说明**。配色 / 字号 / 间距 / 动效一旦要改，
> 先读这里，再改令牌；不要在页面里散落硬编码。register 与产品调性见 `PRODUCT.md`。

## 0. 一句话基调

Linear / Stripe 一类的**克制、精确、数据密集**后台。强调色单一（品牌靛紫），数字近黑可信，
语义色只在状态 / 标签 / 提示里出现。"表达式高级感"体现在 **Hero 大号数字的层级与紧排**，
不是更多特效。仪器要消失在任务里。

## 1. 令牌的两个出处（必须同步）

| 场景 | 出处 | 用法 |
| --- | --- | --- |
| JS / TSX 里需要色值（ECharts、AntD `color=` 之外的圆点、`style` 内联） | **`src/styles/tokens.ts`** | `import { ink, semantic, indigo } from '../styles/tokens'` |
| 纯 CSS / className / 内联 `style` 字符串 | **`src/styles/global.css :root`** | `style={{ color: 'var(--am-ink-3)' }}` |

**两边的「原色板 + 语义角色」数值必须一致。改一个色，两处一起改。**

- **DOM 内联样式优先用 CSS 变量**：`style={{ color: 'var(--am-ink-3)' }}`。可读、可主题化、无需 import。
- **canvas / ECharts 必须用 JS 令牌**：`option` 里的 series / axis / itemStyle 颜色读不到 CSS 变量，
  一律 `import { ink, semantic, accent } from 'tokens'` 取真实色值。
- **AntD `<Tag color="geekblue">` 等预设名**保持原样，AntD 自己渲染。只有「裸 hex」才需要换令牌。

## 2. 颜色

### 2.1 品牌主色 —— 单一强调色 indigo

只有一个强调色：**品牌钴蓝 `indigo-600 #1a52dc`**（v4 去紫；导出名仍叫 `indigo`），与 logo / 字标 / Hero 图标同源。
用于：主操作（按钮）、链接、当前选中、状态指示。**不做装饰**。

`indigo[50…900]`（`--am-indigo-50…800` / `--am-brand*`，值以 `tokens.ts` 为准）：`#eef4ff #dbe8ff #bdd4ff #8fb4ff #5a8cf8 #2e6bf0 #1a52dc #1644b6 #163a90 #15326f`

> 历史遗留的功能蓝 `#2563eb` 已全部并入 indigo——不要再引入第二种"功能蓝"。

### 2.2 墨色阶梯（文字层级）

| 令牌 | 值 | 对比（白底） | 用途 |
| --- | --- | --- | --- |
| `--am-ink` / `ink[1]` | `#1d1f26` | ~15:1 | 标题、关键数据（近黑） |
| `--am-ink-2` / `ink[2]` | `#44464f` | ~8.4:1 | 正文、主要标签 |
| `--am-ink-3` / `ink[3]` | `#6a6c75` | ~4.9:1 | **次级标签 / 说明（承载信息的文字下限）** |
| `--am-ink-4` / `ink[4]` | `#8a8c94` | ~3.4:1 | 弱提示 / 大字辅文（不承载关键信息） |
| `--am-ink-5` / `ink[5]` | `#b3b5bd` | — | 占位符 / 禁用 / 「—」空值 |

**规则**：用户要读取信息的文字不要低于 `ink-3`。曾经散落 60+ 次的 `#94a3b8`（~2.9:1）已统一上提到
`ink-3`，这是可读性修复，不是风格选择。

### 2.3 表面与描边

`--am-bg-page #f4f5f8` · `--am-bg-card #fff` · `--am-surface-sunken #f7f8fa`（工具栏 / 过滤条 / 表头第二中性层）
`--am-border-subtle .06` · `--am-border .10` · `--am-border-strong .14`（均为 slate 透明度）

### 2.4 语义状态 —— 一语义一套四件套

每个语义提供 `{ base, fg, bg, border }`：`base`=圆点 / 描边 / 实心，`fg`=达标对比的同色系文字，
`bg`=极浅底，`border`=浅描边。**把曾经的「4 种绿 / 4 种橙 / 3 种蓝」压成每语义一套。**

| 语义 | base | fg(文字) | bg | CSS 前缀 |
| --- | --- | --- | --- | --- |
| brand | `#1a52dc` | `#1644b6` | `#eef4ff` | `--am-brand*` |
| success | `#10b981` | `#047857` | `#ecfdf5` | `--am-success*` |
| warning | `#f59e0b` | `#b45309` | `#fffbeb` | `--am-warning*` |
| error | `#ef4444` | `#dc2626` | `#fef2f2` | `--am-error*` |
| info | `#1a52dc` | `#1644b6` | `#eef4ff` | `--am-info*` |

### 2.5 分类强调色（accent）—— 非状态、需彼此可分

消息角色 / 设置分区图标 / 图表多系列用。每色一套 `{base,fg,bg,border}`（`--am-sky* / --am-violet* /
--am-purple* / --am-blue* / --am-teal(JS only) / --am-rose*`）。**别再为"要个不同颜色"散落随机 hex——从这里取。**

- 消息角色：user=brand · assistant=success · tool=warning · subagent=sky · thinking=violet
- Hero KPI tone：注册=brand · 活跃=success · Token=warning · 渗透率=rose

### 2.6 会话状态分类色（`statusHue` / `statusColor()`）

10 种会话状态需要彼此可分，保留多色相，但 `thinking` 已并入品牌靛。只从 `utils/format.ts statusColor()`
取（它从 `tokens.statusHue` 取），不要在页面里写状态 hex。

## 3. 字体排印

- **字族**：`-apple-system / SF Pro / PingFang SC …`（`fontFamily`）。等宽用 `fontMono`。
- **固定 rem 阶梯**（产品态不用 clamp 流体字号）：`xs 12 · sm 13 · md 14 · lg 16 · xl 20 · 2xl 28`。
- **展示档**（Hero 数字）：`displaySm 30 · display 36 · displayLg 44`，配 `-0.03em` 紧排 + `tabular-nums`。
- **字重**：`regular 400 · medium 500 · semibold 600 · bold 680`。
- 全局开 `font-feature-settings:'tnum'`，数据表格数字不抖动。正文 `text-wrap` 不强求；标题可 `balance`。

## 4. 形状 / 投影 / 动效

- **圆角**（苹果连续档）：`sm 8 · md 12 · lg 16 · xl 22`。AntD：卡片 14 / 控件 8 / 紧凑 6。
- **投影**：苹果分层（贴地接触 + 柔和环境），`--am-shadow-1/2/pop` + `--am-inner-hi` 内高光。
- **动效**：缓动统一 `--am-ease cubic-bezier(0.32,0.72,0,1)`；时长 `fast .14 · base .2 · slow .32`（产品态 150–250ms）。
  动效**只表达状态**（状态变化 / 反馈 / loading / reveal），不做装饰，不做整页入场编排。
  `prefers-reduced-motion` 全局兜底关闭过渡 / 动画。

## 5. 组件词汇

- **Liquid Glass 仅用于 chrome**：侧栏 / 顶栏 / 弹层用半透磨砂折射底层柔光背景；**数据卡 / 表格保持不透明白底**（读数可信优先）。
- **`<StatusDot>`**（`components/StatusDot.tsx`）：全站状态圆点唯一实现。`status` 自动取色，`stale` 灰化 + 柔环，
  `pulse` 活跃脉冲（reduced-motion 自动关）。**不要再手搓 `<span style={{width:8,height:8,borderRadius:4}}/>`。**
- **Hero 卡（`.am-hero`）**：白底 + 角落极淡语义晕染 + 语义色图标 chip + 36/680 近黑数字。数字是视觉重心。
- **页面标题**：由 `MainLayout` 顶栏统一渲染（菜单 `title/subtitle`），页面体内一般不再自带 H1。
- 每个交互组件齐备：default / hover / focus / active / disabled / loading。键盘 `focus-visible` 走品牌色环。

## 6. 绝对禁止（在本项目语境下）

- 散落硬编码 hex（用令牌）。第二种"功能蓝"（只有 indigo）。同义异色（4 种绿之类）。
- 渐变文字——**唯一例外**是侧栏 `.am-wordmark` 字标。
- 玻璃材质做装饰（只在 chrome）。彩底上读关键数字（Hero 数字回白底近黑）。
- 承载信息的文字低于 `ink-3` 对比。状态只靠颜色传达（配标签 / 图标 / 形状）。
- 内容溢出容器（长串折行、宽表自身滚动；详见 PRODUCT.md「不越界」）。

## 7. 加新页 / 新组件时

1. 颜色从令牌取，DOM 用 `var(--am-*)`，ECharts 用 `tokens.ts` 的 JS 值。
2. 状态圆点用 `<StatusDot>`；语义提示用 `semantic.<x>` 四件套；要"不同色"用 `accent.<x>`。
3. 间距走 8pt（`--am-sp-*`），圆角 / 投影 / 缓动用令牌。
4. 校验：`pnpm exec tsc -b` 通过；对照本文件第 6 节自查。
