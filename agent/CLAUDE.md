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
- **Offline outbox** (`outbox.go`): failed report bodies are spooled to `state/outbox/` and replayed in order.
- **Bootstrap mode**: on first run with an empty cursor store, all monitors widen to a 30d lookback to send
  history, then snap back to 48h once drained.
- Git commits go out separately via `GitLogReporter` → `POST /api/v1/agent/report-commits`.

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
`/install/manifest.json` (download → sha256 verify → stop → atomic replace → start), and `internal/uninstall/`
purges config/state/logs/cache/binary. The actual install-to-disk + service-registration is driven by the
`aiwatchd.sh` / `aiwatchd.ps1` installer scripts, not Go code.
