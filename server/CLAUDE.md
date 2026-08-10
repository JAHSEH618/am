# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`aiwatch-server` — a Spring Boot 3.2 / JDK 17 monolith (front and back end ship in one jar). It ingests
agent reports, persists AI-session telemetry to MySQL, aggregates it, and serves the React console + REST API.
Java code is under `com.am.server` (`am` = "AI Monitoring"; **not** a company name).

## Build, run, test

A Gradle wrapper exists (`./gradlew`), but **this repo's AGENTS.md instructs using the machine-local
`gradle`** — both work; follow AGENTS.md on this machine.

```bash
cd server
gradle bootRun                                  # dev profile by default, port 8081
gradle bootJar                                  # -> build/libs/aiwatch-server-<ver>.jar
gradle test                                     # all tests, incl. the ArchUnit boundary check
gradle test --tests com.am.server.architecture.BoundaryTest   # a single test
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

`POST /api/v1/agent/report` → `AgentSignatureFilter` (verifies `X-Agent-*` HMAC headers: ±300 s timestamp
window, nonce dedup via `agent_nonce`, device lookup, constant-time HMAC compare; `/agent/register` is the
only exemption) → `AgentReportService` routes by `targetType` to a provider ingestor in `agent/ingest`
(`AbstractAiSessionIngestService` + Cursor/Claude/Codex/Hermes/OpenClaw/OpenHarness subclasses). Ingest
**upserts** `ai_session` (unique on `target_type + external_session_id`), appends `ai_session_event`,
dedups `ai_session_message` on `(ai_session_id, external_message_id)`, then publishes an SSE
`session_changed` event and **enqueues a debounced `daily_summary` refresh**. Git commits arrive separately
via `POST /api/v1/agent/report-commits`.

> That endpoint ingests **per commit** and swallows single-commit failures so the rest of the batch lands —
> so its `IngestSummary.failed` count is a load-bearing contract, not a stat: the agent only advances its
> gitlog cursor when `failed == 0`, otherwise those commits fall outside the next incremental window and are
> lost for good. Never drop the field or return 0 unconditionally.

## Aggregation & scheduling

- `DailySummaryAggregator` → writes **`daily_summary`** (the底表 for the People page and insight reports):
  an hourly job (today + yesterday) and a 00:05 daily job, plus the ingest-triggered debounced refresh.
  It computes `ai_active_seconds_union` (merged activity intervals), thinking seconds, retry count, etc.
- `CapabilityDailyAggregator` → writes **`capability_daily`** (the only data source of `/capability`):
  full-day delete+insert of `work_date × user × kind(skill/nl_skill/mcp/plugin_ns) × item × sub_item`
  from `slash_hits_json` + MCP `TOOL_CALL` events (content-parts fallback for event-less providers).
  Daily 00:15 + hourly :10 jobs + view-time `ensureFresh`; history backfilled once at boot
  (`CapabilityDailyBackfillPatch`, sys_config marker `capability.backfill_v1`).
- `GitCommitAttributionEngine` → writes **`git_commit_attribution`** (the only data source of
  `/attribution`, and the primary source of the north-star AI penetration since v2.12): one row per
  non-merge commit, tier B (trailer match, `AiTrailerRules` + sys_config `attribution.trailer_rules`) /
  A (±30 min session window, single attribution by nearest activity event) / NONE. Ingest-debounced
  increments + nightly 00:30 (last 2 days) + boot backfill (`GitCommitAttributionBackfillPatch`,
  marker `attribution.backfill_v1`, rows flagged `backfilled=1`). `AiPenetrationService` reads it and
  falls back to the legacy query-time JOIN until the backfill completes.
- `AiSessionStaleCloser` → every minute, flips sessions idle when `last_activity` is older than ~5 min
  (clients can crash without closing), then broadcasts via SSE.
- `GitCommitPatchRetentionCleaner` → daily 03:45, clears `git_commit_file.patch_gzip` for commits older
  than `sys_config git.patch_retention_days` (default **60**; `0` disables). **Blobs only — the file rows
  stay**, so the file list, line counts and attribution are unaffected; only the "open a file's diff"
  drawer degrades, flagged by `truncate_reason='expired'`. Patches are the single largest thing in the DB
  and serve exactly one endpoint (`GET /api/v1/git-commits/patch`) — do not build anything else on them
  without revisiting this retention.
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
- **Schema governance:** no Flyway/Liquibase. `resources/sql/schema.sql` is the single idempotent source of
  truth (`CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE`), `hibernate.ddl-auto=none`. Additive columns are
  applied at boot by idempotent `*SchemaPatches` classes — add migrations there + in `schema.sql`, never via
  Hibernate auto-DDL.
- **SSE** (`web/sse/SseHub`): in-memory `SseEmitter` fan-out for live dashboard/session updates; best-effort,
  slow clients can't block others.

See `../README.md` for deploy steps and the admin-endpoint reference (note its report-center section is
partly stale — defer to insight's CLAUDE.md for the live report path).
