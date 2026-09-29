# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**AIWatch** — an AI-usage observability platform for engineering teams. A Go client (`aiwatchd`) on each
developer machine reads local AI-tool session stores (Cursor, Claude Code, Codex, Hermes, OpenClaw,
OpenHarness) and reports usage; a Spring Boot monolith ingests, aggregates, scores, and serves a React
console. (Repo was renamed from *ai-work-platform*; the `am`/`com.am` prefix = "AI Monitoring".)

This file is the orientation layer. Deeper, authoritative docs already exist — read them rather than
re-deriving: **`AGENTS.md`** (navigation + machine-readable constraints), **`ARCHITECTURE.md`** (component
map), **`docs/architecture/LAYERS.md`** (layering rules + how to fix violations), `docs/design/` (product
specs; `legacy/` is v1.x), `docs/golden-principles/`, and per-module `CLAUDE.md` files (below).

## Per-module CLAUDE.md map

| Working in… | Read |
| --- | --- |
| Go client overall (build, CLI, reporting, lifecycle) | [`agent/CLAUDE.md`](agent/CLAUDE.md) |
| Adding/parsing an AI-tool collector | [`agent/internal/monitors/CLAUDE.md`](agent/internal/monitors/CLAUDE.md) |
| Spring backend (ingest, layering, schema, auth) | [`server/CLAUDE.md`](server/CLAUDE.md) |
| The LLM analysis/report engine | [`server/src/main/java/com/am/server/insight/CLAUDE.md`](server/src/main/java/com/am/server/insight/CLAUDE.md) |
| The admin console SPA | [`server/src/main/frontend/CLAUDE.md`](server/src/main/frontend/CLAUDE.md) |

## Commands

```bash
# Agent (Go 1.25)
cd agent && go test ./...
cd agent && bash build-dist.sh                  # cross-compile 4 platforms into dist/install/

# Backend (Spring Boot 3.2 / JDK 17). Use the wrapper ./gradlew (pinned Gradle 8.7).
# The machine's global `gradle` is 9.2.x, which BREAKS the build: io.spring.dependency-management mutates
# `runtimeOnly` after testRuntimeClasspath resolves -> `:test` / `bootJar` hard-fail. Always ./gradlew.
cd server && ./gradlew bootRun                    # dev profile, :8081, auto-imports sql/schema.sql
cd server && ./gradlew test                       # includes the ArchUnit boundary check
cd server && ./gradlew bootJar                    # build/libs/aiwatch-server-*.jar (SPA bundled in)

# Frontend (React 18 / Vite 5 / pnpm) — usually built by Gradle; only for live HMR:
cd server/src/main/frontend && pnpm install && pnpm dev   # :5173, proxies /api -> backend

./scripts/check-consistency.sh                   # docs/harness consistency gate
```

## End-to-end data flow

```
local AI tools ──read──> aiwatchd monitors ──HMAC report──> /api/v1/agent/report
                                                              │
                                                   agent/ingest upsert
                                                              ▼
        ai_session / ai_session_event / ai_session_message (MySQL `am`)
                                                              │
                                ┌─────────────────────────────┴───────────────┐
                       DailySummaryAggregator                          insight (dual-LLM judge)
                                │                                              │
                          daily_summary  ───────hours input───────►  analysis_report (+ _user)
                                │                                              │
                                └────────────► REST + SSE ◄────────────────────┘
                                                    │
                                            React console (/people, /dashboard, /analysis, …)
```

## Cross-cutting things to get right

- **`./gradlew` (wrapper, Gradle 8.7), not the machine's `gradle`** — global gradle is 9.2.x and breaks
  `:test`/`bootJar` (dependency-management plugin mutates `runtimeOnly` post-resolution). AGENTS.md updated.
- **Schema is one idempotent file**: `server/src/main/resources/sql/schema.sql` (`hibernate.ddl-auto=none`,
  no Flyway). Additive columns also go through boot-time `*SchemaPatches` classes — never rely on auto-DDL.
- **Layering is test-enforced**: persistence-model packages must not import HTTP-adapter packages
  (`com.am.server.architecture.BoundaryTest` fails the build otherwise — see `docs/architecture/LAYERS.md`).
- **`daily_summary` (live) ≠ `usage_report` (legacy, no writer)**: the current report engine is insight's
  `analysis_report` via `POST /api/v1/admin/analysis/generate`. The README's "报告中心 / `UsageReportGenerator`
  / `reports/generate-*`" section is stale v1.x — don't chase it.
- **v2.12 派生预聚合表**：`capability_daily`（能力使用日聚合，喂 `/capability`）与 `git_commit_attribution`
  （commit→AI 归因，喂 `/attribution` 与北极星渗透率）都是**纯派生物**——口径变更可整表重算（启动期
  backfill 走 sys_config marker），页面查询只打这两张表、不实时扫 `slash_hits_json`/`tool_name`/JOIN。
  规格：`docs/design/管理后台-产出归因与能力使用分析-v1.0.md`。
- **写入这三张表前先想清楚它会不会无限涨**。2026-07 生产根分区被打满一次，三个源头都是"只增不减"：
  binlog（无保留期，41G）、`agent_nonce`（有清理方法但零调用方，103 万行）、`git_commit_file.patch_gzip`
  （3.5G，占 am 库一半）。现在分别由 MySQL `binlog_expire_logs_seconds=3d`（`docker/mysql/aiwatch.cnf`）、`AgentNonceCleaner`、
  `GitCommitPatchRetentionCleaner` 兜住。同类还有 `agent_alert`（`AgentAlertRetentionCleaner`，默认 30 天，sys_config
  `agent.alert_retention_days`，写入本身也限速+异步）和会话正文/blob（`AiSessionContentRetentionCleaner`，**默认关闭**，启用前必读其 Javadoc）。**`patch_gzip` 默认只留 60 天**（`sys_config
  git.patch_retention_days`），超期只清 blob、保留文件行——所以别在 patch 内容上建新功能，
  它只服务 `GET /api/v1/git-commits/patch` 那一个抽屉。
- **`@Modifying(clearAutomatically = true)` 必须同时开 `flushAutomatically`**。1.3.2 之前
  `GitCommitFileRepository#deleteByCommitId` 只开了前者：`ingestOne` 里 `save(commit)` 刚 persist、
  INSERT 还挂在持久化上下文，紧接着这条 bulk DELETE 的 `em.clear()` 就把它丢了——无异常、无回滚、
  日志照报 `inserted=N`，`git_commit` 从 2026-07-01 起再没进过一行（id 序列却跑到 44 万），
  子行照落，攒出 200 万孤儿 `git_commit_file` / 8.8G，归因与渗透率全线空转。孤儿由
  `GitCommitPatchRetentionCleaner#sweepOrphans` 兜底（保留期那条 SQL 要 `JOIN git_commit`，选不中孤儿）。
- **commit 是不可变的**：同一个 `(repo_url, commit_hash)` 重报不得整表重写 `git_commit_file`——
  ROW binlog 会把 MEDIUMBLOB 的前后镜像各记一遍，零变更的重报也要写 2 倍 blob 字节。守卫见
  `GitCommitIngestService#isRicherThanStored`（只有带来更多 patch / 更多文件行才重写）。
- **HMAC 决定了请求体必须全量进内存**（`CachedBodyHttpServletRequest`，gzip 请求还要再持一份解压副本），
  所以"并发数 × 单请求体积"直接吃堆。容器 `-Xmx2g`，Tomcat 因此限到 50 线程、Hikari 池 40——
  调大任一项前先算这道乘法。
- **`/agent/report` 有舱壁，别绕开**（1.3.3，`AgentIngestBulkheadFilter`）：2026-09 生产事故里 50 个 Tomcat 线程
  全在跑上报，40 条连接被占满，连验签的 `findByAgentId` 都等 30s 超时，客户端失败后整包进 outbox、服务一恢复
  全员补发又打满——服务起不来。现在重上报并发 ≤ `aiwatch.agent.ingest-max-concurrency`（16），超额在读 body
  前回 503 + `50301`（≤2KB 心跳不占名额），1.3.3+ agent 见 503 不落 outbox。上报路径上每个会话一个事务、
  每 tick 全员 × 窗口内会话数——**在 ingest 里加任何按会话的查询前，先确认它走索引且不读全量事件**。
- **启动期回填必须一次性 + 分批**（2026-09 事故）：`ApplicationRunner` 在 Tomcat 已接流量后才跑，
  REPEATABLE READ 下一条全表 `UPDATE…JOIN ai_session` 会把 ai_session 的间隙锁满，ingest 的
  `INSERT ai_session` 等锁 50s 后整包失败、进 outbox 重发。回填一律走 `OneShotBackfillSupport`
  （sys_config marker + 主键区间分批）；运行期要持续收敛的状态在写入路径里就写对，别指望"每次重启修一遍"。
- **agent 通道的失败契约（2026-09 复盘后收紧）**：`/api/v1/agent/**` 上凡“过载 / DB 暂时不可用 / 并发冲突”一律
  **HTTP 503 + 50301 + `Retry-After`**，判定统一用 `common/OverloadFailures`（连接池超时、开不了事务、锁等待 1205、死锁、
  乐观锁、事务/语句超时……沿 cause 链找）；否则客户端会把整包落 outbox 并保持快节奏，越重试越挂。**filter 层的 DB 访问
  不经过 `@RestControllerAdvice`，必须自己 try/catch**；agent 路径上未识别异常是 500，不是 200+50000。请求体超限是
  **413 + 41301（确定性失败，勿重试）**，鉴权类仍是 HTTP 200 + 10001..10004。**任何 `catch (Exception)` 都不许把过载/超时类
  异常吞成“单条失败”**（会让请求 200、客户端推进游标、这一拍数据永久丢失——`AbstractAiSessionIngestService` 里乐观锁重试耗尽
  与 `prepare()` 就是这么修的）。
- **入口顺序与就绪门**：就绪门 → 413 → 轻/重信号量 → 在途字节预算 → 验签（method/路径/头/时间戳/agent 存在，**最后才读 body**）
  → per-agent 单在途 + 老客户端名额 → nonce。启动补丁/回填是 `ApplicationRunner`，Spring Boot 在它们**全部跑完后**才把
  readiness 置 `ACCEPTING_TRAFFIC`，在那之前舱壁对上报/注册（含心跳）一律 503；判断能否接流量用
  `/actuator/health/readiness`，**别用 `/actuator/health`**（它含 db/磁盘，补丁没跑完也是 UP；liveness 不含 db，避免抖动误重启）。
  这也意味着长回填期间 `last_seen` 不更新，`OfflineDeviceAlerter` 有就绪后的启动宽限（`notifications.offline_email.startup_grace_minutes`，默认 10）。
- **内存/连接算术，调大任何一项前先重算**：明文按 3×、gzip 按“线上字节 + 3×解压”计价，在途总预算默认 512MB（堆的 1/4，须 ≥ 3×
  `max-body-bytes`）；连接总账 = 重上报 16 + 稳态后台 10（scheduling 3 + dyn-sched 4 + refresh 3）+ 报告期 +10 = 36 ≤ Hikari 40
  （`BackgroundExecutorConfigTest#connectionBudgetStaysWithinHikariPool`、`OpsConfigTest` 兜底）。`@Async` 走专用 `analysis-job-*`
  有界池（此前落在池大小为 1 的 `taskScheduler` 上，管理员一触发报告就卡死 SSE 心跳等定时任务）。
- **超时体系**：Hikari `connection-timeout=5s` 快速失败成 503；每会话 ingest 事务 `timeout=10s`（有效预算 ≈ timeout−1s），超时会被
  Hikari 关连接导致**回滚失败盖掉原异常**，必须经 `IngestTimeouts.inTransaction` 归一成 `TransactionTimedOutException`；JDBC
  `socketTimeout=10min` 只兜网络黑洞，**长 DDL 必须自己 `setNetworkTimeout`**（`CoveringIndexBuilder`、`SchemaPatchSupport` 已做）；
  `data-source-properties` 覆盖 URL，别依赖部署机 `.env` 里的旧 `DB_URL`。优雅停机 30s，compose `stop_grace_period` 必须更大。
  OOM 靠 `-XX:+ExitOnOutOfMemoryError` 退出才能被 Docker 重启；堆转储由 `docker/entrypoint.sh` 按剩余磁盘决定开关（含敏感数据，用完删）。
- **DDL 与回填的写法**：新增列/索引/表一律走 `system/SchemaPatchSupport`（先查 `information_schema`、缺才 ALTER，INSTANT→INPLACE，
  MDL 等待 5s 封顶且用完还原会话变量，永不抛异常、稳态启动零 DDL）；**禁止再写 `ADD COLUMN IF NOT EXISTS`**（那是 MariaDB 语法，
  MySQL 8 直接 1064，`lock_wait_timeout` 默认还是一年）。大回填走 `ResumableBackfill`：`<marker>.progress` 记游标、`<marker>.failed`
  记失败清单，单条失败跳过并记录，整轮跑完写 marker，此后每次启动只重试失败项（最多 3 次，用尽 ERROR 放弃）；重新武装 = 删
  `.failed` 键或升 marker 版本。`@Modifying` 的正例：`AgentDeviceRepository#markOfflineEmailSent` 用定向 UPDATE 取代整行 `save`
  （整行回写会用旧值覆盖并发心跳刚写的 `last_seen`；`AgentDevice` 另加了 `@DynamicUpdate` 堵反方向）。
- **`@TableGenerator` 的 `allocationSize` 统一用 `domain/IdAllocation.BLOCK_SIZE`（1000）**：每个区间要额外借一条连接取号，池满时可能自死锁，
  故调大；生成器表存的是“已预留的最高 id”，区间互不相交，调整无需数据迁移，滚动发布/回滚都安全（代价只是重启跳号）。别在
  `com.am.server` 下放测试用 `@Entity`（会被全上下文扫描成真表）。
- **Token 数据的三层护栏**：Dashboard「Token 走势」= `SUM(daily_summary.total_*_tokens)`，其来源是 `last_activity` 落在当日的
  `ai_session` **会话累计值**（整段历史压在最后活跃那天），而累计值来自客户端。防线：客户端 `monitor.Registry.Register` 出口钳制
  （`AM_TOKEN_MAX_*`）、服务端 `TokenSanityGuard`（越界不采信 + `TOKEN_TAMPER` 告警，`aiwatch.ingest.max-*-tokens`）、汇总
  `DailySummaryAggregator#sumTokensGuarded`（跳过异常会话 + 单人单日封顶）。会话滑出 48h 回看窗口后坏值定格在库里，
  **新增任何 token 写入点必须过 guard**。排障与修复见 `docs/ops/token-anomaly.md`（重算走 `POST /api/v1/admin/aggregate-daily|aggregate-range`）。
- **控制台读路径不许在请求线程里重算**：页面用 `ensureFreshAsync`（后台 single-flight），整窗聚合走
  `TtlSingleFlightCache`；`GET /api/v1/**` 的 SELECT 带 20s `MAX_EXECUTION_TIME`（`ConsoleQueryBudgetFilter`），
  前端切时间窗会取消旧请求。大表索引走 `CoveringIndexBuilder` 夜间在线建，不在启动时 DDL。
- **Admin console lives at `/console`, not `/`** (Vite `base:/console/` + Router `basename`). Root `/`
  serves a standalone public install landing (`resources/landing/install.html` via `LandingController`) that
  exposes no admin SPA/routes — so employees fetching the installer can't browse the backend. `WebConfig`
  serves the SPA under `/console/**`; `ConsoleAccessFilter` optionally IP-gates `/console`, `/api/v1/admin/**`,
  `/api/v1/dashboard/**` via `console.ip_allowlist` (empty = open). `/install/**` is token-gateable via
  `install.token` (`InstallTokenInterceptor`); installer scripts verify binary sha256 against `manifest.json`.
- **Agent ↔ server auth is HMAC** (`X-Agent-*`, ±300 s window, nonce replay guard); **admin auth** is a
  7-day session **or** `X-Admin-Token`. Spring Security now requires `authenticated()` on `/api/v1/admin/**`
  (X-Admin-Token bridged by `AdminTokenAuthenticationFilter`); the empty-token bypass is gone and
  `AuthConfigStartupGuard` refuses prod boot on empty/default credentials. Employees never log into the backend.
- **Agent env vars use the `AM_*` prefix**; server admin/db config uses `AIWATCH_*` / `DB_*`.
- **Verify before claiming done**: `cd server && ./gradlew test`, `cd agent && go test ./...`,
  `./scripts/check-consistency.sh`.
