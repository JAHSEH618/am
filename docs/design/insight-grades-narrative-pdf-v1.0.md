# 设计：归因等级 v2 + 报告叙事 + PDF 导出 + Token 折线图

日期：2026-07-02
状态：已与需求方确认
范围：server（insight 聚合/叙事/schema）、frontend（Analysis/People/Dashboard、PDF 导出）

## 1. 背景与目标

四项需求：

1. Dashboard「Token 走势（近30天）」与 People 详情「Token 用量」子图改为**折线图**（现为堆叠柱状图）。
2. 优化人员水平归因逻辑：**更科学**（公式重构 + 小样本收缩 + 排除噪声信号），**更直接**（综合分映射明确等级，People 页与 Analysis 页都展示，得分构成透明化）。
3. 分析报告**更全面**：新增 LLM 生成的叙事结论（个人评语 + 团队总评，引用具体会话作论据）与新指标 section。
4. 分析报告可导出为**格式优美的 PDF**（团队整份 + 单人报告，前端 pdfmake 方案）。

## 2. 现状摘要

- 综合分 v1（`ReportAggregator.java`）：`(0.35×能力/5 + 0.35×产出分位 + 0.20×高难度占比 + 0.10×(1−回滚率)) × 100`，前端仅展示 top25/mid50/bottom25 三档分位段，且只在 Analysis 页；People 页无任何水平展示。
- 报告为纯结构化数值 + 图表，无叙事文字；唯一 LLM 自然语言是每会话 ≤80 字的 `judge_reason_text`。
- 导出仅有前端 xlsx（SheetJS 懒加载）；前后端均无 PDF 能力。
- 已采集未用于归因的数据：`daily_summary.ai_retry_count / ai_thinking_seconds / ai_first_response_avg_ms / tool_call_count`、`ai_session_event` 细粒度事件流。
- 图表全站统一 ECharts 5.5 + echarts-for-react。

## 3. 设计 A：归因逻辑 v2

### 3.1 等级体系

综合分 0–100 映射五档等级，全站统一展示：

| 等级 | 分数区间 | 含义 |
| --- | --- | --- |
| S | ≥85 | 专家级 |
| A | 70–84 | 高级 |
| B | 55–69 | 中级 |
| C | 40–54 | 初级 |
| D | <40 | 待提升 |

- 窗口内会话数 <10：维持现状判 `insufficient_data`，不评级不评分。
- 会话数 10–19：正常评级，`composite_confidence = 'low'`（前端标注「低置信度」）。
- 会话数 ≥20：`composite_confidence = 'normal'`。

### 3.2 公式 v2（五维加权）

各维度子分归一化到 [0,1] 后加权：

| 维度 | 权重 | 口径 |
| --- | --- | --- |
| 能力 cap | 0.35 | 难度加权五维能力均值 / 5（沿用现有口径，含 judge_disagreement ×0.3 降权） |
| 产出效率 output | 0.25 | 每 AI 活跃小时 commit 数的团队分位（沿用现有分位机制） |
| 完成质量 quality | 0.20 | 0.6×completion_rate + 0.4×(1−commit_revert_rate) |
| 高难挑战 challenge | 0.15 | 难度≥4 **且 outcome=completed** 的会话占比（完成高难，而非仅接高难） |
| 独立成熟度 independence | 0.05 | (leverage 模式占比 − dependent 模式占比 + 1) / 2 |

`raw = (0.35×cap + 0.25×output + 0.20×quality + 0.15×challenge + 0.05×independence) × 100`

### 3.3 小样本收缩（经验贝叶斯）

`final = w×raw + (1−w)×team_mean_raw`，其中 `w = n/(n+10)`，n = 该员工窗口内已审计会话数，`team_mean_raw` = 全部非 insufficient 员工 raw 分均值。等级由 `final` 映射。避免样本少的人得到极端等级。

### 3.4 明确排除的信号（防噪声）

- `ai_first_response_avg_ms`、`ai_thinking_seconds`：模型/服务属性，不是人的属性，**不入分**。
- token 用量：投入量不是水平，**不入分**。
- `ai_retry_count`：语义不够干净（可能含网络重试），**只做展示指标，不入分**。

### 3.5 透明化

每人存储 `composite_breakdown_json`：

```json
{
  "formula_version": "v2",
  "dimensions": [
    {"key": "cap", "label": "能力", "weight": 0.35, "score": 0.72, "team_p50": 0.65},
    {"key": "output", "label": "产出效率", "weight": 0.25, "score": 0.58, "team_p50": 0.50}
  ],
  "raw": 68.4, "shrink_weight": 0.71, "team_mean": 61.2, "final": 66.3
}
```

UserDetail 新增「得分构成」卡片（五维条形 + 权重 + 团队 P50 对比），直接回答「为什么是这个等级」。

### 3.6 存储与展示

- `analysis_report_user` 新列：`composite_grade VARCHAR(2)`、`composite_confidence VARCHAR(8)`、`composite_breakdown_json`（JSON 文本列）。
- `analysis_report` 新列：`team_grade_dist_json`（S–D 各档人数）。
- 变更走 `schema.sql` + insight 的 `*SchemaPatches`（幂等加列）；**历史报告不回算**，旧报告 `composite_grade` 为 NULL，前端回退到原分位段展示。
- `composite_score` / `composite_percentile` 字段保留（含义变为 v2 final 分），DTO 兼容。
- People 页：列表新增「等级」列，详情头部新增等级徽章 + 综合分 + 置信度 + 数据来源窗口说明。数据取该用户**最近一份 completed 报告**的 `analysis_report_user` 行；无报告则显示「未评估」。People 的 web 适配层读取 insight 的 domain repository（web→domain 方向，符合 ArchUnit 边界）。
- Analysis 页：员工列表新增等级列（Tag 配色随等级）；TeamOverview 新增「等级分布」卡片。
- rubric v3.x 与会话审计不动，**无需重跑 LLM 审计**；等级在聚合层重算，下一次生成/强制重跑报告即生效。

## 4. 设计 B1：叙事结论（LLM 生成）

聚合完成后新增叙事阶段，新增 `insight` 子包 `narrative`（`ReportNarrativeService`），复用现有 OpenAI 兼容 judge client（judgeA 配置）。

### 4.1 个人评语（每人 1 次调用）

- 输入画像：该员工窗口统计（等级、五维子分、分布、watchlist flags）+ **Top 5 典型会话上下文卡**（项目、日期、难度、outcome、mode、双 judge 评审理由原文）。出网前经 `SecretRedactor` 脱敏。
- 输出：200–300 字结构化 JSON，五字段（值为中文）：

```json
{
  "level_summary": "水平定位，一句话定性（结合等级与团队分位）",
  "evidence": "典型表现，必须引用具体会话作论据（日期/项目/难度/结果）",
  "strengths": "优势",
  "weaknesses": "短板",
  "suggestions": "发展建议"
}
```

- 存 `analysis_report_user.narrative_json`。

### 4.2 团队总评（1 次调用）

- 输入：团队 KPI、分布、等级分布、watchlist 汇总、亮点员工与其典型会话摘要。
- 输出：400–600 字结构化 JSON 四段：

```json
{
  "overview": "总体水平与趋势",
  "highlights": "亮点（点名具体人和具体会话）",
  "risks": "风险与短板（结合 watchlist）",
  "recommendations": "管理建议"
}
```

- 存 `analysis_report.team_narrative_json`。

### 4.3 运行策略

- 随报告自动生成；调用次数 = 员工数 + 1，独立于审计预算（`max_llm_calls_per_report` 只管审计）。
- 单次调用失败沿用 judge 的 3 次退避重试；最终失败则该 narrative 为 NULL，**报告仍 COMPLETED**，前端不渲染对应卡片。JSON 解析失败同样容忍。
- Prompt 与 rubric 同模式：真值在 sys_config（新 key `insight.narrative_user_prompt`、`insight.narrative_team_prompt`），classpath 文件仅 seed，System Settings UI 可改热生效。

### 4.4 前端渲染

- TeamOverview 顶部「团队总评」卡片，四段式渲染。
- UserDetail 顶部评语卡，五段式（水平定位 / 典型表现 / 优势 / 短板 / 建议）。

## 5. 设计 B2：新指标

- `analysis_report_user` 新列：`retry_count INT`、`retry_per_active_hour`、`tool_call_count INT`（窗口内 `daily_summary` 按人求和）。
- UserDetail 关键统计中展示重试与工具调用指标；「得分构成」卡片见 §3.5。
- TeamOverview「等级分布」卡片见 §3.6。

## 6. 设计 B3：PDF 导出（前端 pdfmake）

### 6.1 依赖与加载

- 新增依赖 `pdfmake`（懒加载 chunk，仿 xlsx 模式，vite 拆独立 chunk）。
- 中文字体：开源 OFL 许可的中文 TTF 子集（如 Noto Sans SC 子集），放前端静态资源，**仅点击导出时 fetch** 并注册进 pdfmake VFS，不进主 bundle。
- 图表转图：ECharts 离屏 `init`（隐藏容器）→ `setOption` → `getDataURL({pixelRatio: 2})` → `dispose`，得到高清 PNG 嵌入 PDF。
- 公共模块 `src/lib/pdf/`（字体加载、离屏渲染、公共样式/页眉页脚），两个文档定义文件：`src/pages/Analysis/exportAnalysisPdf.ts`（团队）、`exportUserPdf.ts`（单人）。

### 6.2 团队报告 PDF（Analysis 页「导出 PDF」按钮，与「导出 Excel」并排）

封面（标题 / 时间窗 / 生成时间 / KPI 摘要）→ 团队总评（四段叙事）→ 等级分布、难度分布、模式分布图 → 分位基线表 → watchlist 表 → 全员明细表（等级 / 综合分 / 五维子分 / 关键指标）。带页眉页脚与页码，表格斑纹配色取自设计 token。

### 6.3 单人报告 PDF（UserDetail 抽屉「导出个人报告」按钮）

头部（姓名 / 等级徽章 / 综合分 / 置信度）→ 评语五段 → 能力雷达图（vs 团队 P50）→ 得分构成 → 难度 / 模式分布 → 关键统计表 → 典型会话摘要。

- 仅报告 status=completed 时可导出；旧报告（无等级/叙事字段）导出时对应章节自动省略。

## 7. 设计 B4：Token 走势折线图

- `Dashboard.tsx` 的 `tokenTrendOption` 与 `People.tsx` PersonDetailPanel 的 `tokenTrendOption`：`type: 'bar'` + `stack` 改为两条独立折线（输入 / 输出 Token），不堆叠；保留现有 tooltip（逐系列 + 合计）、`formatTokensM` Y 轴缩写与配色 token。纯 ECharts option 改动，无 API 变更。

## 8. 错误处理汇总

- 叙事调用/解析失败 → NULL + 报告照常完成（§4.3）。
- 等级计算在 `insufficient_data` 时跳过（现状延续）。
- PDF 导出失败（字体拉取失败、渲染异常）→ toast 报错，不影响页面。
- 历史报告缺新字段 → 前端回退（分位段展示、PDF 章节省略）。

## 9. 测试与验证

- 后端单测：等级映射边界（85/70/55/40）、收缩公式、challenge/independence 口径、低置信度与 insufficient 分支、叙事服务成功/失败/解析失败容忍、SchemaPatches 幂等。`cd server && ./gradlew test`（含 ArchUnit BoundaryTest）。
- 前端：`pnpm build` 通过；PDF 人工核验清单——中文渲染无豆腐块、图表清晰度（2x）、分页无截断、页眉页脚页码正确。
- `./scripts/check-consistency.sh` 通过。
- 文档同步：更新 `insight/CLAUDE.md`（叙事阶段 + 新列）与前端 `CLAUDE.md`（PDF 导出模式）。

## 10. 明确不做（YAGNI）

- 不重跑历史 LLM 审计，不回算历史报告。
- 重试次数 / 思考时长 / 首响应耗时不进综合分公式。
- 不做后端 PDF、不做邮件定时报告。
- People 页不做独立评分计算，只读最近一份 completed 报告。
- rubric v3.x 与双 judge 审计流程不动。
