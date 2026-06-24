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
