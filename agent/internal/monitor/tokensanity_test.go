package monitor

import (
	"context"
	"errors"
	"fmt"
	"math"
	"strings"
	"testing"
	"time"
)

func sess(id string, in, out, cc, cr int64) Session {
	return Session{SessionID: id, InputTokens: in, OutputTokens: out, CacheCreateTokens: cc, CacheReadTokens: cr}
}

func TestSanitizeTokens_LegitimateValuesUntouched(t *testing.T) {
	lim := TokenLimits{}
	s := sess("s", 100_000_000, 5_000_000, 3_000_000_000, 9_000_000_000) // 1 亿输入、缓存 90 亿都合法
	s.ActivityDeltas = []ActivityDelta{{InputTokensDelta: 100_000_000, OutputTokensDelta: 1, MessagesDelta: 1, SourceRef: "a"}}
	s.RecentMessages = []Message{{ExternalMessageID: "m", InputTokens: 1000, OutputTokens: 20}}
	if v := SanitizeTokens(&s, nil, lim); len(v) != 0 {
		t.Fatalf("unexpected violations: %+v", v)
	}
	if s.InputTokens != 100_000_000 || s.CacheReadTokens != 9_000_000_000 || s.ActivityDeltas[0].InputTokensDelta != 100_000_000 {
		t.Fatalf("legit values were modified: %+v", s)
	}
}

func TestSanitizeTokens_BoundaryEqualToLimitIsLegit_OneOverIsNot(t *testing.T) {
	lim := TokenLimits{}
	at := sess("s", MaxSessionTokens, MaxSessionTokens, MaxSessionCacheTokens, MaxSessionCacheTokens)
	at.ActivityDeltas = []ActivityDelta{{InputTokensDelta: MaxDeltaTokens, OutputTokensDelta: MaxDeltaTokens}}
	if v := SanitizeTokens(&at, nil, lim); len(v) != 0 {
		t.Fatalf("exactly-at-limit must be legit: %+v", v)
	}

	over := sess("s", MaxSessionTokens+1, MaxSessionTokens+1, MaxSessionCacheTokens+1, MaxSessionCacheTokens+1)
	over.ActivityDeltas = []ActivityDelta{{InputTokensDelta: MaxDeltaTokens + 1, OutputTokensDelta: MaxDeltaTokens + 1}}
	v := SanitizeTokens(&over, nil, lim)
	if len(v) != 6 {
		t.Fatalf("want 6 violations, got %d: %+v", len(v), v)
	}
	if over.InputTokens != 0 || over.OutputTokens != 0 || over.CacheCreateTokens != 0 || over.CacheReadTokens != 0 {
		t.Fatalf("over-limit totals with no prev must become 0: %+v", over)
	}
	if over.ActivityDeltas[0].InputTokensDelta != 0 || over.ActivityDeltas[0].OutputTokensDelta != 0 {
		t.Fatalf("over-limit deltas must be zeroed: %+v", over.ActivityDeltas[0])
	}
}

func TestSanitizeTokens_AccidentShape_FallsBackToPreviousTrustedTotals(t *testing.T) {
	// 2026-09-21 事故形态：2,070,054 M input / 10,246 M output
	s := sess("s", 2_070_054_000_000, 10_246_000_000, 0, 0)
	prev := &TokenTotals{Input: 5_000_000, Output: 25_000}
	v := SanitizeTokens(&s, prev, TokenLimits{})
	if len(v) != 2 {
		t.Fatalf("want 2 violations, got %+v", v)
	}
	if s.InputTokens != 5_000_000 || s.OutputTokens != 25_000 {
		t.Fatalf("must fall back to previous trusted totals, got %d/%d", s.InputTokens, s.OutputTokens)
	}
	if v[0].Field != "input_tokens" || v[0].Value != 2_070_054_000_000 || v[0].Limit != MaxSessionTokens || v[0].Replacement != 5_000_000 {
		t.Fatalf("violation detail wrong: %+v", v[0])
	}
}

func TestSanitizeTokens_NegativeAndExtremeValues(t *testing.T) {
	s := sess("s", -1, math.MinInt64, math.MaxInt64, -5)
	s.ActivityDeltas = []ActivityDelta{{InputTokensDelta: -7, OutputTokensDelta: math.MaxInt64, MessagesDelta: 2, SourceRef: "keep-me"}}
	s.RecentMessages = []Message{{InputTokens: -3, OutputTokens: math.MaxInt32}}
	v := SanitizeTokens(&s, &TokenTotals{Input: 11, Output: 12, CacheCreate: 13, CacheRead: 14}, TokenLimits{})
	if len(v) != 8 {
		t.Fatalf("want 8 violations, got %d: %+v", len(v), v)
	}
	if s.InputTokens != 11 || s.OutputTokens != 12 || s.CacheCreateTokens != 13 || s.CacheReadTokens != 14 {
		t.Fatalf("totals must fall back per-field: %+v", s)
	}
	d := s.ActivityDeltas[0]
	if d.InputTokensDelta != 0 || d.OutputTokensDelta != 0 || d.MessagesDelta != 2 || d.SourceRef != "keep-me" {
		t.Fatalf("delta must keep messages_delta/source_ref and zero tokens: %+v", d)
	}
	if s.RecentMessages[0].InputTokens != 0 || s.RecentMessages[0].OutputTokens != 0 {
		t.Fatalf("message tokens must be zeroed: %+v", s.RecentMessages[0])
	}
}

func TestSanitizeTokens_NilSessionAndCustomLimits(t *testing.T) {
	if v := SanitizeTokens(nil, nil, TokenLimits{}); v != nil {
		t.Fatalf("nil session must be a no-op")
	}
	s := sess("s", 1001, 10, 0, 0)
	v := SanitizeTokens(&s, nil, TokenLimits{MaxSession: 1000})
	if len(v) != 1 || s.InputTokens != 0 || s.OutputTokens != 10 {
		t.Fatalf("custom limit not honoured: %+v %+v", v, s)
	}
}

func TestDefaultTokenLimits_EnvOverride(t *testing.T) {
	t.Setenv("AM_TOKEN_MAX_SESSION", "12345")
	t.Setenv("AM_TOKEN_MAX_DELTA", "not-a-number")
	l := DefaultTokenLimits()
	if l.MaxSession != 12345 {
		t.Fatalf("MaxSession = %d", l.MaxSession)
	}
	if l.MaxDelta != MaxDeltaTokens {
		t.Fatalf("invalid env must fall back to default, got %d", l.MaxDelta)
	}
	if l.MaxSessionCache != MaxSessionCacheTokens {
		t.Fatalf("MaxSessionCache = %d", l.MaxSessionCache)
	}
}

func TestTokenSanitizer_RemembersLastTrustedAndRateLimitsWarn(t *testing.T) {
	san := NewTokenSanitizer(TokenLimits{})
	var warns []string
	san.warnf = func(f string, a ...any) { warns = append(warns, fmt.Sprintf(f, a...)) }
	clock := time.Date(2026, 9, 21, 10, 0, 0, 0, time.UTC)
	san.now = func() time.Time { return clock }

	// tick 1：正常值 → 记为可信
	snap := Snapshot{Sessions: []Session{sess("s1", 5_000_000, 25_000, 0, 0)}}
	if n := san.SanitizeSnapshot("codex", &snap); n != 0 {
		t.Fatalf("tick1 violations = %d", n)
	}
	// tick 2：坏值 → 回退到 tick1 的可信值，并 WARN
	snap = Snapshot{Sessions: []Session{sess("s1", 2_070_054_000_000, 10_246_000_000, 0, 0)}}
	if n := san.SanitizeSnapshot("codex", &snap); n != 2 {
		t.Fatalf("tick2 violations = %d", n)
	}
	if snap.Sessions[0].InputTokens != 5_000_000 || snap.Sessions[0].OutputTokens != 25_000 {
		t.Fatalf("not rolled back to last trusted: %+v", snap.Sessions[0])
	}
	if len(warns) != 2 {
		t.Fatalf("want 2 WARN lines (input+output), got %d: %v", len(warns), warns)
	}
	for _, w := range warns {
		if !strings.Contains(w, "provider=codex") || !strings.Contains(w, "session=s1") {
			t.Fatalf("WARN must identify provider/session: %q", w)
		}
	}
	// tick 3：仍是坏值，10 分钟内不再重复 WARN，但依然被拒
	clock = clock.Add(2 * time.Minute)
	snap = Snapshot{Sessions: []Session{sess("s1", 2_070_054_000_000, 10_246_000_000, 0, 0)}}
	if n := san.SanitizeSnapshot("codex", &snap); n != 2 || snap.Sessions[0].InputTokens != 5_000_000 {
		t.Fatalf("tick3 n=%d %+v", n, snap.Sessions[0])
	}
	if len(warns) != 2 {
		t.Fatalf("WARN must be rate limited, got %d", len(warns))
	}
	// 过了限流窗口再放行一轮
	clock = clock.Add(11 * time.Minute)
	snap = Snapshot{Sessions: []Session{sess("s1", 2_070_054_000_000, 0, 0, 0)}}
	san.SanitizeSnapshot("codex", &snap)
	if len(warns) != 3 {
		t.Fatalf("WARN should fire again after interval, got %d", len(warns))
	}
	if san.Rejected() != 5 {
		t.Fatalf("rejected = %d want 5", san.Rejected())
	}
}

func TestTokenSanitizer_LastTrustedTableIsBoundedToCurrentSnapshot(t *testing.T) {
	san := NewTokenSanitizer(TokenLimits{})
	san.warnf = func(string, ...any) {}
	snap := Snapshot{Sessions: []Session{sess("a", 1, 1, 0, 0), sess("b", 2, 2, 0, 0)}}
	san.SanitizeSnapshot("claude", &snap)
	snap = Snapshot{Sessions: []Session{sess("b", 3, 3, 0, 0)}}
	san.SanitizeSnapshot("claude", &snap)
	if len(san.last) != 1 {
		t.Fatalf("last-trusted table must only keep sessions of the latest snapshot, got %d", len(san.last))
	}
	// 同 ID 不同 provider 互不串
	san2 := NewTokenSanitizer(TokenLimits{})
	san2.warnf = func(string, ...any) {}
	s1 := Snapshot{Sessions: []Session{sess("x", 7, 7, 0, 0)}}
	san2.SanitizeSnapshot("claude", &s1)
	s2 := Snapshot{Sessions: []Session{sess("x", MaxSessionTokens+1, 0, 0, 0)}}
	san2.SanitizeSnapshot("codex", &s2)
	if s2.Sessions[0].InputTokens != 0 {
		t.Fatalf("different provider must not reuse claude's trusted value, got %d", s2.Sessions[0].InputTokens)
	}
}

// ---- 包装 Provider ----

type fakeProvider struct {
	typ  string
	snap Snapshot
	err  error
}

func (f *fakeProvider) Type() string          { return f.typ }
func (f *fakeProvider) TargetVersion() string { return "v" }
func (f *fakeProvider) IsInstalled() bool     { return true }
func (f *fakeProvider) Snapshot(context.Context) (Snapshot, error) {
	return f.snap, f.err
}

type fullProvider struct {
	fakeProvider
	lookback time.Duration
}

func (f *fullProvider) SetLookback(d time.Duration) { f.lookback = d }
func (f *fullProvider) WatchHints() []string        { return []string{"/tmp/x"} }
func (f *fullProvider) Account() Account            { return Account{Provider: "cursor", Email: "a@b.c"} }

func TestRegistry_RegisterWrapsProviderAndSanitizesSnapshot(t *testing.T) {
	fp := &fakeProvider{typ: "codex", snap: Snapshot{Type: "codex", Sessions: []Session{
		sess("s1", 2_070_054_000_000, 10_246_000_000, 0, 0),
		sess("s2", 100_000_000, 500_000, 0, 0),
	}}}
	reg := NewRegistry()
	reg.Register(fp)
	all := reg.All()
	if len(all) != 1 || all[0].Type() != "codex" || all[0].TargetVersion() != "v" || !all[0].IsInstalled() {
		t.Fatalf("wrapper must transparently expose Provider methods: %+v", all)
	}
	snap, err := all[0].Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if snap.Sessions[0].InputTokens != 0 || snap.Sessions[0].OutputTokens != 0 {
		t.Fatalf("poisoned session must be clamped: %+v", snap.Sessions[0])
	}
	if snap.Sessions[1].InputTokens != 100_000_000 || snap.Sessions[1].OutputTokens != 500_000 {
		t.Fatalf("normal session must be untouched: %+v", snap.Sessions[1])
	}
}

func TestSanitizingProvider_ErrorPassesThroughUntouched(t *testing.T) {
	want := errors.New("boom")
	p := WithTokenSanity(&fakeProvider{typ: "x", err: want}, TokenLimits{})
	if _, err := p.Snapshot(context.Background()); !errors.Is(err, want) {
		t.Fatalf("err = %v", err)
	}
}

func TestSanitizingProvider_ForwardsOptionalInterfaces(t *testing.T) {
	full := &fullProvider{fakeProvider: fakeProvider{typ: "cursor"}}
	p := WithTokenSanity(full, TokenLimits{})

	ls, ok := p.(LookbackSetter)
	if !ok {
		t.Fatal("wrapper must implement LookbackSetter for reporter.applyLookback")
	}
	ls.SetLookback(30 * 24 * time.Hour)
	if full.lookback != 30*24*time.Hour {
		t.Fatalf("SetLookback not forwarded: %v", full.lookback)
	}
	wh, ok := p.(WatchHints)
	if !ok || len(wh.WatchHints()) != 1 || wh.WatchHints()[0] != "/tmp/x" {
		t.Fatalf("WatchHints not forwarded")
	}
	ap, ok := p.(AccountProvider)
	if !ok || ap.Account().Email != "a@b.c" {
		t.Fatalf("Account not forwarded")
	}

	// 底层不支持可选接口时：空操作 / nil / 零值
	bare := WithTokenSanity(&fakeProvider{typ: "claude"}, TokenLimits{})
	bare.(LookbackSetter).SetLookback(time.Hour) // 不 panic
	if bare.(WatchHints).WatchHints() != nil {
		t.Fatal("bare provider must expose nil WatchHints")
	}
	if !bare.(AccountProvider).Account().IsZero() {
		t.Fatal("bare provider must expose zero Account")
	}
}

func TestWithTokenSanity_DoesNotDoubleWrapAndHandlesNil(t *testing.T) {
	p := WithTokenSanity(&fakeProvider{typ: "x"}, TokenLimits{})
	if WithTokenSanity(p, TokenLimits{}) != p {
		t.Fatal("must not wrap twice")
	}
	if WithTokenSanity(nil, TokenLimits{}) != nil {
		t.Fatal("nil in, nil out")
	}
	if _, ok := p.(*sanitizingProvider); !ok {
		t.Fatal("expected *sanitizingProvider")
	}
	if p.(*sanitizingProvider).Unwrap().Type() != "x" {
		t.Fatal("Unwrap must return inner provider")
	}
}
