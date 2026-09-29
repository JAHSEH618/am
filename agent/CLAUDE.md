# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`aiwatchd` — the single-binary Go client that runs on each developer's machine, reads local AI-tool
session stores, and reports usage to the AIWatch server. Module `github.com/am/aiwatch-agent`, Go 1.25.

## Build & test

```bash
cd agent
go test ./...                                  # all tests
go test -v ./internal/reporter -run TestName   # a single test
bash build-dist.sh                             # cross-compile all 4 platforms (VERSION=dev)
VERSION=2.0.0 bash build-dist.sh               # tagged release
bash build-dist.sh --scripts-only              # refresh installer scripts only, no rebuild
```

`build-dist.sh` produces `dist/install/{aiwatchd-darwin-arm64, -darwin-amd64, -linux-amd64,
-windows-amd64.exe, aiwatchd.sh, aiwatchd.ps1, manifest.json}` using
`CGO_ENABLED=0 go build -trimpath -ldflags "-X main.Version=$VERSION -s -w"` (~12 MB stripped).
`manifest.json` carries per-platform sha256 and is what `aiwatchd update` checks.

## CLI surface

Dispatch is in `cmd/agent/main.go`. Subcommands: `init` (write config from `AM_*` env), `register`,
`start` (the long-lived report loop), `status` (JSON: config + registration + detected tools),
`update`, `uninstall`, `version`, `help`. There is also a one-off SQL backfill utility at
`cmd/backfill-cursor-order/` (re-orders historical Cursor messages).

## Config & registration

Config is `config.json` (`internal/config/`), located per-OS (macOS
`~/Library/Application Support/aiwatchd/`, Linux `~/.config/aiwatchd/`, Windows `%ProgramData%\aiwatchd\`);
v1.x `ai-work-agent` configs are auto-migrated on first load. All env overrides use the **`AM_*`** prefix:
`AM_SERVER_URL`, `AM_USER_CODE`, `AM_USER_NAME`, `AM_DEPARTMENT`, `AM_WATCH_DIR`, `AM_LOG_LEVEL=debug`,
`AM_AUTO_UPDATE`.

`internal/registrar/EnsureRegistered()` does an idempotent `POST /api/v1/agent/register`; the server
returns `AgentID` + `AgentSecret` (persisted to config) plus server-pushed knobs (`ReportIntervalMs`,
`TimestampWindowMs`, `MonitorPolicy`). `start` re-registers automatically if the secret is missing, so a
failed first registration self-heals. `internal/device/` computes a per-user-per-machine `HostHash =
sha256(hostname|userCode)`. `git_author_emails` in config is the allow-list for attributing git commits
to this user (alongside `git config user.email`).

## Reporting loop

`internal/reporter/` is the heart. `Run(ctx)` ticks on an **adaptive cadence**: the idle baseline is
`ReportIntervalMs` (**default 120000 ms / 2 min**; legacy short defaults are auto-upgraded), but whenever the
last report's response carries `active=true` (server saw a non-idle session with activity in the last ~5 min)
the loop drops to `ActiveReportIntervalMs` (**default 15 s**, server-pushable at register, clamped to
`[5 s, baseline]`) so the realtime page / dashboard are near-live while someone is working; it falls back to
the 2-min baseline when idle. Cadence is driven by `Reporter.lastActive` + `ticker.Reset`; the interval-select
logic (`resolveActiveInterval` / `nextInterval`) is unit-tested in `adaptive_test.go`. Note
`ConfigureActivityTimeouts` stays scaled to the **baseline** interval so status windows don't flap with the
cadence. Each tick snapshots all policy-enabled providers **in parallel**
(one failing provider is isolated, never aborts the tick), slices per-session message cursors, gzips bodies
≥1 KB, and `POST`s to `/api/v1/agent/report`. Resilience features to be aware of before touching this code:
- **Message cursors** (`cursors.go`): per `(provider, sessionID)` high-water marks persisted atomically to
  `state/cursors.json` (schema-versioned — a bump forces a full re-scan). Cursors only advance on a
  successful tick; a failed tick retransmits and the server dedups.
- **Offline outbox** (`outbox.go`): now **drain-only** — `tickOnce` no longer spools anything. Cursors advance only after a
  successful `client.Report`, so *every* failure (5xx incl. 500/502/504, 503/429/50301, HTTP 200 + 50000, timeouts, connection
  errors, write errors) simply resends the same increments next tick; spooling them is what turned the 2026-09 outage into a
  restart-proof storm. Legacy files left by older versions are drained at most `outboxDrainPerTick` (20) per tick, and a legacy file
  that gets **413 / 41301** is discarded (otherwise it blocks the whole queue behind it). The cost: after >48 h offline, data older
  than the lookback window is only recovered by bootstrap-style backfill, not by an outbox.
- **Failure backoff** (`reporter/backoff.go`): exponential + equal jitter (15 s → 10 min), `Retry-After` is the floor (capped at 1 h),
  state (`failStreak`, `busyUntil`) lives on the Reporter and clears on success. While backing off the file watcher is muted (and skips
  its mtime walk) and no other trigger fires; heartbeats are unaffected (server's bulkhead lets ≤2 KB through).
- **Cadence jitter**: every `ticker.Reset` gets ±20 % (`nextTickDelay`), the first tick waits a random 0–60 s, the gitlog timer is
  jittered too; `resolveActiveInterval` / `nextInterval` stay deterministic and unit-tested.
- **Per-request byte budget** (`reporter/budget.go`): default 4 MiB of JSON (`report_body_budget_bytes`, clamped to [256 KB, 16 MB]).
  Sessions/messages over budget wait for the next tick — cursors advance only to what was actually sent; at least one session
  per tick; non-idle sessions first; bootstrap does not end while anything is deferred (backlog drains at a 30 s cadence).
  **413 / 41301** halves the budget (floor 32 KB) and it recovers gradually; an irreducible unit (1 session, ≤1 message) that is still
  rejected is parked for 1 h. When a message has `content_parts` its `text` is omitted, except user messages carrying local-command
  noise prefixes (the server's `LocalCommandNoise` reads `text` before parts).
- **Sent-fingerprint persistence** (`state/sent_sessions.json`): only idle entries younger than 15 min, atomic, versioned
  (`sentStateSchema` — bump it whenever `sessionFingerprint` fields change), capped at 4000; a restart no longer re-sends the whole
  48 h window.
- **Unchanged idle sessions are skipped** (`unchanged.go`): a session with no new messages/deltas after cursor
  slicing, `status == idle`, and the same scalar fingerprint as the last *successful* send is left out of the
  body; it is force-resent every `unchangedResyncInterval` (15 min). Non-idle sessions are always sent — the
  server's `active` reply and work-session active seconds depend on them.
- **Watcher-triggered ticks** fire at most once per ticker period and never while already in fast cadence
  (IDE config-dir hints for trae/codebuddy/qoder change constantly); in those states the watcher skips its
  2 s mtime walk entirely and re-baselines (without firing) when it resumes.
- **Memory**: `cmdStart` sets a 512 MiB Go soft memory limit unless `GOMEMLIMIT` is set (`cmd/agent/memlimit.go`).
- **Corrupt `cursors.json`**: the bad file is renamed `cursors.json.corrupt-<ts>` (2 kept) and the reporter continues with an empty
  store *that has a path* — the old fallback had no path, so `Save` never wrote and every restart re-ran the full 30 d bootstrap.
  **Do not bump `CursorSchemaVersion`** without byte budget + rollout percent + server bulkhead in place and a staged release: it
  sends every machine into a 30 d full backfill at once.
- **Config timeout**: `report_timeout_ms` default 90 s (90000), hard cap 120 s; stored legacy values (the old 15 min default lives in
  every machine's `config.json`) are clamped at runtime by `ReportTimeout()` and replaced on the next natural `Save`.
- **Token clamp** (`monitor.Registry.Register` wraps every provider): session totals / deltas over the caps (`AM_TOKEN_MAX_SESSION`,
  `AM_TOKEN_MAX_SESSION_CACHE`, `AM_TOKEN_MAX_DELTA`) fall back to the last trusted value in this process (or 0) with a content-free WARN.
  Optional provider interfaces (`LookbackSetter`, `WatchHints`, `AccountProvider`) are forwarded explicitly by the wrapper — add new
  ones there too.
- **Bootstrap mode**: on first run with an empty cursor store, all monitors widen to a 30d lookback to send
  history, then snap back to 48h once drained.
- Git commits go out separately via `GitLogReporter` → `POST /api/v1/agent/report-commits` (same ≥1 KB gzip +
  HMAC-over-wire-bytes path as `/report`; commits are filtered by author email *before* the per-commit
  `git show`/`diff-tree` enrichment, so teammates' commits cost one `git log` line, not four forks), with its own
  per-repo commit cursor. That cursor advances **only when the response reports `failed == 0`** — the server
  ingests per commit and returns 200 even when some rows fail, and an advanced cursor puts those commits
  outside the next incremental window forever. Every scan logs one INFO line (`gitlog scan: repos=… `
  `new_commits=… filtered_by_email=… repos_without_identity=…`); `repos=0` and a nonzero
  `filtered_by_email` are the two usual reasons a machine reports no commits at all.

See [`internal/monitors/CLAUDE.md`](internal/monitors/CLAUDE.md) for the collector framework itself.

## Transport security

`internal/security/` signs every report with **HMAC-SHA256** over `body || ts || nonce`, sent as
`X-Agent-Id / X-Agent-Ts / X-Agent-Nonce / X-Agent-Sign`. `/register` is the only unsigned endpoint. The
server enforces a ±300 s timestamp window and rejects replayed nonces. Logs must never contain prompts,
source, file contents, or secrets (`internal/logger/` enforces file logging with 5 MB rotation).

## Lifecycle / OS integration

`internal/svcctl/` installs and controls auto-start per platform: macOS LaunchAgent
(`~/Library/LaunchAgents/com.aiwatch.aiwatchd.plist`), Linux `systemd --user`
(`~/.config/systemd/user/aiwatchd.service`), Windows ScheduledTask `aiwatchd` (AtLogon). `Stop()`/
`RemoveRegistration()` are idempotent. `internal/updater/` self-updates from the server's
`/install/manifest.json` (download → sha256 verify → stop → atomic replace → start). The daemon polls hourly ±25 % and honours
the optional manifest field **`rollout_percent`** (0–100, default 100): the agent's bucket is `sha256(agent_id)[:4] mod 100`, so a
rollout only ever widens; manual `aiwatchd update` and the install scripts ignore it. `build-dist.sh` reads `ROLLOUT_PERCENT`
(note `--scripts-only` regenerates the whole manifest, so pass `VERSION` too), and `internal/uninstall/`
purges config/state/logs/cache/binary. The actual install-to-disk + service-registration is driven by the
`aiwatchd.sh` / `aiwatchd.ps1` installer scripts, not Go code.
