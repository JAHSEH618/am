# AIWatch 设计文档 · v2.0

> 由原 *ai-work-platform v1.x* 重新定位而来。
> v1.x 历史文档：`./legacy/ai-work-platform-design-v1.2.md` / `./legacy/ai-work-platform-design-v1.3.md`

## 1. 平台定位

**AIWatch — 员工 AI 协作使用情况观测与洞察平台**。

不是工时记录、不是成本核算，而是回答三个核心问题：

| 维度 | 核心问题 | 衡量指标（v2.x 落地） |
| --- | --- | --- |
| **用得多深** | 谁在用、用得多深 | AI 渗透率（北极星）、AI 协作时长、活跃员工数、活跃项目数 |
| **用得多好** | 用得是否高效 | 首次响应时长、卡壳次数、模型选择是否合理、工具调用浪费率 |
| **产出了什么** | AI 真的在创造价值吗 | AI 协助提交数 / 占比、AI 协作时长 / 提交数比、Top 模型 / 工具 |

**显式去除**：成本视角（`cost_usd` / `total_cost_usd` / `cost_delta` 三字段在 v2.0 全部物理 DROP）。
理由：(1) 各 AI 厂商的成本口径在频繁变化，自维护一份失真的成本数没意义；(2) 我们关心的是"投入有没有产出"，而不是"花了多少钱"——前者 token 使用量已能很好刻画。

## 2. 命名（v2.0 重命名清单）

| 维度 | v1.x | v2.0 |
| --- | --- | --- |
| 平台品牌 | ai-work-platform | **AIWatch** |
| 客户端二进制 | ai-work-agent | **aiwatchd** |
| Go module | github.com/am/ai-work-agent | github.com/am/aiwatch-agent |
| Server gradle artifact | ai-work-platform | aiwatch-server |
| Server gradle group | com.am | com.am（不动）|
| Java 包名 | com.am.server | com.am.server（不动，重构成本不值）|
| 前端 npm name | ai-work-platform-web | aiwatch-web |
| 前端 title | ai-work-platform | AIWatch — AI 使用观测与洞察 |
| Hikari pool 名 | ai-work-cp | aiwatch-cp |
| application.name | ai-work-platform | aiwatch |
| 配置 namespace | `ai-work-platform.agent.*` | `aiwatch.agent.*` |
| 客户端配置目录 | `<base>/ai-work-agent/config.json` | `<base>/aiwatchd/config.json`（v2.0 启动时一次性自动迁移）|
| log 前缀 | `[ai-work-agent]` | `[aiwatchd]` |
| 数据库名 | am | am（不动，"am" = AI Monitoring 短码）|
| 环境变量 | `AM_SERVER_URL` / `AM_USER_CODE` / `AM_WATCH_DIR` | 不动（运维侧不需要重新培训）|

## 3. 信息架构（前端菜单 v2.0）

```text
[观测大盘]   /dashboard       团队 AI 渗透率 / Token / 活跃 / 模型 概览
[实时活跃]   /realtime        当前所有 Agent 进行中的会话状态流
[AI 会话]    /sessions        近期所有会话列表与详情（保留 v1.x 主功能）
[员工画像]   /people          每位员工 AI 渗透率 / 协作时长 / 行为标签        ← v2.1
[项目透视]   /projects        以仓库为锚的 AI 协助率与提交关联              ← v2.1
[模型与工具] /models-tools    模型 Token 占比 + 工具调用 Top + 浪费分析     ← v2.0 复用 Tools，v2.1 加模型分布
[报告中心]   /reports         日 / 周 / 月报自动生成 + 自然语言叙述        ← v2.2
[异常告警]   /alerts          Agent 离线 / 签名异常 / 重放运行时告警
```

**菜单顺序设计原则**：

1. 团队级 → 实时 → 个体（员工 / 项目）→ 抽象（模型与工具）→ 输出（报告）→ 治理（告警）。
2. v2.0 只 ship 灰度可见的 placeholder 页（用 `ComingSoon` 组件），明确"这是 v2.x 路线图"，避免菜单点进去白屏。
3. `/cost` / `/tools` 老路由 v2.0 起做 `<Navigate replace>` 重定向到 `/dashboard` / `/models-tools`。

## 4. 数据模型变更

### 4.1 删除字段（DROP COLUMN，幂等）

| 表 | 字段 | 说明 |
| --- | --- | --- |
| `daily_summary` | `total_cost_usd` | 当日 AI 成本估算 |
| `ai_session` | `cost_usd` | 单会话成本估算 |
| `ai_session_event` | `cost_delta` | 事件级成本增量 |

迁移 SQL 用 `information_schema` 包裹的 prepared statement 实现幂等，已写入 `server/src/main/resources/sql/schema.sql` 末尾。

### 4.2 新增字段（ADD COLUMN，幂等）

`daily_summary` 新增以下观测字段，对应 v2.0 健康度 / 产出指标体系：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `ai_active_seconds` | BIGINT | 当日 AI 协作时长（秒）：第一条 user 消息 → 最后一次 assistant 响应的累计窗口 |
| `ai_first_response_avg_ms` | INT | 当日首次响应平均时长（ms） |
| `ai_thinking_seconds` | BIGINT | 当日 thinking 状态累计（秒） |
| `ai_retry_count` | INT | 当日"连续 user 无 assistant 间隔"次数（卡壳 / 不满意） |
| `ai_commit_count` | INT | 当日 AI 协助 git 提交数（依赖 `git_commit` 表） |
| `ai_models_top3` | VARCHAR(256) | Top3 token 消耗模型，JSON 字符串 |

聚合任务在 `DailySummaryAggregator`（待实装于 Phase 2）中按日跑，扫 `ai_session` + `ai_session_event` 即可。

### 4.3 新增表（CREATE TABLE IF NOT EXISTS）

#### `git_commit` —— Phase 3 实装

aiwatchd 周期性扫描"近期 ai_session 涉及到的 repo"的 `git log`，把提交流水上报。
AI 协助判定由服务端基于"提交时间附近 30min 内同 repo 是否有 active session"推断。

```text
id, agent_id, user_code, host_hash, repo_url, commit_hash,
commit_time, author_name, author_email, message_subject,
files_changed, lines_added, lines_deleted, branch_name,
ai_assisted (TINYINT), ai_assist_confidence (0~100),
ai_session_ids (JSON 数组),
created_time, updated_time
UNIQUE KEY (repo_url, commit_hash)
```

#### `usage_report` —— Phase 3 实装

服务端 cron 任务按日 / 周 / 月聚合生成报告。

```text
id, scope (daily/weekly/monthly), scope_key,
user_code (员工级), department (团队级),
payload_json, narrative_md (中文叙述),
status (GENERATED/PUBLISHED), created_time, updated_time
UNIQUE KEY (scope, scope_key, user_code) / (scope, scope_key, department)
```

## 5. 北极星指标：AI 渗透率

**定义**：指定时间窗口内"被识别为 AI 协助产出"的代码占总代码产出的比例。

**实装公式**（v2.1 起）：

```text
ai_penetration = (Σ ai_assisted=1 commits 的 lines_added) / (Σ all commits 的 lines_added)   * 100
```

**v2.0 阶段**：北极星卡显示 `—` 与"v2.1 接入 git_commit 后生效"占位文案；服务端 `DashboardOverviewDto.aiPenetrationPercent` 固定回 `-1`。

**为什么不是"会话数 / 员工数"**：会话本身可以是用户随手开了又没用，不能反映"产出价值"；只有当代码真的进了 commit 才算数。

## 6. 客户端 aiwatchd 变更

### 6.1 配置目录自动迁移

`aiwatchd` 启动时 `config.Load()` 会先尝试新路径 `<base>/aiwatchd/config.json`；
若新路径不存在但 `<base>/ai-work-agent/config.json` 存在，自动复制（保留老文件兜底）。
任何步骤失败都静默跳过，最坏退化为"未注册"，由 `aiwatchd init` 走正常注册。

### 6.2 删除 cost 上报（向后兼容期 1 个版本）

* `monitor.Session.CostUSD` 字段移除。
* 6 个 Provider（cursor / claude / codex / hermes / openclaw / openharness）的 cost 累加 / SQL SELECT 全部删除。
* 服务端 `MonitorSessionDto` 也删除 `costUsd`，但因为 Spring Boot 默认 `fail-on-unknown-properties=false`，老 v1.x 客户端继续上报 `cost_usd` 不会报错——多余字段静默忽略。

### 6.3 Phase 3 新增 gitlog Provider（v2.2 实装）

新增 `agent/internal/monitors/gitlog`：周期性扫描"最近 30 天有 ai_session 的 repo"的 `git log`，
把提交元信息（hash / 作者 / 时间 / files / lines / branch / message）上报到 `/api/v1/agent/report-commits`。

**产出归因（v1.0）**：上报前按 Git **author_email** 过滤——允许的邮箱集合 = 各仓库 `git config user.email`（EffectiveGitEmail）
∪ `config.json` 可选数组 `git_author_emails`。集合为空时该仓库周期内跳过上报并 WARN（避免他人提交误入）。
服务端用 `agent_device.git_user_email` ∪ 请求体 `reported_identity_emails` 再做兜底校验；不匹配记入 `ignored_identity_mismatch`。
详见 `./员工AI产出-Git采集与归因方案-v1.0.md`。

**隐私考虑**：仅上报提交元信息（不含 diff、不含文件内容），员工可在 config.json 中通过 `gitlog_blacklist` 屏蔽特定 repo。

## 7. 服务端变更

### 7.1 删除

* `CostController` / `CostSummaryDto` 整体删除。
* `AiSessionRepository.aggregateCost` 方法删除。
* `DashboardController.overview` 去掉 cost 累加逻辑。
* `AbstractAiSessionIngestService.upsertSession` 不再 set `costUsd`，`writeEvent` 签名去掉 `costDelta` 参数。

### 7.2 新增（v2.1+）

* `DailySummaryAggregator`：按日跑，把 `ai_session` + `ai_session_event` 聚合写入 `daily_summary` 的 6 个新字段。
* `PeopleController` / `ProjectsController`：员工画像 / 项目透视的列表 + 详情接口。
* `ReportsController` + `UsageReportGenerator`：日 / 周 / 月报生成与查询。
* `GitCommitIngestController`：接收 aiwatchd gitlog Provider 上报。

## 8. 阶段性路线

| 阶段 | 范围 | 状态 |
| --- | --- | --- |
| **v2.0**（基线 PR） | 命名 + 去 cost + 8 项新菜单 + 4 个 placeholder 页 + schema.sql v2.0 段（DROP / ADD / 新表）| ✅ 已实装 |
| **v2.0.1** | codex Provider 合成 ExternalMessageID（修复 12k 重复消息脏数据）+ aiwatchd 二进制 + 配置自动迁移上线 | ✅ 已实装 |
| **v2.0.2** | DailySummaryAggregator + AdminController 手动触发端点 | ✅ 已实装（见 §8.1）|
| **v2.0.3** | dev/prod port 收编（dev=8081 / prod=8080）+ `/api/v1/admin/**` X-Admin-Token 鉴权（错码 10101，dev 内置 token）| ✅ 已实装 |
| **v2.1 · Phase 2 后端** | `ai_active_seconds_union`（merge-overlapping 算法）+ PeopleController + ProjectsController + ModelsController + BehaviorTagService（4 档行为标签） | ✅ 已实装 |
| **v2.1 · Phase 2 前端** | People.tsx 列表+详情+ECharts 时间线、Projects.tsx 列表+贡献者矩阵、ModelsTools.tsx 堆叠柱+token 燃烧热力图 | ✅ 已实装 |
| **v2.2 · Phase 3 客户端** | `agent/internal/monitors/gitlog`：scanner（git log + numstat）+ cursor（增量游标）+ Provider；reporter goroutine + `apiclient.ReportCommits` + `config.GitLog*` | ✅ 已实装 |
| **v2.2 · Phase 3 服务端** | GitCommit entity + Repository + GitCommitIngestService（AI 协助判定：commit ± 30min 内同 user/repo 重叠 session 数计权） + 渗透率真实计算 + Aggregator.ai_commit_count 接通 | ✅ 已实装 |
| **v2.2 · Phase 3 报告中心** | UsageReport entity + UsageReportGenerator（daily/weekly/monthly cron + admin 手动触发） + ReportsController + Reports.tsx 联调 + 规则模板叙述（不接 LLM） | ✅ 已实装 |

### 8.0 已上线的 v2.0.3 + v2.1 + v2.2 一览

```
zh
端口收编        application.yml 默认 8080；application-dev.yml 覆盖到 8081；prod 直接打 jar 不需要 args
admin 鉴权      /api/v1/admin/** 全员 X-Admin-Token；dev=dev-admin-token；prod 通过 AIWATCH_ADMIN_TOKEN 环境变量
                错码 10101 admin_token missing or invalid（常量时间比较防 timing 攻击）

People API      GET /api/v1/people?from&to&tag           列表（4 档行为标签筛选 + 日均协作时长降序）
                GET /api/v1/people/{userCode}            详情（KPI + 14 天时间线 + Top 模型/工具/项目）

Projects API    GET /api/v1/projects?from&to             列表（按 ai_session.project_name 聚合）
                GET /api/v1/projects/{name}              详情（贡献者矩阵 + 每日时间线 + Top 模型）

Models API      GET /api/v1/models/distribution          模型 token 占比 input/output 分色 + session/user count
                GET /api/v1/models/heatmap?days=14       模型 × 日期 token 燃烧热力图

gitlog 上报     POST /api/v1/agent/report-commits        独立通道，HMAC 与 /report 一致
                Provider 默认扫 ~/projects 等 5 个根目录，blacklist 子串匹配；增量游标存 ~/.cache/aiwatchd/gitlog/cursor.json

报告中心        GET  /api/v1/reports?scope&scope_key     列表（无鉴权）
                GET  /api/v1/reports/by-user             某用户的历史报告
                GET  /api/v1/reports/{id}                详情
                POST /api/v1/admin/reports/generate-{daily,weekly,monthly}   手动触发（带鉴权）
                cron: daily 0:15 / weekly 周一 0:30 / monthly 1 号 0:45（Asia/Shanghai）
                叙述当前用规则模板生成（中文 Markdown，不接 LLM；后续替换 LlmNarrativeService 即可）
```

### 8.1 DailySummaryAggregator（v2.0.2 已上线）

**位置**：`com.am.server.aggregator.DailySummaryAggregator`

**触发**：
- 自动：`@Scheduled(cron = "0 5 0 * * *", zone = "Asia/Shanghai")` 每天凌晨 0:05 跑昨天。
- 手动：`POST /api/v1/admin/aggregate-daily?date=YYYY-MM-DD`，不传 date 默认昨天。

**输入**：`ai_session` + `ai_session_message` + `ai_session_event` 三张流水表。

**输出**：把 (user_code, work_date) 维度的 12 个指标 upsert 到 `daily_summary`。
v1.2 工时字段（online_seconds / active_seconds / project_count）由独立 work_session 聚合任务负责，本聚合不动它们；保留是为了与 v1.x 共存。

**关键算法**：

| 指标 | 算法 |
| --- | --- |
| `ai_session_count` | 按 user_code 分组的 sessions count |
| `ai_message_count` | sum(user_messages + assistant_messages) |
| `total_input_tokens` / `total_output_tokens` | sum |
| `tool_call_count` | `ai_session_event` 当日 `event_type=TOOL_CALL` 计数 |
| `active_model_top` | 按 token 加权 Top1 模型（剔除 NULL） |
| `ai_models_top3` | 按 token 加权 Top3 模型 JSON 字符串 |
| `ai_active_seconds` | 每个 session 的 (lastActivity − startedAt) **clip 到当日窗口** 后求和。语义是"会话累计活跃时长"——多 session 并行（cursor + codex 同时跑）会累加，非"区间并集"，因此可能 > 24h |
| `ai_active_seconds_union` | **v2.1 上线**：把当日所有 session 的活跃区间做 merge-overlapping 后求和，自然 ≤ 86400。算法 O(n log n)（按 start 排序 + 一遍合并）。前端"日均协作"用这个，列表降序也用这个 |
| `ai_first_response_avg_ms` | 每个 session 第一条 user 与第一条 assistant 之间的间隔，**剔除 > 30min 的离群值**后做用户级算术平均 |
| `ai_thinking_seconds` | STATUS_CHANGE 事件流中"进入 thinking → 离开 thinking"的时长求和（含尾段 clip 到当日 23:59） |
| `ai_retry_count` | 同 session 内连续 user 消息中间无 assistant 的次数（卡壳 / 重新提问） |
| `ai_commit_count` | **v2.2 上线**：`git_commit` 当日该用户 ai_assisted=1 的提交数（依赖 GitCommitIngestService 的 ±30min 重叠 session 判定） |

**幂等**：基于 `(user_code, work_date)` UNIQUE KEY 做 find-then-save 的 upsert，重复触发同一日期会覆盖 v1.3 / v2.0 字段，**不动 v1.2 工时字段**（避免覆盖另一聚合任务的产物）。

**故障容忍**：定时任务的 `dailyJob()` 用 try-catch 兜住异常，避免单日聚合失败让进程崩溃。

**ShedLock**：v2.0 单实例部署未启用；多实例时再加 `@SchedulerLock` + `LockProvider` Bean。

## 9. 风险与开放问题

1. **隐私边界**：员工日报 / 周报会暴露使用行为细节。建议生产部署前与法务 / HRBP 对齐边界——尤其"卡壳次数"这种容易被误读为 KPI 的指标。
2. **AI 协助率误判**：基于"提交时间窗口"的推断必然有假阳性 / 假阴性。前端在 `ai_assist_confidence < 50` 时打"疑似"标签，避免管理者拿"渗透率"考核到具体人头。
3. **API 兼容期**：v2.0 服务端兼容老 v1.x 客户端的 cost 上报；v2.1 起客户端发布版本不再上报 cost，整个公司客户端升级完毕后才能去掉服务端的兼容代码。
4. **配置目录迁移容错**：mac / Windows / Linux 三平台的 base 目录都不一样，迁移函数已在 `agent/internal/config/config.go:migrateLegacyIfNeeded` 中处理；遇到权限问题等会静默跳过，由 `aiwatchd init` 兜底。

## 10. v2.0 验收

| 项 | 验收方式 |
| --- | --- |
| 数据库 cost 字段不再存在 | `SHOW COLUMNS FROM daily_summary / ai_session / ai_session_event` 不含 cost |
| 数据库新表 / 新字段已就位 | `SHOW TABLES LIKE 'git_commit'` / `'usage_report'`；`SHOW COLUMNS FROM daily_summary LIKE 'ai_%'` 6 行 |
| Server 编译 / 启动通过 | `./gradlew compileJava` 无报错；`bootRun` 日志无 cost 字段相关异常 |
| 前端编译 / 路由通过 | `./gradlew frontendBuild` 无报错；菜单 8 项 + Hero 卡 4 张（含 AI 渗透率占位） |
| Agent 编译 / 上报兼容 | `go build ./...` 无报错；老库的 `monitor_target` / `ai_session` 上报路径与之前一致 |
| 老 v1.x 客户端兼容 | 老客户端继续上报 `cost_usd`，服务端日志无 deserialization 异常 |
