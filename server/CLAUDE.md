# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`aiwatch-server` — a Spring Boot 3.2 / JDK 17 monolith (front and back end ship in one jar). It ingests
agent reports, persists AI-session telemetry to MySQL, aggregates it, and serves the React console + REST API.
Java code is under `com.am.server` (`am` = "AI Monitoring"; **not** a company name).

## Build, run, test

**Always use the wrapper `./gradlew` (pinned Gradle 8.7), never the machine's global `gradle`** (9.2.x makes
`io.spring.dependency-management` mutate `runtimeOnly` after resolution → `:test` / `bootJar` hard-fail; see root CLAUDE.md / AGENTS.md).
Many `@SpringBootTest` classes need a MySQL 8 on `127.0.0.1:3306` (see `src/test/resources/application-test.yml`).

```bash
cd server
./gradlew bootRun                                  # dev profile by default, port 8081
./gradlew bootJar                                  # -> build/libs/aiwatch-server-<ver>.jar
./gradlew test                                     # all tests, incl. the ArchUnit boundary check
./gradlew test --tests com.am.server.architecture.BoundaryTest   # a single test
```

`processResources` depends on the frontend build, so any `bootJar`/`bootRun` rebuilds the SPA via a
Gradle-managed Node/pnpm (see [`src/main/frontend/CLAUDE.md`](src/main/frontend/CLAUDE.md)).

**Profiles / ports:** base `application.yml` = 8080 · dev (`application-dev.yml`) = **8081**, `show-sql`
on, resource caching off · prod (`application-prod.yml`) = **9527**. The dev profile auto-imports
`resources/sql/schema.sql` (`spring.sql.init`) into MySQL `am`; prod expects the schema already loaded.

## Package layering — enforced by ArchUnit

Dependencies flow **HTTP-adapter → application/service → persistence model → `common`**. The rule in
`src/test/java/com/am/server/architecture/BoundaryTest.java` (authoritative spec: `docs/architecture/LAYERS.md`)
is non-negotiable and runs in `gradle test`:

> Persistence-model packages `domain..`, `insight.domain..`, `system.domain..` **MUST NOT** depend on
> HTTP-adapter packages `web..`, `agent.api..`, `insight.web..`, `system.web..`.

If you trip it, move the HTTP concern (DTO mapping, request/response shaping) up into a `service` /
`*.ingest` / `*.orchestrator` class rather than reaching down from an entity. Put business orchestration
in services, not controllers.

Capability-based packages under `com.am.server`:
- `web/` — controllers + `web/dto`, `web/sse` (SseHub real-time broadcaster), `web/security`
  (`AdminTokenInterceptor`), `web/support`.
- `agent/` — the agent-facing edge: `agent/api` (report/register controllers), `agent/security`
  (HMAC signature filter, nonce replay store), `agent/ingest` (per-provider session ingest), `agent/service`,
  `agent/config`.
- `aggregator/` — `DailySummaryAggregator` and `AiSessionStaleCloser` (see below).
- `insight/` — the LLM analysis engine; has its own [CLAUDE.md](src/main/java/com/am/server/insight/CLAUDE.md).
- `system/` — `system/auth` (`AuthConfigSyncer`), `system/scheduling` (`DynamicScheduledTaskManager` —
  cron jobs are registered in code and tunable from the UI without restart), `system/domain` (`sys_config`).
- `domain/` — JPA entities + repositories (`ai`, `agent`, `employee`, `git`, `summary`, `session`, `monitor`).
- `service/`, `config/`, `common/` (`R<T>` envelope, `BizException`, `ErrorCode`, `GlobalExceptionHandler`).

## Agent ingest pipeline

`POST /api/v1/agent/report` → `AgentIngestBulkheadFilter` (**readiness gate** — `ApplicationAvailability` not
`ACCEPTING_TRAFFIC` means startup patches/backfills are still running → 503; then 413 for `Content-Length` >
`max-body-bytes` (32 MB); caps concurrent heavy reports at `aiwatch.agent.ingest-max-concurrency`, default 16 — well under
the 40-connection Hikari pool — with **zero wait** (`ingest-acquire-timeout-ms: 0`, so rejected requests don't pin the
other Tomcat threads) and answers HTTP 503 + `ErrorCode.SERVER_BUSY` (50301) *before* the body is read or the DB touched;
bodies ≤2 KB such as device heartbeats use a separate small semaphore (`ingest-light-max-concurrency`, 8) and `/register`
shares it with a 64 KB body cap; an in-flight byte budget (`IngestByteBudget`, 512 MB) bounds heap use) →
`AgentSignatureFilter` (order: method/path → signature headers → ±300 s timestamp window → device lookup → **only then read
the body** (limited read, gzip decoded ≤ `max-decoded-body-bytes` 64 MB and ≤ 100:1) → constant-time HMAC compare →
`AgentIngestGuard` (**one heavy report in flight per agent**, and a tiny quota for agents older than 1.3.3 that don't
back off on 503) → nonce dedup via `agent_nonce`; DB failures inside the filter map to 503 via `OverloadFailures`;
`/agent/register` is the only signature exemption) → `AgentReportService` routes by `targetType` to a provider ingestor in `agent/ingest`
(`AbstractAiSessionIngestService` + Cursor/Claude/Codex/Hermes/OpenClaw/OpenHarness subclasses). Ingest
**upserts** `ai_session` (unique on `target_type + external_session_id`), appends `ai_session_event`,
dedups `ai_session_message` on `(ai_session_id, external_message_id)`, then publishes an SSE
`session_changed` event and **enqueues a debounced `daily_summary` refresh**. Git commits arrive separately
via `POST /api/v1/agent/report-commits` (same bulkhead).

> That endpoint ingests **per commit** and swallows single-commit failures so the rest of the batch lands —
> so its `IngestSummary.failed` count is a load-bearing contract, not a stat: the agent only advances its
> gitlog cursor when `failed == 0`, otherwise those commits fall outside the next incremental window and are
> lost for good. Never drop the field or return 0 unconditionally.

> Every per-session write runs in its own `REQUIRES_NEW` transaction with a **10 s timeout** (`aiwatch.agent.ingest-session-timeout-seconds`,
> effective budget ≈ timeout−1 s). A statement timeout makes Hikari close the connection and the rollback then fails, masking the
> original exception — always go through `IngestTimeouts.inTransaction`, which normalises every timeout shape to
> `TransactionTimedOutException`. Overload-class failures (`OverloadFailures.isOverload`) must **propagate** (→ HTTP 503, client
> keeps its cursor and resends; dedup makes that idempotent) — optimistic-lock retry exhaustion and `prepare()` failures used to be
> swallowed into a 200, which permanently lost that tick's messages. Token values from the client pass `TokenSanityGuard`
> (session totals / deltas over the configured caps are not adopted and raise `TOKEN_TAMPER`); any new token write must too.
> `WorkSessionService.advance` is no longer `@Transactional`: it takes a per-agent striped lock *outside* the transaction plus a
> "counted-up-to" watermark (single-instance assumption), so heartbeat/report races can't double-count.
> `agent_alert` writes are deduplicated (5 min per agent+type+level), globally rate-limited and asynchronous (never blocks or fails a request).
>
> Ingest cost is *per session per tick per agent*, so it dominates DB load. Sessions that carry no new
> messages/deltas must stay cheap, and nothing preloads a whole session: dedup looks up only the keys present
> in the report (`source_ref IN (…)` on `idx_session_sourceref` — falling back to the full set incl. the legacy
> `extra_json` branch until sys_config marker `event.source_ref_backfill_v1` exists — and `external_message_id
> IN (…)` on `uk_session_extmsg`); user/assistant/total counters are only recounted from `ai_session_message`
> when the tick wrote messages (`SessionMessageCountSupport#reconcileAfterIngest`); NL-skill attribution
> re-reads only from the turn the first new message lands in (`NlSkillAttributionSupport#reconcileSessionFrom`, a column
> projection — never `save` a projected detached row, use `updateSlashHits`);
> and "does this session have source refs" is an index-only existence check
> (`AiSessionEventRepository#existsAnySourceRef`; once marker `event.source_ref_backfill_v1` exists it uses
> `existsMaterializedSourceRef` and never falls back to the `extra_json` scan). `countByAiSessionId` runs only when the cursor is at
> the tail or a tick wrote nothing (the residual COUNT on unchanged tail ticks needs a `stored_message_count` column to remove —
> not done). `WorkSessionService.advance` runs on every report
> including heartbeats and relies on `ai_session(agent_id,last_activity)` / `work_session(agent_id,status,
> start_time)` (`PerformanceIndexSchemaPatches`). Prod has Hikari `leak-detection-threshold: 60000` — if the
> pool saturates again, grep the log for `Connection leak detection triggered` to see who holds connections.

## Aggregation & scheduling

- `DailySummaryAggregator` → writes **`daily_summary`** (the底表 for the People page and insight reports):
  an hourly job (today + yesterday) and a 00:05 daily job, plus the ingest-triggered debounced refresh.
  It computes `ai_active_seconds_union` (merged activity intervals), thinking seconds, retry count, etc.
- `CapabilityDailyAggregator` → writes **`capability_daily`** (the only data source of `/capability`):
  full-day delete+insert of `work_date × user × kind(skill/nl_skill/mcp/plugin_ns) × item × sub_item`
  from `slash_hits_json` + MCP `TOOL_CALL` events (content-parts fallback for event-less providers).
  Daily 00:15 + hourly :10 jobs + view-time `ensureFresh`; history backfilled once at boot
  (`CapabilityDailyBackfillPatch`, sys_config marker `capability.backfill_v2`; it goes through `aggregateUnderDateLock`, the same
  per-date lock as the hourly job, and — like the other three big backfills — through `ResumableBackfill`).
- `GitCommitAttributionEngine` → writes **`git_commit_attribution`** (the only data source of
  `/attribution`, and the primary source of the north-star AI penetration since v2.12): one row per
  non-merge commit, tier B (trailer match, `AiTrailerRules` + sys_config `attribution.trailer_rules`) /
  A (±30 min session window, single attribution by nearest activity event) / NONE. Ingest-debounced
  increments + nightly 00:30 (last 2 days) + boot backfill (`GitCommitAttributionBackfillPatch`,
  marker `attribution.backfill_v1`, rows flagged `backfilled=1`). `AiPenetrationService` reads it and
  falls back to the legacy query-time JOIN until the backfill completes.
- **View-time freshness is async**: pages call `DailySummaryAggregator#ensureFreshAsync` /
  `CapabilityDailyAggregator#ensureFreshAsync` (background, per-date single-flight) and read the current
  snapshot; the sync `ensureFresh`, hourly/daily jobs and the ingest debounce share one per-date lock. The ingest
  debounce has a 60 s max wait so "today" keeps converging under constant ingest.
- `CoveringIndexBuilder` → daily 03:30, builds `idx_window_cover` on `ai_session_event` / `ai_session_message`
  online (`ALGORITHM=INPLACE, LOCK=NONE`, 10 s metadata-lock wait); no-op once they exist.
- `AiSessionStaleCloser` → every minute, flips sessions idle when `last_activity` is older than ~5 min
  (clients can crash without closing), then broadcasts via SSE.
- `GitCommitPatchRetentionCleaner` → daily 03:45, clears `git_commit_file.patch_gzip` for commits older
  than `sys_config git.patch_retention_days` (default **60**; `0` disables). **Blobs only — the file rows
  stay**, so the file list, line counts and attribution are unaffected; only the "open a file's diff"
  drawer degrades, flagged by `truncate_reason='expired'`. Patches are the single largest thing in the DB
  and serve exactly one endpoint (`GET /api/v1/git-commits/patch`) — do not build anything else on them
  without revisiting this retention. The same job also **deletes orphan file rows** (`sweepOrphans`, ≤300k
  rows/run to cap ROW-binlog write amplification): rows whose parent `git_commit` no longer exists belong to
  no commit, and the retention query above can never reach them because it `JOIN`s `git_commit`. Prod had
  2.07 M of them / 8.8 GB, left behind by the `flushAutomatically` bug fixed in 1.3.2.
- `AgentNonceCleaner` → daily 04:15, deletes `agent_nonce` rows older than 24 h (batched). The nonce only
  guards replay inside the ±300 s signature window, so older rows are dead weight; without this the table
  grows forever (it hit 1.03 M rows / 276 MB in prod before the job existed).

> `daily_summary` ≠ `usage_report`. `daily_summary` is live and central. `usage_report` is a legacy v1.x
> table with **no writer in the current codebase** — the live "reports" are insight `analysis_report`s.

## Auth, persistence, SSE

- **Admin auth** is dual-channel for `/api/v1/admin/**`: a logged-in HttpSession (7-day sliding;
  `AIWATCH_USER`/`AIWATCH_PASSWORD`) **or** the `X-Admin-Token` header (`AIWATCH_ADMIN_TOKEN`, stored in
  `sys_config`, hot-reloaded by `AuthConfigSyncer`). Agent/report, installer, and `X-Admin-Token` paths are
  whitelisted from login. Employees never log into the backend.
- **Actuator:** only `/actuator/health`, `health/liveness`, `health/readiness`, `info` are anonymous; everything else under `/actuator/**`
  (notably `prometheus`) needs admin auth (`X-Admin-Token`, constant-time compare). `/actuator/health` deliberately excludes SMTP.
- **Schema governance:** no Flyway/Liquibase. `resources/sql/schema.sql` is the single idempotent source of
  truth (`CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE`), `hibernate.ddl-auto=none`. Additive columns are
  applied at boot by idempotent `*SchemaPatches` classes — add migrations there + in `schema.sql`, never via
  Hibernate auto-DDL.
- **SSE** (`web/sse/SseHub`): in-memory `SseEmitter` fan-out for live dashboard/session updates, best-effort.
  `publish` only serializes on the caller's thread and enqueues the frame for a single `sse-sender` thread
  (bounded queue of 256 frames; overflow is dropped and counted), so ingest threads never block on a browser
  socket; ingest also skips its SSE work (`ai_session_audit` lookup, DTO building) when nobody is subscribed.
  A stalled client can still delay frames for the other subscribers until its write fails and it is evicted.

See `../README.md` for deploy steps and the admin-endpoint reference (note its report-center section is
partly stale — defer to insight's CLAUDE.md for the live report path).
