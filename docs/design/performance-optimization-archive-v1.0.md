# 性能优化归档 v1.0

> 归档用途：记录两轮性能审查与落地的范围、文件触点与后续 backlog。  
> 原则：**不改变业务逻辑**；统计口径、ingest 去重、审计 prompt 采样等需与现网行为对齐验证。  
> 最后更新：2026-05-21

---

## 1. 背景

全仓性能审查覆盖：

| 层级 | 路径 |
|------|------|
| 服务端 | `server/`（Java / Spring） |
| 控制台 | `server/src/main/frontend/`（React / Vite） |
| Agent | `agent/`（Go） |

两轮实施：

- **第一轮**：会话列表 N+1、People ensureFresh、Dashboard 计数、Ingest/Report 批量、审计线程池、Agent/前端热点。
- **第二轮**：按 backlog 顺序推进 H5→H1→H3→H2→M3/F1/F3→H4 及若干 Agent/前端项；**明确排除 M7**（gitlog 全文件 patch）。

---

## 2. 已完成（按模块）

### 2.1 服务端 Java

| ID | 模块 | 优化 | 关键文件 |
|----|------|------|----------|
| R1-1 | `AiSessionController` | 列表窗内 token/消息数：批量 `aggregateSessionWindowsForSessions`，消除每行一次聚合 | `AiSessionEventRepository`, `AiSessionController` |
| R1-2 | `PeopleController` | `ensureWindowFreshIfStale`：按 `max(event_time)` vs `max(updated_time)` 判断，稳态跳过 `ensureFresh` | `PeopleController`, `DailySummaryRepository`, `AiSessionEventRepository` |
| R1-3 | `DashboardController` | `overview` 用 `COUNT` / `COUNT(DISTINCT agent_id)`，不再拉全量 non-idle 实体 | `AiSessionRepository`, `DashboardController` |
| R1-4 | `DashboardController` | `online`：work_session 批量 `findLatestOpenByAgentIdIn` | `WorkSessionRepository`, `DashboardController` |
| R1-5 | `ReportAggregator` | Git commit `user_code IN` 一次拉取；报告用户 `saveAll` | `GitCommitRepository`, `ReportAggregator` |
| R1-6 | `AbstractAiSessionIngestService` | `MAX(sequence_no)`；预加载 `external_message_id`；user 消息 slash 与 content 一次 `save` | `AiSessionMessageRepository`, ingest |
| R1-7 | `GitCommitIngestService` | commit 文件明细 `saveAll` | `GitCommitIngestService` |
| R1-8 | 洞察审计 | 共享 `insightAuditExecutor` 线程池，避免每次扫描新建 pool | `InsightAuditExecutorConfig`, `SessionAuditService`, `BackgroundInsightAuditScanner` |
| R2-H5 | DB 索引 | 复合索引幂等补齐 | `PerformanceIndexSchemaPatches`, `schema.sql` |
| R2-H1 | `DailySummaryAggregator` | 消息/事件投影；模型 token 投影；Git commit 批量 count；`saveAll` 落库 | `DailySummaryAggregator`, 各 Repository |
| R2-H3 | Ingest | tool 去重批量 prefetch；每 ingest tick 每 session 只 `reconcileSession` 一次 | `AbstractAiSessionIngestService`, `AiSessionEventRepository` |
| R2-H2 | `SlashCommandStatSupport` | 全局 Top：拆「仅有 slash_hits_json」与「缺 json 回算 content」两路查询 | `AiSessionMessageRepository`, `SlashCommandStatSupport` |
| R2-H4 | `SessionAuditService` | 审计 prompt：投影列；>200 条分页取头/尾/中段，不全量 hydrate 实体 | `SessionAuditService`, `AiSessionMessageRepository` |
| R2-M1 | `ReportAggregator` | `loadUnionSecondsByUser`：`user_code IN` 聚合，不扫窗内全表 daily_summary | `DailySummaryRepository`, `ReportAggregator` |
| R2-M3 | `DashboardController` | `online`：non-idle 会话摘要查询 `findNonIdleSessionSummariesByTargetTypeIn` | `AiSessionRepository`, `DashboardController` |

**H5 新增索引（存量库启动补丁 + schema 文末幂等 ALTER）：**

- `ai_session_event (target_type, event_time)` → `idx_target_event_time`
- `ai_session_message (target_type, message_time)` → `idx_target_message_time`
- `ai_session (target_type, last_activity, invalid_reason)` → `idx_target_last_invalid`

### 2.2 Go Agent

| ID | 模块 | 优化 | 关键文件 |
|----|------|------|----------|
| R1-A1 | `sysmeta` | `GlobalGitUser` 10 分钟进程内缓存 | `sysmeta/sysmeta.go` |
| R1-A2 | `reporter` | `tickMu.TryLock()` 防重叠 tick | `reporter/reporter.go` |
| R1-A3 | `cursors` | `json.Marshal` 替代 `MarshalIndent` 写盘 | `reporter/cursors.go` |
| R1-A4 | `apiclient` | 响应体 `LimitReader` 4MB | `apiclient/apiclient.go` |
| R2-A7 | `sysmeta` | `LocalIP` 10 分钟进程内缓存 | `sysmeta/sysmeta.go` |
| R2-M8 | `outbox` | `pending` 计数；队列为空时 `Drain` 跳过 `ReadDir` | `reporter/outbox.go` |
| R2-M9 | `gitlog/session_roots` | 内容未变则跳过写 `gitlog_session_roots.json` | `monitors/gitlog/session_roots.go` |

### 2.3 前端 React

| ID | 模块 | 优化 | 关键文件 |
|----|------|------|----------|
| R1-F1 | 路由 | 重页 `React.lazy` + `Suspense` | `App.tsx` |
| R1-F2 | `SystemSettings` | Audit 搜索 `keyDraft` / `keyQuery` 分离，仅搜索时请求 | `SystemSettings.tsx` |
| R1-F3 | `ModelsTools` / `Tools` | 父页传 `from/to`，嵌入模式隐藏子 RangePicker | `ModelsTools.tsx`, `Tools.tsx` |
| R1-F4 | `api/client` | `fetchMonitorTargets` 内存缓存 + in-flight 去重 | `api/client.ts` |
| R1-F5 | `Tools` | ECharts `notMerge lazyUpdate` | `Tools.tsx` |
| R2-F1 | 构建 | Vite `manualChunks`：echarts / xlsx / antd | `vite.config.ts` |
| R2-F2 | 分析报告 | Excel 导出动态 `import('./exportAnalysisReport')` | `Analysis.tsx` |
| R2-F3 | `Sessions` | 去掉 pageSize `1000` 选项 | `Sessions.tsx` |
| R2-F7 | `UserDetail` | Tabs `destroyInactiveTabPane` | `Analysis/UserDetail.tsx` |
| R2-F12 | `Dashboard` | 标签页 `document.hidden` 时跳过轮询 | `Dashboard.tsx` |

---

### 2.4 第三轮：员工数据超时（2026-08-25）

现象：控制台点「员工数据」经常打穿前端 15s 超时（`api/client.ts` timeout），选「近30天 / 本月」必现。
定位到三处，全部**不改统计口径**：

| ID | 模块 | 问题 | 改法 |
|----|------|------|------|
| R3-1 | `PeopleController#ensureWindowFreshIfStale` | 判「快照是否过期」用 `GROUP BY DATE(event_time)` 把整窗事件流扫一遍——为一个是/否问题付整窗代价 | 改成按天两次索引探针 `probeActiveEventAfter`（走 `idx_target_event_time`），代价与窗口长度无关 |
| R3-2 | 同上 | 过期日**逐天同步**重聚，每天 = 当日活跃人数 × 4 条聚合查询；30 天窗口撞上 backfill 必超时。且窗口去重标记扫完才落，list/detail 并发各扫一遍 | 同步只给最近 `MAX_SYNC_ENSURE_DAYS=2` 天，更早的转 `enqueueRefresh` 后台 debounce；去重标记改 `compute` 原子占位 |
| R3-3 | `/people` 列表 | 「问答比」与「Slash 合计」是两条 SQL，扫的却是同一批 `ai_session_message` 行 | 合成一条 `SUM(CASE WHEN ...)` 按员工分组，窗内只扫一遍 |
| R3-4 | `/people/{code}` 详情 | 同一批行发三条 SQL（role 计数 / Slash 合计 / Slash 按天）；`computePeriodComparison` 又把本期 daily_summary 与本期 Git 计数各重查一遍 | 按天聚合一条查完、合计由各天相加；本期行与 Git 计数由 `buildDetail` 传入复用 |
| R3-5 | `SlashCommandStatSupport#topCommandTokensForUser` | 一条 SQL 同时 `SELECT content_text`（MEDIUMTEXT，原文不截断），把该员工窗内**全部提问原文**搬到应用层，只为在极少数缺 JSON 的历史行上回算 | 拆「仅 slash_hits_json」+「仅回算」两路，与团队 Top 的 R2-H2 同一套拆法 |

回归护栏：`PeopleMessageStatsQueryTest`（真 MySQL）钉住条件聚合的每一项——role 大小写、Slash 只算 user 行、
invalid / 窗外 / 非 active target_type 一条不进、按天相加 == 整窗合计——以及探针的三种过期语义。

**仍未做（本轮范围外）：** `ai_session_message` 上给列表那次扫描做覆盖索引（省掉每行一次聚簇索引回表，
但按现表量估 +150~400MB 且随表增长）；或把「提问数 / 回复数 / Slash 数」落进 `daily_summary`
（列表彻底不碰 message 表，但要加列 + 聚合器写入 + 一次性回填）。

---

## 3. 明确未改 / 暂缓

### 3.1 按产品决策排除

| ID | 项 | 原因 |
|----|-----|------|
| **M7** | gitlog `enrich` 每 commit 多次 git；`PatchContextLines=999999` | **产品要求：保持全文件 patch**，不做降采样或默认关 patch |

### 3.2 会改变默认行为（需单独确认）

| ID | 项 | 说明 |
|----|-----|------|
| **A1** | 默认 `ReportIntervalMs=5000` | 改为 10–30s 或 idle 自适应会影响上报频率 |
| **A2** | Bootstrap 30d + 5s tick 首装风暴 | 需专用 bootstrap 降频策略 |

### 3.3 改动面大 / 需口径或设计

| ID | 项 | 说明 |
|----|-----|------|
| **H3（部分）** | Ingest 按 session 拆大事务 | 仅做了 tool/reconcile 优化；整包 snapshot 仍单事务 |
| **H6** | `NlSkillAttributionBackfillRunner` 启动 `LIKE '%skill.md%'` | 建议改离线 job + ingest 增量标记 |
| **M4** | 大 payload：`People` 全量、`AnalysisReport` 全量 user、`AiSession` size≤1000 | 需 API 分页与前端 lazy load 方案 |
| **M5** | Dashboard / Models / Projects 短 TTL 缓存 | 需缓存 key 与失效策略 |
| **M6** | `DualJudgeService` `Thread.sleep` 占 worker | 建议共享 scheduler 异步重试 |
| **M2** | `mergeSlashHitsJson` Java 逐条 `readTree` | 可 SQL `JSON_TABLE` 或 ingest 预聚合表 |
| **A3** | gitlog 每轮 `discoverRepos` 深度遍历 | 缓存 repo 列表 + 定期刷新 |
| **A4** | `git log` 整段进内存 | 流式解析 |
| **A5** | HTTP 无重试、多 `New` client | 幂等 POST 有限重试 + 共享 Transport |
| **A6** | gitlog 持有启动时 `cfg` | 重注册后刷新 secret |

### 3.4 前端细项（未铺开）

| ID | 项 |
|----|-----|
| F4 | `Dashboard` OnlineAgentTable：`columns` memo、agent 搜索 debounce、虚拟滚动 |
| F5 | `People` / `Projects` 服务端分页；Git Modal 虚拟列表 |
| F6 | `Analysis` 员工列表分页 / 搜索 debounce / 轮询 `useRef` 固定 interval |
| F8 | `People` PersonDetailPanel：`topBarOption` `useMemo` |
| F9 | `TeamOverview` 等图表统一 `notMerge lazyUpdate` |
| F10 | `Realtime` 虚拟 grid / memo 卡片 |
| F11 | `SessionDetail` / `GitCommitChangeCell` 大 diff 截断或虚拟滚动 |
| F13 | 全项目 `React.memo` 体系化 |
| F14 | `People` / `Projects` hooks 顺序（early return 在 hooks 前） |

---

## 4. 建议后续顺序（backlog）

```text
1. 生产 EXPLAIN 验证 H5 索引命中率
2. M4 API 分页（People 列表、Analysis 报告 user、AiSession size 上限）
3. H6 Backfill 改异步 / 增量标记，去掉启动全表 LIKE
4. M5 只读接口 30–60s 本地缓存（Dashboard overview、Models/Projects 分布）
5. Ingest 按 session 拆事务（需失败语义评审）
6. 前端 F4–F6、F11（大表 / 大 diff）
7. A1/A2（仅在你确认可改默认间隔后）
```

**持续排除：** M7（全文件 patch）。

---

## 5. 验证清单（回归）

| 场景 | 关注点 |
|------|--------|
| AI 会话列表 | 窗内 `windowTokens` / `windowMessageCount` 与改前一致 |
| 员工数据列表/详情 | 协作时长、问答比；backfill 中过期日仍会触发 ensureFresh |
| 大盘 overview / online | 活跃会话数、在线表字段与 rowSpan 行为 |
| 模型与工具 / Slash Top | Top 命令排序与计数（尤其仅 json / 仅回算两路合并） |
| 分析报告 | 用户指标、Git 相关指标、union seconds |
| Ingest | 消息去重、slash 字段、NL skill reconcile 结果 |
| 洞察审计 | 长会话 prompt 采样后评判结果分布（>200 条会话） |
| Agent | 稳态 CPU/磁盘；outbox 空队列不再每 tick ReadDir |
| 前端 | 首屏体积；导出 Excel 首次点击加载 xlsx chunk |

**构建：**

```sh
cd server && gradle compileJava
cd agent && go build ./...
cd server/src/main/frontend && pnpm run build
```

---

## 6. 相关代码索引（便于续做）

```
server/src/main/java/com/am/server/
  web/AiSessionController.java
  web/PeopleController.java
  web/DashboardController.java
  web/support/SlashCommandStatSupport.java
  aggregator/DailySummaryAggregator.java
  agent/ingest/AbstractAiSessionIngestService.java
  insight/aggregate/ReportAggregator.java
  insight/audit/SessionAuditService.java
  system/PerformanceIndexSchemaPatches.java

server/src/main/resources/sql/schema.sql

server/src/main/frontend/
  vite.config.ts
  src/App.tsx
  src/api/client.ts
  src/pages/{Dashboard,Sessions,Analysis,ModelsTools,Tools,SystemSettings}.tsx
  src/pages/Analysis/UserDetail.tsx

agent/internal/
  sysmeta/sysmeta.go
  apiclient/apiclient.go
  reporter/{reporter.go,cursors.go,outbox.go}
  monitors/gitlog/session_roots.go   # M7 未改 enrich
```

---

## 7. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-05-21 | 收敛第一轮 + 第二轮已改/未改；M7 按产品要求保持不动 |
