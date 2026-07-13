# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this package is

`monitors/` is the **data-collection layer** of `aiwatchd`: one sub-package per AI tool, each
reading that tool's local session store and turning it into a uniform `monitor.Snapshot`.
This is the most intricate part of the agent — adding support for a new tool happens here.

Orchestration (polling, cursors, transmission) lives one level up in
[`../reporter/`](../reporter/) and the contract types in [`../monitor/`](../monitor/); the
server whitelist that can disable a monitor lives in [`../monitorpolicy/`](../monitorpolicy/).

## The Provider contract

Every monitor implements `monitor.Provider` (defined in `../monitor/provider.go`):

```go
Type() string                                    // unique code: "cursor","claude","codex","hermes","openclaw","openharness","opencode","kimicode","zcode"
TargetVersion() string                           // version of the monitored tool
IsInstalled() bool                               // cheap fs check — status/logging only, NOT used to gate Snapshot
Snapshot(ctx) (monitor.Snapshot, error)          // best-effort; return empty Snapshot on error, never fail hard
```

Optional interfaces a provider may also implement:
- `LookbackSetter.SetLookback(d)` — switch scan window between `DefaultLookback` (48h) and
  `BootstrapLookback` (30d). All registered session monitors implement it; `gitlog` does not.
- `AccountProvider.Account()` — report the tool's logged-in account (email/tier). Only `cursor` does.

`Snapshot` returns `[]monitor.Session`. The wire model lives in `../monitor/provider.go`:
`Session` carries identity (`SessionID`, `Cwd`, `GitBranch`, `Model`), counters
(`UserMessages`/`AssistantMessages`/`InputTokens`/`OutputTokens`/cache tokens), `RecentMessages`,
`RecentTools`, and **`ActivityDeltas`** — fine-grained `(EventTime, InputTokensDelta,
OutputTokensDelta, MessagesDelta, Source, SourceRef)` increments that drive incremental upstream
reporting. The server reconstructs `SESSION_OPEN / TOKEN_DELTA / MESSAGE_DELTA / TOOL_CALL` events
from these (those event names are a server-side concept, not emitted here).

## The monitors at a glance

| Monitor | Data source | Parse strategy |
| --- | --- | --- |
| `cursor/` | SQLite `~/Library/Application Support/Cursor/.../state.vscdb` (WAL) | `PRAGMA data_version` fast-path skips full SQL on idle ticks; concurrent `buildParsedSession`; in-memory `(sessionID, lastBubbleAt)` cache |
| `claude/` | JSONL `~/.claude/projects/<enc>/*.jsonl` + `subagents/*.jsonl` | `common.FileCache` mtime/offset + `ScanJSONL` from offset + parallel parse; merges subagent child sessions into parent |
| `codex/` | JSONL `~/.codex/sessions/<date>/*.jsonl` + index | same FileCache/ScanJSONL/parallel pattern as claude |
| `openclaw/` | JSONL `~/.openclaw/agents/<a>/sessions/*.jsonl` | byte-for-byte the same pattern as claude (identical protocol) |
| `hermes/` | SQLite `~/.hermes/state.db` | persistent RO conn + `PRAGMA data_version` fast-path; full table scan per tick (small dataset); tokens **estimated** |
| `openharness/` | JSON `~/.openharness/data/sessions/<hash>/session-*.json` | no cache, full re-read per tick; interpolates time for undated msgs; tokens **estimated** |
| `opencode/` | SQLite `~/.local/share/opencode/opencode.db` (WAL, XDG path on all OS) | persistent RO conn + `PRAGMA data_version` fast-path like hermes; `session`/`message`/`part` tri-table; tokens are **real** columns. Legacy `storage/*.json` generations not yet parsed |
| `kimicode/` | JSONL `~/.kimi-code/sessions/<workDirKey>/<sessionId>/agents/*/wire.jsonl` (+ legacy `~/.kimi`) | FileCache/ScanJSONL like codex; merges main+subagent `wire.jsonl` per `sessionId`; tokens from `StatusUpdate.token_usage` (**real**); message text is heuristic **pending a real sample** |
| `zcode/` | SQLite `~/.zcode/cli/db/db.sqlite` (WAL, home-dir `~/.zcode` on macOS+Windows, confirmed) | Z Code (z.ai GLM agent) — OpenCode-derived `session`/`message`/`part` tri-table; persistent RO conn + `PRAGMA data_version` fast-path like opencode. **No token/model columns**: tokens from `message.data.tokens` (**real**), model from assistant `modelID`. zcode `tokens.input` *includes* cache (`total==input+output`), so input is split into disjoint fresh+cache to match opencode's convention. `tool` part = `{type:"tool", tool:"<Name>", state:{…}}` (confirmed); `reasoning`→thinking, `step-finish`/`step-start` ignored. Richer `model_usage`/`turn_usage`/`tool_usage` tables exist for future metrics |
| `antigravity/` | `~/.gemini/antigravity/conversations/*.pb` + VS Code `state.vscdb` sidebar index | Session-level observation from one protobuf file per conversation. Reads the stable JSON/protobuf sidebar metadata when available; deliberately does not guess the private conversation-body protobuf schema. |
| `qoder/` | `~/.qoder/projects/**/transcript/*.jsonl`, `~/.qoderwork/projects/**`, legacy session logs | Official Qoder hook transcript schema (`session_meta/user/assistant/progress`); parses message content, tool calls, timestamps, cwd/model and token usage when present. |
| `trae/` | TRAE / TRAE SOLO workspace `state.vscdb` | Parses `ChatStore`, `inputHistory`, and `memento/icube-ai-ng-chat-storage*` JSON with tolerant field projection across international/CN/SOLO builds. |
| `codebuddy/` | `codebuddy-sessions.vscdb` + optional `genie-history/**/messages.json[l]` | Session metadata is always local; message bodies are parsed when that build flushes local history, otherwise sessions are reported without fabricated content. |
| `gitlog/` | on-disk git repos | **not a `Provider`** — driven by `reporter.GitLogReporter`; streams `git log` per repo filtered by author email, persists its own per-repo commit cursor |

## Shared semantics — `common/`

Reuse these before hand-rolling anything; they encode contracts the server depends on:
- `jsonl.go` — `FileCache[V]`, `ScanJSONL`, `RunParallelParse` (incremental JSONL ingestion).
- `status.go` — `ResolveActivity` collapses raw provider state + recent tools into the **10 canonical
  statuses** (`idle, waiting, thinking, compacting, reading, writing, running, searching, browsing,
  spawning`); `NormalizeToolName` maps tool names to canonical (Read/Write/Bash/…). Timeout windows
  scale with report interval via `ConfigureActivityTimeouts`.
- `extid.go` — `SyntheticMessageID` / `SyntheticMessageIDByTime` build dedup keys when the source has
  no native message id (hermes, openharness). **Stability is critical**: the server dedups on
  `(ai_session_id, external_message_id)`, so an unstable key causes duplicates or dropped messages.
- `subagent_merge.go` — collapse child sessions into the parent (only claude emits them today).
- `text.go` — `EstimateTokens` (≈chars/4) for sources without real token counts; `Truncate` (UTF-8 safe).
- `worktree.go`, `activity_delta.go`.

## Adding a new monitor

1. Create `monitors/<tool>/` with a `Provider` implementing the four required methods.
2. Register it in `../../cmd/agent/main.go` `buildProviders()` (providers are a static list there —
   there is no env-based enable/disable; runtime gating is the server `monitorpolicy` whitelist).
3. Implement `LookbackSetter` if the source is time-windowed; `AccountProvider` if it exposes an account.
4. Reuse `common/` utilities — especially `ScanJSONL`/`FileCache` for JSONL sources and `ResolveActivity`
   for status, so behavior matches the other monitors.

## Gotchas

- **Cursor WAL fast-path** (`cursor/provider.go`): naive mtime checks fail because Cursor touches the WAL
  even when idle. The provider holds a long-lived RO connection and checks `PRAGMA data_version` — keep
  that connection open and read-only. This drops idle ticks from ~17s to ~50ms.
- **Cursor path is macOS-hardcoded**; on Windows/Linux the provider returns an empty snapshot gracefully.
- **Cursor message-cursor schema version**: bumping the dedup/ID synthesis rules requires bumping
  `CursorSchemaVersion` in `../reporter/cursors.go`, which nukes stored cursors and forces a 30d backfill.
- **No negative-token guard here** — providers trust wire counts; validation/caps are server-side.
- `IsInstalled()` is for status output only; `Snapshot` is always called and must self-handle a missing source.
