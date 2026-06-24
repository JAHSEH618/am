# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What `insight` is

The **LLM-driven analysis engine** (AIWatch v2.0's actual "report center"). It scores AI-coding sessions
with a dual-LLM-judge rubric and rolls them up into window-scoped `analysis_report`s with per-employee
metrics, team percentiles, and watchlist (anomaly) flags.

> This — `AnalysisReportController` at `POST /api/v1/admin/analysis/generate` — is the live report
> pipeline. The `usage_report` table and the README's "报告中心 / `reports/generate-*` /
> `UsageReportGenerator`" section are **stale v1.x docs**: the table exists in `schema.sql` but no Java
> code reads or writes it. Don't go looking for `UsageReportGenerator`.

## The two-tier design (the key mental model)

Insight deliberately separates an **expensive, permanent, per-session LLM judgment** from a **cheap,
ephemeral, per-window statistical aggregation**:

1. **Audit (cached, cross-window)** → `ai_session_audit`, one row per `ai_session_id`. Holds the merged
   dual-judge verdict (difficulty, outcome, mode, 5 capability scores) plus raw A/B scores and a
   `judge_disagreement` flag. Reused by every report that touches the session, so the LLM runs once.
2. **Aggregate (recomputed each report)** → `analysis_report` + `analysis_report_user`. Pure statistics
   over the cached audits + `daily_summary` + `git_commit` for a `[from, to]` window.

`aggregate/` here is **not** the top-level `com.am.server.aggregator` package. The latter is the always-on,
no-LLM ETL that builds `daily_summary`; insight `aggregate/` **consumes** `daily_summary.ai_active_seconds_union`
as its hours input. Different layers, don't conflate them.

## Sub-packages

- `orchestrator/` — `AnalysisReportOrchestrator.findOrCreate()` is the entry point: idempotent (returns a
  cached report for the same window unless `force=true` or the prior run FAILED), then schedules
  `AnalysisJobRunner.runAsync()` **after transaction commit**. `AnalysisReportStartupCleaner` marks
  orphaned PENDING/RUNNING reports FAILED on boot (async jobs don't survive a restart).
- `audit/` — the LLM core. `SessionAuditService.auditAll()` filters sessions via `InsightAuditPolicy`,
  respects an LLM-call budget, and fans out concurrent workers. Per session: `buildPrompt()` (loads rubric
  via `RubricLoader`, smart-truncates >200-message transcripts to first-50 + sampled-middle + last-50) →
  `DualJudgeService` calls Judge A and Judge B → `AuditConsistencyChecker.combine()` merges them and flags
  disagreement. `BackgroundInsightAuditScanner` (`@Scheduled`) pre-warms the cache off a file-based cursor.
- `aggregate/` — `ReportAggregator.aggregate()` builds per-user `UserMetrics`, team P10–P90 percentiles
  (from users with ≥10 sessions), composite scores, and `WatchlistEvaluator` anomaly flags
  (`HIGH_INVEST_LOW_OUTPUT`, `HIGH_REVERT`, `LOW_DIFFICULTY`, `HIGH_DEPENDENT`, `DEBUGGING_LOOP`,
  `SILENT_PRODUCTIVE`).
- `domain/` — JPA entities/repos: `AnalysisReport`, `AiSessionAudit`, `AnalysisReportUser`.
- `web/` (+ `web/dto/`) — `AnalysisReportController` (generate / find / history / detail / per-user) and
  `InsightAuditAdminController` (request re-audit). DTOs use `@JsonRawValue` to pass stored JSON straight through.
- `config/` — `InsightProperties` (binds `aiwatch.insight.*`) and `InsightConfigSyncer` (two-way sync with
  the `sys_config` table; hot-reloads on `SystemConfigChangedEvent`).

## LLM providers

`JudgeClient` is the provider abstraction, but the only real implementation is
`OpenAiCompatibleJudgeClient` (OpenAI / Azure / DeepSeek / Moonshot / Qwen / self-hosted vLLM/Ollama — anything
speaking `/v1/chat/completions`) plus a `MockJudgeClient` (`provider=mock`, the default). **There is no native
Anthropic/Claude client** — using the Claude API directly would require a new `JudgeClient`. The client sets
`temperature=0`, strips `<think>` blocks, and parses JSON out of the response (no schema enforcement on the wire).

Judge config (endpoint / apiKey / model / timeout for slots A and B) and the rubric live in `sys_config`,
editable from the System Settings UI and hot-reloaded. The rubric default seed is
`resources/insight/rubric-v3.0.yaml` (plain text fed to the LLM, not parsed).

## Gotchas

- **Re-audit is triggered by message growth, not time**: a session is re-judged only when
  `total_messages > message_count_at_audit + reaudit_message_threshold` (default 20), or on explicit
  request / `force=true`. Bumping `rubric_version` does **not** auto re-audit history (avoids surprise LLM bills).
- **LLM budget truncation is silent**: if a window has more sessions than `max_llm_calls_per_report` allows,
  only the affordable subset is audited and the report is marked partial — no error.
- **Judge disagreement is down-weighted, not dropped**: a disagreeing session still contributes to team
  aggregates at ×0.3 weight.
- **The after-commit scheduling in `findOrCreate` matters**: schedule the async runner inside the orchestrator
  and the worker may not see the not-yet-committed report row. `recoverStuckPendingIfNeeded()` is the safety net.
- `ai_session_audit` has a unique key on `ai_session_id`; re-audit UPDATEs the row (never a second INSERT).
- The background scanner's cursor is a local JSON file — if lost, it rescans from id 0 (no DB cursor by design).
