package reporter

// tickOnce 级别的集成测试：真 HTTP（httptest）+ 假 provider，验证
//   - 各种"服务端没处理成功"的失败都不写 outbox、不推进游标、进入退避；
//   - 退避期内 tick 不发起请求，成功一次即清零；
//   - 413 缩小 body、遗留 outbox 的处理；
//   - bootstrap 回填在字节预算下分多个 tick 完成，不丢不重，且 bootstrap 模式在排空后才关闭。

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/apiclient"
	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/monitor"
)

// isolateHome 让所有落盘位置（config / state / cache）指向临时目录，测试绝不碰开发机的真实目录。
func isolateHome(t *testing.T) {
	t.Helper()
	dir := t.TempDir()
	for _, k := range []string{"HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME", "ProgramData", "LocalAppData", "AppData"} {
		t.Setenv(k, dir)
	}
}

// fakeProvider 每次 Snapshot 都用 build 重新构造会话（与真 provider 一样每个 tick 产出全新切片）。
type fakeProvider struct {
	typ   string
	build func() []monitor.Session

	mu        sync.Mutex
	lookbacks []time.Duration
}

func (p *fakeProvider) Type() string          { return p.typ }
func (p *fakeProvider) TargetVersion() string { return "test" }
func (p *fakeProvider) IsInstalled() bool     { return true }
func (p *fakeProvider) Snapshot(context.Context) (monitor.Snapshot, error) {
	return monitor.Snapshot{Sessions: p.build()}, nil
}
func (p *fakeProvider) SetLookback(d time.Duration) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.lookbacks = append(p.lookbacks, d)
}
func (p *fakeProvider) lastLookback() time.Duration {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.lookbacks) == 0 {
		return 0
	}
	return p.lookbacks[len(p.lookbacks)-1]
}

// reportServer 记录收到的每个 /report 请求里的消息 ID，并按 handler 决定响应。
type reportServer struct {
	*httptest.Server
	requests atomic.Int64
	mu       sync.Mutex
	received []string // 收到（且被判定成功）的 "provider:session:msgID"
	bodies   []int    // 每个成功请求的线上字节数
}

func newReportServer(t *testing.T, respond func(w http.ResponseWriter, r *http.Request) bool) *reportServer {
	t.Helper()
	rs := &reportServer{}
	rs.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rs.requests.Add(1)
		wire, _ := io.ReadAll(r.Body)
		if respond != nil && !respond(w, r) {
			return
		}
		raw := wire
		if strings.EqualFold(r.Header.Get("Content-Encoding"), "gzip") {
			zr, err := gzip.NewReader(bytes.NewReader(wire))
			if err != nil {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			raw, _ = io.ReadAll(zr)
		}
		var req reportRequest
		if err := json.Unmarshal(raw, &req); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		rs.mu.Lock()
		for _, m := range req.Monitors {
			for _, s := range m.Sessions {
				for _, msg := range s.RecentMessages {
					rs.received = append(rs.received, fmt.Sprintf("%s:%s:%s", m.Type, s.SessionID, msg.ExternalMessageID))
				}
			}
		}
		rs.bodies = append(rs.bodies, len(wire))
		rs.mu.Unlock()
		_, _ = w.Write([]byte(`{"code":0,"message":"ok","data":{"sessions":1,"events":0,"messages":0,"active":false}}`))
	}))
	t.Cleanup(rs.Close)
	return rs
}

func (rs *reportServer) receivedIDs() []string {
	rs.mu.Lock()
	defer rs.mu.Unlock()
	return append([]string(nil), rs.received...)
}

// newTickReporter 构造一个能真正跑 tickOnce 的 Reporter：游标 / outbox / 指纹都落在临时目录。
func newTickReporter(t *testing.T, srvURL string, timeout time.Duration, providers ...monitor.Provider) (*Reporter, *fakeClock) {
	t.Helper()
	isolateHome(t)
	reg := monitor.NewRegistry()
	for _, p := range providers {
		reg.Register(p)
	}
	stateDir := t.TempDir()
	ob := &Outbox{dir: filepath.Join(stateDir, "outbox"), maxFiles: 2000, maxDrainPerCall: outboxDrainPerTick}
	if err := os.MkdirAll(ob.dir, 0o755); err != nil {
		t.Fatal(err)
	}
	ob.pending.Store(-1)

	clk := &fakeClock{now: time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC)}
	cfg := &config.Config{ServerURL: srvURL, UserCode: "u1", AgentID: "agent-1", AgentSecret: "secret"}
	r := &Reporter{
		cfg:      cfg,
		registry: reg,
		client:   apiclient.New(srvURL, timeout),
		cursors:  &MsgCursorStore{path: filepath.Join(stateDir, cursorsFileName), cursors: make(map[string]MsgCursor)},
		outbox:   ob,
		userCode: cfg.UserCode,
		budget:   newBodyBudget(cfg.ReportBodyBudget()),
		nowFn:    clk.Now,
		randFn:   fixedRand(0.5),
	}
	r.creds.Store(&agentCreds{id: cfg.AgentID, secret: cfg.AgentSecret})
	r.initSentState(stateDir, clk.Now())
	r.setCadence((120 * time.Second).Milliseconds(), (15 * time.Second).Milliseconds())
	return r, clk
}

func outboxFiles(t *testing.T, r *Outbox) int {
	t.Helper()
	entries, err := os.ReadDir(r.dir)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".json") {
			n++
		}
	}
	return n
}

func smallProvider() *fakeProvider {
	return &fakeProvider{typ: "claude", build: func() []monitor.Session {
		return []monitor.Session{mkSess("s1", "idle", budgetT0, mkMsgs("s1", 5, 200))}
	}}
}

// 所有"服务没处理成功"的失败：不写 outbox、游标不推进、进入退避；恢复后同样的增量被重发且游标才推进。
func TestTickOnce_FailuresNeverSpoolAndBackOff(t *testing.T) {
	cases := []struct {
		name       string
		handler    func(w http.ResponseWriter, r *http.Request) bool
		wantMinGap time.Duration // busyUntil 至少要多远
	}{
		{"500", func(w http.ResponseWriter, _ *http.Request) bool { w.WriteHeader(500); return false }, 120 * time.Second},
		{"502 from nginx", func(w http.ResponseWriter, _ *http.Request) bool { w.WriteHeader(502); return false }, 120 * time.Second},
		{"504 from nginx", func(w http.ResponseWriter, _ *http.Request) bool { w.WriteHeader(504); return false }, 120 * time.Second},
		{"503 busy", func(w http.ResponseWriter, _ *http.Request) bool {
			w.WriteHeader(503)
			_, _ = w.Write([]byte(`{"code":50301}`))
			return false
		}, 120 * time.Second},
		{"503 with Retry-After honored", func(w http.ResponseWriter, _ *http.Request) bool {
			w.Header().Set("Retry-After", "600")
			w.WriteHeader(503)
			return false
		}, 600 * time.Second},
		{"429", func(w http.ResponseWriter, _ *http.Request) bool { w.WriteHeader(429); return false }, 120 * time.Second},
		{"HTTP 200 + business code 50000 (legacy pool timeout)", func(w http.ResponseWriter, _ *http.Request) bool {
			_, _ = w.Write([]byte(`{"code":50000,"message":"pool timeout"}`))
			return false
		}, 120 * time.Second},
		{"401 / signature error", func(w http.ResponseWriter, _ *http.Request) bool {
			w.WriteHeader(401)
			_, _ = w.Write([]byte(`{"code":10001}`))
			return false
		}, 120 * time.Second},
		{"413", func(w http.ResponseWriter, _ *http.Request) bool { w.WriteHeader(413); return false }, 120 * time.Second},
		{"413 + business code 41301 + Connection: close", func(w http.ResponseWriter, _ *http.Request) bool {
			w.Header().Set("Connection", "close")
			w.WriteHeader(413)
			_, _ = w.Write([]byte(`{"code":41301,"message":"payload too large"}`))
			return false
		}, 120 * time.Second},
		{"HTTP 200 + business code 41301", func(w http.ResponseWriter, _ *http.Request) bool {
			_, _ = w.Write([]byte(`{"code":41301,"message":"payload too large"}`))
			return false
		}, 120 * time.Second},
		{"503 + 50301 + Retry-After 30 (readiness gate)", func(w http.ResponseWriter, _ *http.Request) bool {
			w.Header().Set("Retry-After", "30")
			w.WriteHeader(503)
			_, _ = w.Write([]byte(`{"code":50301,"message":"starting"}`))
			return false
		}, 120 * time.Second},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var fail atomic.Bool
			fail.Store(true)
			rs := newReportServer(t, func(w http.ResponseWriter, r *http.Request) bool {
				if fail.Load() {
					return tc.handler(w, r)
				}
				return true
			})
			r, clk := newTickReporter(t, rs.URL, 5*time.Second, smallProvider())

			err := r.tickOnce(context.Background())
			if err == nil {
				t.Fatal("tick must report the failure")
			}
			if n := outboxFiles(t, r.outbox); n != 0 {
				t.Fatalf("outbox has %d file(s): failed bodies must never be spooled (cursors were not advanced, the next tick re-sends)", n)
			}
			if r.cursors.Size() != 0 {
				t.Fatal("cursors advanced on a failed tick")
			}
			if r.failStreak != 1 || !r.inBackoff() {
				t.Fatalf("failStreak=%d inBackoff=%v, want 1/true", r.failStreak, r.inBackoff())
			}
			if got := r.backoffRemaining(); got < tc.wantMinGap {
				t.Errorf("busy window %s shorter than expected %s", got, tc.wantMinGap)
			}
			if r.lastActive.Load() {
				t.Error("failure must drop to the idle cadence")
			}

			// 退避期内：任何触发路径都不发起请求。
			before := rs.requests.Load()
			if err := r.tickOnce(context.Background()); err != nil {
				t.Fatalf("skipped tick should not error: %v", err)
			}
			if rs.requests.Load() != before {
				t.Fatal("a tick inside the busy window must not touch the server")
			}

			// 服务恢复、退避结束：同样的增量被重发，游标此时才推进，退避状态清零。
			fail.Store(false)
			clk.Advance(20 * time.Minute)
			if err := r.tickOnce(context.Background()); err != nil {
				t.Fatalf("recovery tick failed: %v", err)
			}
			if got := len(rs.receivedIDs()); got != 5 {
				t.Errorf("server received %d messages after recovery, want the 5 that were never acknowledged", got)
			}
			if r.cursors.Size() != 1 {
				t.Errorf("cursors after success = %d, want 1", r.cursors.Size())
			}
			if r.failStreak != 0 || r.inBackoff() {
				t.Error("success must clear the backoff state")
			}
			if n := outboxFiles(t, r.outbox); n != 0 {
				t.Errorf("outbox has %d file(s) after recovery", n)
			}
		})
	}
}

// 请求超时与连接错误同样只退避、不落 outbox。
func TestTickOnce_TimeoutAndConnectionErrorsNeverSpool(t *testing.T) {
	t.Run("timeout", func(t *testing.T) {
		rs := newReportServer(t, func(w http.ResponseWriter, r *http.Request) bool {
			select {
			case <-r.Context().Done():
			case <-time.After(400 * time.Millisecond):
			}
			return false
		})
		r, _ := newTickReporter(t, rs.URL, 60*time.Millisecond, smallProvider())
		if err := r.tickOnce(context.Background()); err == nil {
			t.Fatal("expected a timeout error")
		}
		if n := outboxFiles(t, r.outbox); n != 0 || r.cursors.Size() != 0 || !r.inBackoff() {
			t.Fatalf("outbox=%d cursors=%d inBackoff=%v", n, r.cursors.Size(), r.inBackoff())
		}
	})
	t.Run("connection refused", func(t *testing.T) {
		rs := newReportServer(t, nil)
		url := rs.URL
		rs.Close()
		r, _ := newTickReporter(t, url, time.Second, smallProvider())
		if err := r.tickOnce(context.Background()); err == nil {
			t.Fatal("expected a connection error")
		}
		if n := outboxFiles(t, r.outbox); n != 0 || r.cursors.Size() != 0 || !r.inBackoff() {
			t.Fatalf("outbox=%d cursors=%d inBackoff=%v", n, r.cursors.Size(), r.inBackoff())
		}
	})
}

// 连续失败：退避随次数指数增长，不会因为反复重试变短。
func TestTickOnce_ConsecutiveFailuresGrowBackoff(t *testing.T) {
	rs := newReportServer(t, func(w http.ResponseWriter, _ *http.Request) bool { w.WriteHeader(500); return false })
	r, clk := newTickReporter(t, rs.URL, 5*time.Second, smallProvider())
	var prev time.Duration
	for i := 1; i <= 8; i++ {
		if err := r.tickOnce(context.Background()); err == nil {
			t.Fatal("expected failure")
		}
		rem := r.backoffRemaining()
		if rem < prev {
			t.Fatalf("failure %d: window %s shrank from %s", i, rem, prev)
		}
		if rem > backoffMax {
			t.Fatalf("failure %d: window %s exceeds the cap %s", i, rem, backoffMax)
		}
		prev = rem
		clk.Advance(rem + time.Second)
	}
	if prev != 450*time.Second { // rnd=0.5 → 300s + 150s
		t.Errorf("capped window = %s, want 450s", prev)
	}
	if n := outboxFiles(t, r.outbox); n != 0 {
		t.Errorf("outbox files = %d", n)
	}
}

// 服务端持续几分钟 503（启动补丁没跑完的就绪门 / 舱壁满）：agent 按 nextTickDelay 排期，
// 十分钟内只探测寥寥几次，且间隔不缩短；恢复后第一个 tick 就能发出去。
func TestTickOnce_SustainedServerBusyBacksOff(t *testing.T) {
	var busy atomic.Bool
	busy.Store(true)
	rs := newReportServer(t, func(w http.ResponseWriter, _ *http.Request) bool {
		if busy.Load() {
			w.Header().Set("Retry-After", "30")
			w.WriteHeader(503)
			_, _ = w.Write([]byte(`{"code":50301,"message":"starting"}`))
			return false
		}
		return true
	})
	r, clk := newTickReporter(t, rs.URL, 5*time.Second, smallProvider())

	start := clk.Now()
	var gaps []time.Duration
	last := start
	for clk.Now().Sub(start) < 10*time.Minute {
		if err := r.tickOnce(context.Background()); err == nil {
			t.Fatal("expected 503")
		}
		if now := clk.Now(); now != start && len(gaps) < 64 {
			gaps = append(gaps, now.Sub(last))
		}
		last = clk.Now()
		clk.Advance(r.nextTickDelay()) // Run 里 ticker.Reset(nextTickDelay()) 之后到点触发
	}
	if got := rs.requests.Load(); got > 6 {
		t.Errorf("%d requests in 10 minutes of sustained 503: the agents are hammering a starting server", got)
	}
	for i := 1; i < len(gaps); i++ {
		if gaps[i] < gaps[i-1] {
			t.Errorf("gap %d (%s) shrank vs the previous (%s)", i, gaps[i], gaps[i-1])
		}
	}
	if outboxFiles(t, r.outbox) != 0 {
		t.Error("nothing may be spooled during a 503 storm")
	}

	busy.Store(false)
	if err := r.tickOnce(context.Background()); err != nil {
		t.Fatalf("tick after the server became ready: %v", err)
	}
	if len(rs.receivedIDs()) != 5 {
		t.Errorf("the increments never acknowledged must be delivered once the server is up, got %d", len(rs.receivedIDs()))
	}
}

// AGENT_NOT_FOUND 不算"忙"：清凭证，下个 tick 重新注册；同样不写 outbox。
func TestTickOnce_AgentNotFoundClearsCredsWithoutSpooling(t *testing.T) {
	rs := newReportServer(t, func(w http.ResponseWriter, _ *http.Request) bool {
		_, _ = w.Write([]byte(`{"code":10004,"message":"agent not found"}`))
		return false
	})
	r, _ := newTickReporter(t, rs.URL, 5*time.Second, smallProvider())
	if err := r.tickOnce(context.Background()); !apiclient.IsAgentNotFound(err) {
		t.Fatalf("want agent-not-found, got %v", err)
	}
	if r.cfg.AgentID != "" || r.cfg.AgentSecret != "" {
		t.Error("credentials must be cleared so the next tick re-registers")
	}
	if outboxFiles(t, r.outbox) != 0 {
		t.Error("nothing may be spooled")
	}
	if r.inBackoff() {
		t.Error("agent-not-found must not add backoff on top of the re-register path")
	}
}

// 413：body 缩小（预算收缩），随后能发出去；不写 outbox。
func TestTickOnce_413ShrinksBodyUntilItFits(t *testing.T) {
	const limit = 40 << 10 // 模拟网关 client_max_body_size：线上字节 > 40KB 回 413
	rs := newReportServer(t, func(w http.ResponseWriter, r *http.Request) bool {
		if r.ContentLength > limit {
			w.WriteHeader(http.StatusRequestEntityTooLarge)
			return false
		}
		return true
	})
	// 不可压缩的正文，让线上字节 ≈ JSON 字节。
	build := func() []monitor.Session {
		var sessions []monitor.Session
		for i := 0; i < 4; i++ {
			id := fmt.Sprintf("s%d", i)
			msgs := mkMsgs(id, 10, 0)
			for j := range msgs {
				msgs[j].Text = randomText(8_000, int64(i*100+j))
			}
			sessions = append(sessions, mkSess(id, "idle", budgetT0.Add(time.Duration(i)*time.Minute), msgs))
		}
		return sessions
	}
	p := &fakeProvider{typ: "claude", build: build}
	r, clk := newTickReporter(t, rs.URL, 5*time.Second, p)
	r.budget = newBodyBudget(4 << 20)

	for tick := 0; tick < 40; tick++ {
		_ = r.tickOnce(context.Background())
		clk.Advance(30 * time.Minute)
		if got := len(rs.receivedIDs()); got == 40 {
			break
		}
	}
	got := rs.receivedIDs()
	if len(got) != 40 {
		t.Fatalf("received %d messages, want all 40 (a 413 must shrink the body, not resend it forever)", len(got))
	}
	seen := map[string]bool{}
	for _, id := range got {
		if seen[id] {
			t.Fatalf("duplicate delivery of %s", id)
		}
		seen[id] = true
	}
	if r.budget.current() >= 4<<20 {
		t.Errorf("budget never shrank: %d", r.budget.current())
	}
	if outboxFiles(t, r.outbox) != 0 {
		t.Error("outbox must stay empty")
	}
}

func randomText(n int, seed int64) string {
	const alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	x := uint64(seed)*6364136223846793005 + 1442695040888963407
	b := make([]byte, n)
	for i := range b {
		x = x*6364136223846793005 + 1442695040888963407
		b[i] = alphabet[(x>>33)%uint64(len(alphabet))]
	}
	return string(b)
}

// 旧版本遗留在磁盘上的 outbox：服务端好了就排空；排空失败则退避、不再追加新文件、本 tick 不发新包。
func TestTickOnce_LegacyOutboxDrainAndFailure(t *testing.T) {
	var mode atomic.Int32 // 0 ok, 1 fail 500, 2 fail 413
	rs := newReportServer(t, func(w http.ResponseWriter, _ *http.Request) bool {
		switch mode.Load() {
		case 1:
			w.WriteHeader(500)
			return false
		case 2:
			w.WriteHeader(413)
			return false
		}
		return true
	})
	r, clk := newTickReporter(t, rs.URL, 5*time.Second, smallProvider())
	legacy := []byte(`{"agent_id":"agent-1","agent_version":"old","captured_at":"2026-09-01T10:00:00","monitors":[]}`)
	if err := r.outbox.Append(legacy); err != nil {
		t.Fatal(err)
	}

	mode.Store(1)
	if err := r.tickOnce(context.Background()); err == nil {
		t.Fatal("drain failure must surface")
	}
	if n := outboxFiles(t, r.outbox); n != 1 {
		t.Fatalf("outbox files = %d, want the single legacy file and nothing new", n)
	}
	if !r.inBackoff() || r.failStreak != 1 {
		t.Error("a failed drain is a server failure: back off")
	}
	if got := rs.requests.Load(); got != 1 {
		t.Errorf("requests = %d: after a failed drain the fresh body must not be sent in the same tick", got)
	}

	// 遗留文件是超大整包（413）：永远发不出去，丢弃它，队列不被卡死；本 tick 的新包照常发。
	mode.Store(2)
	clk.Advance(time.Hour)
	_ = r.tickOnce(context.Background()) // drain: 413 → 丢弃；随后新包同样 413 → 失败
	if n := outboxFiles(t, r.outbox); n != 0 {
		t.Errorf("oversize legacy file should have been discarded, files=%d", n)
	}

	mode.Store(0)
	clk.Advance(time.Hour)
	if err := r.tickOnce(context.Background()); err != nil {
		t.Fatalf("tick after recovery: %v", err)
	}
	if len(rs.receivedIDs()) != 5 {
		t.Errorf("fresh increments must still be delivered, got %d", len(rs.receivedIDs()))
	}
}

// bootstrap 回填：单请求原先可达数百 MB，现在按字节预算分成多个 tick；不丢不重；
// bootstrap 模式只在所有内容发完之后才关闭（否则被推迟的老会话再也扫不到）。
func TestTickOnce_BootstrapBackfillIsBatchedAndBootstrapEndsOnlyWhenDrained(t *testing.T) {
	const (
		nSessions = 12
		nMsgs     = 40
		budget    = 96 << 10
	)
	p := &fakeProvider{typ: "claude", build: func() []monitor.Session {
		var sessions []monitor.Session
		for i := 0; i < nSessions; i++ {
			id := fmt.Sprintf("s%02d", i)
			msgs := mkMsgs(id, nMsgs, 0)
			for j := range msgs {
				msgs[j].Text = randomText(1_500, int64(i*1000+j))
			}
			sessions = append(sessions, mkSess(id, "idle", budgetT0.Add(time.Duration(i)*time.Minute), msgs))
		}
		return sessions
	}}
	rs := newReportServer(t, nil)
	r, clk := newTickReporter(t, rs.URL, 10*time.Second, p)
	r.budget = newBodyBudget(budget)
	r.bootstrap = true
	applyLookback(r.registry, monitor.BootstrapLookback)

	ticks := 0
	for ; ticks < 200 && r.bootstrap; ticks++ {
		if err := r.tickOnce(context.Background()); err != nil {
			t.Fatalf("tick %d: %v", ticks, err)
		}
		clk.Advance(time.Minute)
		if r.bootstrap {
			if !r.backlog.Load() {
				t.Fatalf("tick %d: still bootstrapping but the backlog flag (fast drain cadence) is off", ticks)
			}
			if got := p.lastLookback(); got != monitor.BootstrapLookback {
				t.Fatalf("tick %d: lookback switched to %s before the backlog drained", ticks, got)
			}
		}
	}
	if r.bootstrap {
		t.Fatal("bootstrap never finished")
	}
	if ticks < 3 {
		t.Fatalf("backfill finished in %d ticks: it should have been split into several budgeted batches", ticks)
	}
	if got := p.lastLookback(); got != monitor.DefaultLookback {
		t.Errorf("lookback after drain = %s, want %s", got, monitor.DefaultLookback)
	}
	if r.backlog.Load() {
		t.Error("backlog flag must be cleared once drained")
	}

	got := rs.receivedIDs()
	if len(got) != nSessions*nMsgs {
		t.Fatalf("server received %d messages, want %d", len(got), nSessions*nMsgs)
	}
	seen := make(map[string]bool, len(got))
	for _, id := range got {
		if seen[id] {
			t.Fatalf("duplicate delivery of %s", id)
		}
		seen[id] = true
	}
	for i := 0; i < nSessions; i++ {
		for j := 0; j < nMsgs; j++ {
			if id := fmt.Sprintf("claude:s%02d:s%02d-m%03d", i, i, j); !seen[id] {
				t.Fatalf("message %s was never delivered (cursor advanced past unsent data)", id)
			}
		}
	}
	rs.mu.Lock()
	defer rs.mu.Unlock()
	for i, n := range rs.bodies {
		// 线上字节（gzip 后）远小于预算；关键是没有任何一个请求接近"整包全量"。
		if n > budget {
			t.Errorf("request %d carried %d wire bytes, above the %d JSON budget", i, n, budget)
		}
	}
}

// 重启后第一个 tick 不再重发 48 小时内全部未变化的空闲会话：指纹落盘并在启动时加载。
func TestTickOnce_UnchangedIdleSessionsSkippedAfterRestart(t *testing.T) {
	p := &fakeProvider{typ: "claude", build: func() []monitor.Session {
		s := mkSess("idle-1", "idle", budgetT0, nil)
		s2 := mkSess("idle-2", "idle", budgetT0, nil)
		return []monitor.Session{s, s2}
	}}
	rs := newReportServer(t, nil)

	r1, clk := newTickReporter(t, rs.URL, 5*time.Second, p)
	if err := r1.tickOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(r1.sent) != 2 {
		t.Fatalf("fingerprints recorded = %d, want 2", len(r1.sent))
	}
	if _, err := os.Stat(r1.sentPath); err != nil {
		t.Fatalf("fingerprints were not persisted: %v", err)
	}

	// "重启"：全新的 Reporter，从同一个 state 目录加载。
	r2 := &Reporter{nowFn: clk.Now}
	r2.initSentState(filepath.Dir(r1.sentPath), clk.Now().Add(time.Minute))
	if len(r2.sent) != 2 {
		t.Fatalf("restarted reporter loaded %d fingerprints, want 2", len(r2.sent))
	}
	mons := []monitor.Snapshot{{Type: "claude", Sessions: p.build()}}
	if dropped := r2.dropUnchangedIdleSessions(mons, clk.Now().Add(time.Minute)); dropped != 2 {
		t.Errorf("restart resent unchanged idle sessions: dropped=%d, want 2", dropped)
	}

	// 强制重发周期语义不变：距上次成功发送超过 15 分钟仍会重发。
	mons = []monitor.Snapshot{{Type: "claude", Sessions: p.build()}}
	if dropped := r2.dropUnchangedIdleSessions(mons, clk.Now().Add(unchangedResyncInterval+time.Second)); dropped != 0 {
		t.Errorf("sessions older than the resync interval must be re-sent, dropped=%d", dropped)
	}
}

// Run 在第一个 tick 之前有随机启动延迟：延迟期间不碰服务端，延迟结束后才上报；延迟期间 ctx 取消能立刻退出。
func TestRun_StartupDelayPrecedesFirstTick(t *testing.T) {
	t.Run("first tick waits for the delay", func(t *testing.T) {
		rs := newReportServer(t, nil)
		r, _ := newTickReporter(t, rs.URL, 5*time.Second, smallProvider())
		r.nowFn = nil              // 用真实时钟：这里要验证的是真实的等待
		r.randFn = fixedRand(0.01) // 60s × 0.01 = 600ms
		ctx, cancel := context.WithCancel(context.Background())
		done := make(chan struct{})
		go func() { _ = r.Run(ctx); close(done) }()

		time.Sleep(100 * time.Millisecond)
		if n := rs.requests.Load(); n != 0 {
			t.Fatalf("%d request(s) reached the server before the startup delay elapsed", n)
		}
		deadline := time.Now().Add(5 * time.Second)
		for rs.requests.Load() == 0 && time.Now().Before(deadline) {
			time.Sleep(20 * time.Millisecond)
		}
		if rs.requests.Load() == 0 {
			t.Fatal("first tick never happened after the startup delay")
		}
		cancel()
		<-done
	})
	t.Run("cancel during the delay returns promptly without reporting", func(t *testing.T) {
		rs := newReportServer(t, nil)
		r, _ := newTickReporter(t, rs.URL, 5*time.Second, smallProvider())
		r.nowFn = nil
		r.randFn = fixedRand(0.9) // 54s
		ctx, cancel := context.WithCancel(context.Background())
		done := make(chan struct{})
		go func() { _ = r.Run(ctx); close(done) }()
		time.Sleep(50 * time.Millisecond)
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Fatal("Run did not return after ctx cancel during the startup delay")
		}
		if n := rs.requests.Load(); n != 0 {
			t.Errorf("%d request(s) sent although the agent was stopped during the startup delay", n)
		}
	})
}
