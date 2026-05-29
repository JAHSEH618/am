# AI 协作分析报告 · 设计文档 · v3.0

> 配套设计：`./aiwatch-design-v2.0.md`
> 前置版本：AIWatch v2.2+（`ai_session` / `ai_session_message` / `ai_session_event` / `git_commit` / `daily_summary` 五张表已稳定）
> 文档定位：**给唯一的管理员账号做开发人员能力洞察的方法论 + 落地方案**
> 与现有「员工画像」的关系：**新增独立页面 `/analysis`，保留 `/people` 不动**。员工画像继续承担"日常活跃 / 行为标签"的描述性视图；分析报告承担"按窗口的深度能力评估"。
> 触发方式：**管理员前端选时间窗口 → 点击生成 → 后端跑 LLM + 聚合 → 出报告**，默认窗口为本自然周。

---

## 0. 这套方案的边界（一定要先读）

### 0.1 谁用、用来干什么

* **使用者**：唯一的管理员账号（线下即公司管理者 / CTO 本人）
* **目的**：辅助识别——
  1. 谁在 AI 协作上**真正高产**（杠杆型用户）；
  2. 谁需要培训（高 token + 低产出 + 高重试）；
  3. 谁的协作模式有**风险信号**（产出回滚率高、长期高难度任务低完成率）；
  4. 团队整体在哪些方向上协作有空白。
* **不是**：绩效打分系统，也不替代 code review / 1-on-1。它产生的是**洞察（insight）**，由人解读和决策。

### 0.2 形态：按需触发的"分析报告"，不是常驻仪表盘

* 管理员前端选时间窗口（默认本自然周）→ 点击「生成报告」→ 后端任务异步跑 → 完成后展示
* 报告按窗口落盘缓存（同窗口再点击秒级返回）
* 会话级 LLM 审计结果**永久缓存**（`ai_session_audit`），跨窗口复用，不重复跑

### 0.3 不分岗位

公司全员开发，**不做岗位分群**，相对化基线就是"窗口内的全员 P25 / P50 / P75"。任务难度差异靠 **LLM 全量难度评分**做归一化。

### 0.4 全量 LLM 审计（不抽样）

* 预算充足，窗口内每个会话进 pipeline 跑 LLM judge
* 双模型互判，置信度过低进 `judge_disagreement` 标记
* 这解决了"抽样过拟合"的最大短板

### 0.5 单一管理员账号 → 不做角色矩阵

* 权限只有"管理员"一档；员工不登录看不到任何数据
* 不做"本人视图 / 申诉 / 访问审计"等合规模块（公司线下会议向员工说明数据采集范围）

### 0.6 数据分析仍然有**技术性**陷阱（必须先理解）

下面这些是纯技术问题，不解决就是"看似科学的玩具"，**管理员每次打开报告前必须先理解**：

| 技术陷阱 | 本方案怎么压住 |
| --- | --- |
| **Goodhart 法则** | 不向员工公开指标；管理员不在公开场合截图传播 |
| **提问能力 ≠ 业务能力** | 提问质量仅作为协作模式描述，**最终评价靠任务完成率 + 产出验证** |
| **LLM judge bias** | 双模型互判，差异 > 阈值丢弃；rubric 显式禁止"礼貌/长度/中英文"加分 |
| **任务难度不可比** | **LLM 全量难度评分 1-5**，所有指标按难度加权 |
| **幸存者偏差**：高级工程师不用 AI 反被判沉默 | 会话少且 `git_commit` 产出正常 → 列入"沉默+产出常"白名单，不进负面 watchlist |
| **单点指标对抗** | 所有结论靠**会话数据 + git 产出**双源 |
| **小样本不可信** | 窗口内会话 < 30 或员工窗口内活跃天 < 5 → 不出该员工详细画像，仅列基本量 |

---

## 1. 数据可信度分级

| 等级 | 信号 | 来源 | 用法 |
| --- | --- | --- | --- |
| **强** | 会话发生与时长、Token、AI 协作时长 | `ai_session` / `daily_summary` | 直接进图表 |
| **强** | AI 协助提交数、行数、回滚率 | `git_commit` | **唯一进入"能力评价"的硬指标族** |
| **中** | LLM 全量评判：任务难度、会话结果、能力维度 | `ai_session_audit`（新表） | 加权 / 归一化基础 |
| **弱** | 消息数、首响时延、重试次数 | `ai_session` / `daily_summary` | 仅在描述性视图，不进评价模型 |
| **噪音** | 措辞 / 礼貌 / 表情 / 工作时段 / 模型偏好 | — | **明确不进任何指标**（见附录 A） |

---

## 2. 指标体系（两层）

### 2.1 L1 · 描述层（团队 / 个人活动量）

> 回答"窗口内发生了什么"。不评判。

```text
ai_active_hours          AI 协作时长（小时）
                         口径：报告窗口内每个自然日的 daily_summary.ai_active_seconds_union 求和，
                         与员工画像（/people）一致（DailySummaryAggregator 信号流 + 日区间并集）。
session_count            会话数
total_tokens             Token 总量（in + out）
ai_commit_count          AI 协助 commit 数
ai_lines_added           AI 协助新增行数
top_models               Top 3 模型 token 占比
top_tools                Top 工具调用
top_projects             Top 项目 / repo
```

### 2.2 L2 · 评判层（能力洞察 + 产出验证）

> 回答"做得怎么样"。**所有指标需 LLM judge + git_commit 双源**。

#### A. 任务难度分布（LLM 全量评分）

```text
difficulty_dist          1-5 难度各档会话数
                         1 = trivial（typo / 格式 / 简单查询）
                         2 = simple（小函数 / 单文件 / 知识问答）
                         3 = moderate（多文件 / 调试 / 需要理解上下文）
                         4 = hard（架构调整 / 性能优化 / 复杂调试）
                         5 = expert（跨模块设计 / 难复现 bug / 新技术调研）
```

#### B. 任务完成率（按难度加权）

```text
completion_rate_by_difficulty
  难度 d 上的完成率 = (LLM 判定"completed"的会话数) / (该难度总会话数)
  加权总分 = Σ (completion_rate_d × weight_d)
  权重：1→1, 2→2, 3→4, 4→8, 5→16

会话结果三分类：
  · completed   用户最后一条表达接受 / 没再追问相关问题
  · partial     改换思路 / 还在迭代但未明确放弃
  · abandoned   会话挂起 / 用户开新会话谈不同主题 / 显式放弃
```

#### C. 能力维度评分（5 维，LLM 全量评分，每会话各 1-5 分）

```text
problem_decomposition     需求拆解：把复杂问题分成可执行小步的能力
context_management        上下文管理：提供代码 / 错误 / 约束让 AI 理解
debugging_skill           调试能力：定位问题、读懂错误、设计验证步骤
tool_orchestration        工具编排：合适工具 + 适时切换模型
self_correction           自我纠错：发现 AI 走偏时及时调整方向（而非死磕）
```

> **窗口聚合规则**：5 个维度的窗口合成值是**该员工窗口内所有会话的难度加权均值**，不是简单平均。难度 3 以上的会话权重远高于难度 1。

#### D. 协作模式分类（LLM 全量分类，会话级单选）

```text
mode_distribution
  · leverage    杠杆型：用 AI 加速明确任务，会话短、结果明确、产出落地
  · learning    学习型：新领域探索 / 问 AI 解释概念
  · dependent   依赖型：基础问题问 AI、缺乏自主判断、卡壳重复
  · exploratory 探索型：开放式 brainstorm / 架构讨论 / 选型对比
  · debugging   纯调试：粘错误 → 试方案 → 粘新错误的循环
```

#### E. 产出验证（git_commit 三角验证）

```text
ai_commits_per_active_hour      AI 协助 commit / AI 协作小时
ai_lines_per_1k_token           AI 协助每千 token 落地代码行数
commit_revert_rate              AI 协助提交 7 天内被 revert / 大改的比例
high_difficulty_commit_ratio    LLM 判定"难度≥4 会话"对应的 commit 占比
```

#### F. 综合分位（仅作排序，不展示绝对值）

```text
composite =  0.40 × completion_rate_weighted
           + 0.30 × capability_avg (5 维度均值，难度加权)
           + 0.30 × productivity_index  (ai_commits_per_hour 在窗口内的百分位)

展示形式：前 25% / 中 50% / 后 25%（不展示绝对数）
```

---

## 3. Watchlist · 窗口级异常信号

> 单一窗口分析，所有 watchlist 在该窗口内判定。**"连续 N 周"类规则不在 v3.0 出现**，因为不知道用户会选什么窗口。

| 信号 | 触发条件（窗口内） | 含义 | 建议动作 |
| --- | --- | --- | --- |
| **高投入低产出** | `ai_active_hours` ≥ 团队 P75 且 `ai_commits_per_active_hour` ≤ 团队 P25 | 时间花了但没落地 | 1-on-1 了解任务难度 / 是否卡死 |
| **高回滚率** | `commit_revert_rate > 25%` 且窗口内 commit ≥ 5 | AI 出的代码质量没把好关 | 加 code review 强度 |
| **任务难度持续偏低** | 难度 1-2 占比 > 80% 且 session_count ≥ 30 | 接的都是小活，可能被边缘化或主动避难 | 任务分配复盘 |
| **依赖型模式偏高** | `mode_dist.dependent > 35%` 且窗口内 session ≥ 30 | 工作方式偏依赖 | 关心 + 任务难度复盘 |
| **完成率显著偏低** | `completion_rate_weighted ≤ 团队 P15` 且窗口内 session ≥ 30 | 遇到瓶颈 | 主动了解 |
| **沉默 + 产出正常** ⚪ | `session_count ≤ 团队 P10` 且 `ai_commits_per_active_hour ≥ 团队 P50` | **正面信号**：高自主性的高级工程师 | 邀请做内部 AI 协作分享 |

> 若需要"环比"信号（与上窗口对比），由报告界面提供"对比上一相同长度窗口"的视图，**在前端做对比，不进 watchlist 规则**。

---

## 4. 全量 LLM 分析管线

### 4.1 触发与缓存策略

```
管理员点「生成报告」
   ↓
[查 analysis_report]  同窗口已存在 completed 报告？
   ├─ 是 → 直接展示
   └─ 否 → 创建新 report（status=pending）→ 返回 report_id
          ↓
   [取窗口内涉及到的所有 ai_session]（last_activity ∈ [from, to)）
          ↓
   [逐 session 检查 ai_session_audit]
     · 没审计过 → 进入审计队列
     · 审计过但 total_messages 增长 > 20 → 重审
     · 审计过且消息未显著增长 → 直接复用
          ↓
   [并发跑双 judge LLM]  status=running
          ↓
   [一致性检查 + 落 ai_session_audit]
          ↓
   [ReportAggregator]  按窗口聚合每个用户 + 团队
          ↓
   [WatchlistEvaluator]  窗口内 P25/P75 基线 + 6 类信号判定
          ↓
   [HighlightSessionPicker]  每个用户挑 6 个典型会话
          ↓
   [写 analysis_report + analysis_report_user]  status=completed
```

### 4.2 增量优势

* `ai_session_audit` 按会话级永久缓存；后续任意窗口（即便有大量重叠）只跑新增 / 显著变化的会话
* 同一窗口重复点「生成」秒级返回（命中报告缓存）
* 管理员手动选"刷新"才会强制重跑该窗口

### 4.3 双判一致性

| 维度 | 一致性 |
| --- | --- |
| 难度（1-5） | `abs(A-B) ≤ 1` |
| 结果三分类 | `A == B` |
| 5 维度评分 | `abs(A_d - B_d) ≤ 1` for all d |
| 协作模式 | `A == B`；否则用难度加权选更接近的一个 |

不一致样本**写入 audit 表但标记** `judge_disagreement=1`，聚合时权重 × 0.3。

### 4.4 量级估算

* 100 人 × 日均 8 会话 × 7 天 ≈ 5,600 会话/窗口
* 双 judge → ≈ 11,200 次 LLM 调用 / 窗口
* 首次窗口冷启动一次跑完，后续相同区间复用 → 极快
* 跨窗口（如管理员先选本周、又选本月）只需补差

---

## 5. 前端：单一新页 `/analysis`

### 5.1 菜单

`MainLayout.tsx` 在「员工画像」之后插入：

```ts
{ key: '/analysis', icon: <FileSearchOutlined />, label: '分析报告',
  title: '分析报告',
  subtitle: '基于全量 LLM 审计的能力洞察 + 产出验证（按时间窗）' },
```

> 旧的 `/people` 完全保留。

### 5.2 页面结构

```
┌──────────────────────────────────────────────────────────────────────┐
│ 时间窗：[本周▼] [上周] [本月] [自定义日期区间] [↻ 重新生成]          │
│ 状态：已就绪（生成时间 2026-05-10 18:32） / 生成中 35/56 会话 / 失败│
├──────────────────────────────────────────────────────────────────────┤
│ ┌─ 团队级摘要 ────────────────────────────────────────────────────┐ │
│ │ 活跃员工 23 / 总计 30 | 总会话 1,847 | AI 协助 commit 132     │ │
│ │ 难度分布柱图 + 协作模式饼图 + 完成率分布                       │ │
│ │ Watchlist 触发员工 ▶ 4 人                                       │ │
│ └────────────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────────────┤
│ ┌─ 员工列表（默认排序：综合分位降序） ────────────────────────────┐│
│ │ 员工   分位  完成率  AI协作h  commit  回滚率  watchlist           ││
│ │ alice  前25% 78%     42.3     31      6%      —                   ││
│ │ bob    中50% 61%     18.5     7       4%      —                   ││
│ │ carol  后25% 35%     56.0     2       22%     ⚠ 高投入低产出      ││
│ │ dave   中50% —       2.1      8       —       ⚪ 沉默+产出常       ││
│ └────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

点击某员工 → 右侧 / 抽屉 / 跳转详情：

```
┌─ alice / A001 / 2026-05-04 ~ 2026-05-10 ──────────────────────────────┐
│ Tab 1: 能力雷达     五维雷达 + 团队 P50 参考圈                         │
│ Tab 2: 任务难度     难度 1-5 堆叠柱：completed/partial/abandoned 三色  │
│ Tab 3: 协作模式     5 类饼图                                           │
│ Tab 4: 产出验证     commits/hour 时序 + 团队 P50 基线                  │
│ Tab 5: Top 项目模型工具                                                │
│ Tab 6: 典型会话样本 ★ 6 条代表性会话（高难度完成 / 高能力 / 难度高但放弃）│
│         点击 → 跳现有 /sessions/:id                                    │
└────────────────────────────────────────────────────────────────────────┘
```

### 5.3 时间窗逻辑

* 默认本自然周（周一 00:00 ~ 当前）
* 快捷：本周 / 上周 / 本月 / 上月 / 最近 7 天
* 自定义：DateRangePicker 自由选择
* 时间窗判定：`ai_session.last_activity ∈ [from, to)`

### 5.4 生成状态可视化

报告生成是异步任务，前端轮询：

```
status = pending    报告刚创建，未开始
status = running    LLM 审计进行中（含进度 audited_count / total_count）
status = completed  完成，展示数据
status = failed     失败，展示错误 + 重试按钮
```

---

## 6. 数据模型

> **现有任何表都不改**。新增 3 张表。

### 6.1 `ai_session_audit` —— 会话级永久缓存（核心新表）

```sql
CREATE TABLE IF NOT EXISTS ai_session_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ai_session_id BIGINT NOT NULL,
  user_code VARCHAR(64) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  audit_version VARCHAR(16) NOT NULL,        -- 'v3.0'，rubric 版本号
  judge_a_model VARCHAR(64) NOT NULL,
  judge_b_model VARCHAR(64) NOT NULL,
  difficulty TINYINT NOT NULL,               -- 1-5（双判均值四舍五入）
  difficulty_a TINYINT NOT NULL,
  difficulty_b TINYINT NOT NULL,
  outcome VARCHAR(16) NOT NULL,              -- completed/partial/abandoned
  mode VARCHAR(16) NOT NULL,                 -- leverage/learning/dependent/exploratory/debugging
  cap_problem_decomposition TINYINT NOT NULL,
  cap_context_management TINYINT NOT NULL,
  cap_debugging_skill TINYINT NOT NULL,
  cap_tool_orchestration TINYINT NOT NULL,
  cap_self_correction TINYINT NOT NULL,
  judge_disagreement TINYINT NOT NULL DEFAULT 0,
  judge_reason_text MEDIUMTEXT,              -- LLM 给出的简短解释（人工 spot-check 用）
  message_count_at_audit INT NOT NULL,       -- 审计时会话的消息数（重审判定）
  audited_time DATETIME NOT NULL,
  created_time DATETIME NOT NULL,
  UNIQUE KEY uk_session (ai_session_id),
  KEY idx_user_time (user_code, audited_time),
  KEY idx_difficulty (difficulty)
);
```

**重审逻辑**：报告生成时若 `ai_session.total_messages > ai_session_audit.message_count_at_audit + 20`，重新评判并 UPDATE 旧行（不增行）。

### 6.2 `analysis_report` —— 窗口级报告元数据（含团队级 payload）

```sql
CREATE TABLE IF NOT EXISTS analysis_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  window_from DATE NOT NULL,                 -- 时间窗起（含）
  window_to DATE NOT NULL,                   -- 时间窗止（不含）
  report_version VARCHAR(16) NOT NULL,       -- 'v3.0'
  rubric_version VARCHAR(16) NOT NULL,       -- 'v3.0'
  status VARCHAR(16) NOT NULL,               -- pending/running/completed/failed
  audited_count INT NOT NULL DEFAULT 0,      -- 已审计会话
  total_count INT NOT NULL DEFAULT 0,        -- 窗口内总会话
  error_text MEDIUMTEXT,                     -- 失败原因
  -- 团队级 payload（completed 后写入）
  active_user_count INT,
  total_session_count INT,
  total_active_hours DECIMAL(10,2),
  total_ai_commit INT,
  team_difficulty_dist_json JSON,            -- {"1":120,"2":340,"3":890,...}
  team_mode_dist_json JSON,
  team_completion_dist_json JSON,            -- {"completed":0.62,"partial":0.25,"abandoned":0.13}
  team_percentiles_json JSON,                -- {"completion_rate":{"p25":0.4,"p50":0.6,"p75":0.78}, "ai_commits_per_active_hour":{...}, ...}
  watchlist_summary_json JSON,               -- {"high_invest_low_output":["A001","A007"],"high_revert":["A012"], ...}
  started_time DATETIME,
  completed_time DATETIME,
  created_time DATETIME NOT NULL,
  updated_time DATETIME NOT NULL,
  UNIQUE KEY uk_window (window_from, window_to),
  KEY idx_status (status)
);
```

### 6.3 `analysis_report_user` —— 窗口 × 用户级画像

```sql
CREATE TABLE IF NOT EXISTS analysis_report_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  report_id BIGINT NOT NULL,
  user_code VARCHAR(64) NOT NULL,
  -- 基本量
  session_count INT NOT NULL,
  ai_active_hours DECIMAL(8,2) NOT NULL,
  total_tokens BIGINT NOT NULL,
  ai_commit_count INT NOT NULL,
  ai_lines_added BIGINT NOT NULL,
  -- 难度
  difficulty_dist_json JSON,
  avg_difficulty DECIMAL(3,2),
  high_difficulty_ratio DECIMAL(5,4),
  -- 结果
  completion_rate DECIMAL(5,4),
  abandoned_rate DECIMAL(5,4),
  -- 5 维能力
  cap_problem_decomposition DECIMAL(3,2),
  cap_context_management DECIMAL(3,2),
  cap_debugging_skill DECIMAL(3,2),
  cap_tool_orchestration DECIMAL(3,2),
  cap_self_correction DECIMAL(3,2),
  -- 模式
  mode_dist_json JSON,
  -- 产出
  ai_commits_per_active_hour DECIMAL(8,4),
  ai_lines_per_1k_token DECIMAL(8,4),
  commit_revert_rate DECIMAL(5,4),
  high_difficulty_commit_ratio DECIMAL(5,4),
  -- 综合
  composite_score DECIMAL(6,2),              -- 内部排序用，不展示
  composite_percentile DECIMAL(5,2),         -- 0-100，展示分位标签用
  -- 标记
  watchlist_flags_json JSON,                 -- ["high_invest_low_output","high_revert"]
  -- 样本
  highlight_session_ids JSON,                -- Tab 6 的 6 个典型会话 id
  -- 数据可信度
  insufficient_data TINYINT NOT NULL DEFAULT 0,  -- 1 = 样本太少不出详情
  created_time DATETIME NOT NULL,
  UNIQUE KEY uk_report_user (report_id, user_code),
  KEY idx_report_perc (report_id, composite_percentile),
  CONSTRAINT fk_report FOREIGN KEY (report_id) REFERENCES analysis_report(id) ON DELETE CASCADE
);
```

### 6.4 不动现有任何表

* `daily_summary` / `behavior_tag` 等 v2.1 字段保留——`/people` v1 员工画像继续用
* `git_commit` / `ai_session*` 完全沿用
* 新功能挂在新 controller，与现有代码物理隔离

---

## 7. 服务端模块

```
com.am.server.insight  (新建包)
  ├─ audit
  │   ├─ SessionAuditService       窗口级触发：扫窗口内 session → 决定是否需要审计 / 重审
  │   ├─ JudgeClient               LLM 调用客户端（双模型，并发 + 重试）
  │   ├─ RubricLoader              加载 rubric prompt 模板（YAML 在 resources/insight/）
  │   └─ AuditConsistencyChecker   双判一致性 + judge_disagreement 标记
  ├─ aggregate
  │   ├─ ReportAggregator          按窗口聚合：写 analysis_report + analysis_report_user
  │   ├─ HighlightSessionPicker    每个用户挑 6 个典型会话
  │   ├─ WatchlistEvaluator        6 类窗口级 watchlist 规则
  │   └─ PercentileCalculator      窗口内全员 P25/P50/P75 计算
  ├─ orchestrator
  │   └─ AnalysisReportOrchestrator
  │         编排：拿到窗口 → 检查报告缓存 → 触发 audit → 完成后 aggregate
  │         异步执行（@Async + 状态机驱动 pending→running→completed/failed）
  └─ web
      ├─ AnalysisReportController            管理端 API（见 §7.2）
      └─ AnalysisReportProgressController    生成进度查询
```

### 7.1 关键 API（全部走 `AdminTokenInterceptor`）

```
POST /api/v1/admin/analysis/generate?from=2026-05-04&to=2026-05-11
  · 若同窗口已有 completed 报告 → 返回 report_id（不重跑）
  · 否则创建新报告（pending）→ 异步触发 orchestrator → 返回 report_id

POST /api/v1/admin/analysis/generate?from=...&to=...&force=true
  · 强制重跑：删除该窗口已有用户明细行，报告回到 pending；下游对该窗口内**所有会话**忽略 `ai_session_audit` 缓存并重新执行双 judge，再全量聚合。

GET  /api/v1/admin/analysis/{reportId}
  · 报告详情：团队级摘要 + 用户列表

GET  /api/v1/admin/analysis/{reportId}/users/{userCode}
  · 单员工详情：6 个 tab 数据

GET  /api/v1/admin/analysis/{reportId}/progress
  · 生成进度：{status, audited_count, total_count, eta_ms}

GET  /api/v1/admin/analysis?from=...&to=...
  · 查询是否已有该窗口的报告（前端进页面时探测用）

GET  /api/v1/admin/analysis/history?page=...
  · 历史报告列表（已生成过的所有窗口）
```

### 7.2 失败与重试

* LLM 调用失败 → 指数退避重试 3 次 → 仍失败 → 单条 session 标记审计跳过（不阻塞整个报告）
* 整体报告超 50% 会话审计失败 → `status=failed` + `error_text`，前端展示重试按钮
* `force=true`：对该窗口内所有会话**忽略会话级审计缓存**，全量重新双 judge（仍受 `aiwatch.insight.max-llm-calls-per-report` 预算上限约束，超出则截断队列）；普通「生成」仍复用 `ai_session_audit` 仅补审新增或会话显著变长（≥ `reaudit-message-threshold`）的样本

---

## 8. 落地路径

| 阶段 | 范围 | 状态 |
| --- | --- | --- |
| **v3.0-design** | 本设计文档发布 | 📝 当前 |
| **v3.0-α** | 3 张新表 schema + SessionAuditService + Rubric YAML + 离线试跑近 1 周历史数据 | 📅 |
| **v3.0-β** | AnalysisReportOrchestrator + ReportAggregator + WatchlistEvaluator + 列表 API（先不上详情）| 📅 |
| **v3.0-γ** | `/analysis` 前端：时间窗 + 团队摘要 + 员工列表 + 异步进度条 | 📅 |
| **v3.0-rc** | 详情 6 个 Tab + HighlightSessionPicker + 错误重试链路 | 📅 |
| **v3.1** | 累积 4-6 周数据后基于双判一致率回炉 rubric；新增「对比上一窗口」视图 | 📅 |

---

## 9. 自检：这次方案为什么可靠

| 问题 | v3.0 做法 |
| --- | --- |
| 任务难度不可比 | **LLM 全量难度评分 1-5**，所有指标按难度加权 |
| 抽样过拟合 | **全量审计 + 双 judge 互验 + 会话级永久缓存** |
| LLM judge bias | **双判 + 一致性自动剔除 + rubric 显式禁止伪信号加分** |
| 单点指标对抗 | **三角验证 + 典型会话样本**（管理员眼见为实） |
| 高级工程师误判 | **"沉默+产出常"列为正面 watchlist** |
| 管理员看不过来 | **团队摘要 + watchlist + 6 条典型会话**：不必读全量 |
| 小样本噪声 | 会话 < 30 或 commit 极少 → `insufficient_data=1`，详情页只展示基本量 |
| 跨窗口重复成本 | **`ai_session_audit` 永久缓存**，跨窗口零重复 |

诚实承认仍未解决的两个问题：

1. **Goodhart 长期对抗**：员工知道有审计后行为会变形。靠"不公开个人指标"压制，做不到根除。
2. **难度评分本身的 bias**：LLM 对自己擅长领域可能低估难度。靠**双模型分别擅长不同领域 + 不一致样本人工 spot-check** 缓解。

最终原则一句话：**系统不替你下结论，给你看异常 + 6 条样本，由人去解读和决策。**

---

## 10. 实施盘点（落地清单）

### 10.1 新增页面：**1 个**

| 页面 | 路由 | 菜单位置 | 说明 |
| --- | --- | --- | --- |
| **分析报告** | `/analysis` | 「员工画像」之后 | 时间窗选择器 + 异步生成 + 团队摘要 + 员工列表 + 详情 6 Tab |

> `/people` 员工画像 v1 **完全保留不动**。

### 10.2 新增表：**3 张**

| 表 | 主键维度 | 行级缓存 |
| --- | --- | --- |
| `ai_session_audit` | 会话 | **永久**（跨窗口复用） |
| `analysis_report` | 窗口 | 永久（管理员手动重跑前不失效） |
| `analysis_report_user` | 窗口 × 用户 | 与 report 共生命周期（cascade） |

### 10.3 不动现有任何表

* `daily_summary` / `git_commit` / `ai_session*` / `behavior_tag` 等全部保留

### 10.4 新增后端模块

* 1 个新 package：`com.am.server.insight`
* 9 个新类（见 §7）
* 6 个新 API（见 §7.1）
* 全部走 `AdminTokenInterceptor` 鉴权（管理员 token 已有）

### 10.5 LLM 资源依赖

| 依赖 | 选型 | 是否需要你提供 |
| --- | --- | --- |
| Judge A 模型 | 自建本地（Qwen2.5-72B / DeepSeek-V3）| **需要：推理端点 + 鉴权方式** |
| Judge B 模型 | 外部 API（GPT-4o-mini / Claude-Sonnet）| **需要：API key + 速率限制** |
| Rubric YAML | 内置在 `server/src/main/resources/insight/rubric-v3.0.yaml` | 我起草 + 你审 |

### 10.6 你仍然需要拍板的几个点

| # | 决策 | 默认建议 |
| --- | --- | --- |
| 1 | Judge A 模型端点 + 鉴权 | — |
| 2 | Judge B 模型 API key + 速率上限 | — |
| 3 | 首次上线后**主动回填**历史多长？（生成 4 个"已结束周"的报告） | 4 周 |
| 4 | 触发"重新生成"的条件：管理员手动 only? 还是允许"日终自动刷新本周"? | 管理员手动 only（更可控） |
| 5 | Rubric 案例库：要不要让 1 位资深工程师标 30 个真实会话作为 few-shot？ | 建议做，双判一致率从 ~70% → ~85%+ |
| 6 | 报告失败时 LLM 调用费如何控制：单报告调用上限多少次？ | 单报告硬上限 50,000 次 LLM 调用，超出截断 |

---

## 附录 A · 明确不进入指标体系的伪信号

| 伪信号 | 拒绝理由 |
| --- | --- |
| 对话礼貌 / 措辞 | 与能力无关 |
| 提问字数 / 会话长度 | 高手提问短、低手提问长 |
| 中 / 英文使用 | 工作语言习惯不评判 |
| 工作时段 / 周末活跃 | 工作节奏不评判 |
| 模型偏好（喜欢用 X 模型） | 工具偏好不评判 |
| 工具调用次数本身 | 看用对没用对，不看用多少 |
| 单次会话的"成败" | 单点高方差，必须窗口聚合 |
| Token 消耗量本身 | 必须配产出口径才有意义 |

## 附录 B · 出报告前的检查清单（管理员每次打开报告前自查）

- [ ] 时间窗 ≥ 7 天
- [ ] 窗口内活跃员工 ≥ 10（基线统计稳定性）
- [ ] 双 judge 一致样本占比 ≥ 70%（报告页头部展示，否则 rubric 需要回炉）
- [ ] 已有 `git_commit` 双源数据
- [ ] 我**不**会把这份报告对外公开 / 截图传播
- [ ] watchlist 上的人，结合 1-on-1 解读，不直接做决策

任何一项不通过，**不出此报告**或不基于此报告做行动。

## 附录 C · Rubric Prompt 模板（v3.0 草案）

放置：`server/src/main/resources/insight/rubric-v3.0.yaml`

```yaml
difficulty:
  system: |
    你是资深技术经理，正在评估一个开发人员与 AI 编程助手的对话。
    任务：判定这次会话所处理的技术任务难度，1-5 分。
    评分依据：技术深度、上下文复杂度、需要的领域知识。
    禁止：依据对话长度、措辞、礼貌、语言（中/英）评分。
  rubric:
    1: 'trivial：拼写 / 格式 / 简单事实查询'
    2: 'simple：单文件单函数改动 / 知识问答 / 命令查询'
    3: 'moderate：多文件 / 调试 / 需要理解项目上下文'
    4: 'hard：架构调整 / 性能优化 / 复杂调试 / 跨模块'
    5: 'expert：系统设计 / 难复现 bug / 新技术调研 / 大重构'
  output_schema:
    difficulty: int (1-5)
    reason: string (一句话，≤ 50 字)

outcome:
  system: |
    判定会话最终结果：completed / partial / abandoned
    completed：用户最后一条消息表达接受 / 没再追问相关问题
    partial：改换思路 / 还在迭代但未明确放弃
    abandoned：会话挂起 / 用户开新会话谈不同主题 / 显式放弃
  禁止：依据 AI 回答质量评判（这是评开发人员，不是评 AI）

capabilities:
  problem_decomposition: |
    需求拆解能力，1-5 分
    1：单条命令式提问，无明确目标
    3：能把问题分成 2-3 步描述
    5：明确目标、子任务清晰、约束完整
  context_management: |
    上下文管理能力，1-5 分
    1：无代码 / 无错误信息地提问
    3：贴了相关代码片段
    5：贴了代码 + 错误 + 已尝试方案 + 约束
  debugging_skill: ...
  tool_orchestration: ...
  self_correction: ...

mode:
  system: |
    分类本会话主要属于哪种协作模式（5 选 1）：
    leverage / learning / dependent / exploratory / debugging
  ...
```

完整 YAML 在实装期细化。Rubric 是这套系统的**真正核心**，会随数据回炉迭代（v3.1 起记录 rubric_version）。
