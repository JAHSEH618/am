package reporter

import (
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/apiclient"
)

func TestBackoffDelay(t *testing.T) {
	cases := []struct {
		name       string
		streak     int
		retryAfter time.Duration
		rnd        float64
		want       time.Duration
	}{
		{"1st failure, rnd=0 → exp/2", 1, 0, 0, 15 * time.Second},
		{"2nd failure doubles", 2, 0, 0, 30 * time.Second},
		{"3rd", 3, 0, 0, 60 * time.Second},
		{"4th", 4, 0, 0, 120 * time.Second},
		{"5th", 5, 0, 0, 240 * time.Second},
		{"6th reaches the 10min exp cap", 6, 0, 0, 5 * time.Minute},
		{"far beyond the cap stays capped", 50, 0, 0, 5 * time.Minute},
		{"streak<1 treated as 1", 0, 0, 0, 15 * time.Second},
		{"negative streak treated as 1", -3, 0, 0, 15 * time.Second},
		{"rnd=0.5 is the midpoint of [exp/2, exp)", 3, 0, 0.5, 90 * time.Second},
		{"capped streak with rnd=0.5", 10, 0, 0.5, 450 * time.Second},
		{"Retry-After is a floor: larger wins", 1, 90 * time.Second, 0, 90 * time.Second},
		{"Retry-After smaller than backoff is ignored", 4, 10 * time.Second, 0, 120 * time.Second},
		{"Retry-After above the 10min exp cap is still honored", 6, 30 * time.Minute, 0.9, 30 * time.Minute},
		{"absurd Retry-After is capped at 1h", 1, 48 * time.Hour, 0.3, time.Hour},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := backoffDelay(tc.streak, tc.retryAfter, tc.rnd); got != tc.want {
				t.Errorf("backoffDelay(%d, %s, %v) = %s, want %s", tc.streak, tc.retryAfter, tc.rnd, got, tc.want)
			}
		})
	}
}

// 性质：无 Retry-After 时结果落在 [exp/2, exp) ⊂ [15s, 10min]；同一随机数下随失败次数单调不减。
func TestBackoffDelay_Properties(t *testing.T) {
	for _, rnd := range []float64{0, 0.13, 0.5, 0.87, 0.999999} {
		var prev time.Duration
		for streak := 1; streak <= 60; streak++ {
			d := backoffDelay(streak, 0, rnd)
			if d < backoffBase/2 || d >= backoffMax {
				t.Fatalf("streak=%d rnd=%v: %s outside [%s, %s)", streak, rnd, d, backoffBase/2, backoffMax)
			}
			if d < prev {
				t.Fatalf("streak=%d rnd=%v: %s < previous %s (must not shrink as failures pile up)", streak, rnd, d, prev)
			}
			prev = d
		}
	}
}

func TestJitterDuration(t *testing.T) {
	cases := []struct {
		name string
		d    time.Duration
		rnd  float64
		want time.Duration
	}{
		{"rnd=0 → -20%", 120 * time.Second, 0, 96 * time.Second},
		{"rnd=0.5 → unchanged", 120 * time.Second, 0.5, 120 * time.Second},
		{"rnd→1 → +20%", 120 * time.Second, 1, 144 * time.Second},
		{"the 5s fast-cadence floor survives downward jitter", 5 * time.Second, 0, 5 * time.Second},
		{"floor also applies to 6s (−20% = 4.8s)", 6 * time.Second, 0, 5 * time.Second},
		{"sub-floor base intervals are only jittered upward-safe", 3 * time.Second, 0, 3 * time.Second},
		{"zero stays zero", 0, 0.7, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := jitterDuration(tc.d, tickJitterFrac, tc.rnd); got != tc.want {
				t.Errorf("jitterDuration(%s, %v) = %s, want %s", tc.d, tc.rnd, got, tc.want)
			}
		})
	}
	// 上下界：任何随机数都落在 ±20%（含下限保护）之内。
	base := 15 * time.Second
	for _, rnd := range []float64{0, 0.25, 0.5, 0.75, 0.999999} {
		got := jitterDuration(base, tickJitterFrac, rnd)
		if got < minActiveReportInterval || got < time.Duration(float64(base)*0.8) || got > time.Duration(float64(base)*1.2) {
			t.Errorf("jitter(%s, %v) = %s out of bounds", base, rnd, got)
		}
	}
}

func fixedRand(v float64) func() float64 { return func() float64 { return v } }

func TestStartupDelay(t *testing.T) {
	for _, tc := range []struct {
		rnd  float64
		want time.Duration
	}{
		{0, 0},
		{0.5, 30 * time.Second},
		{0.25, 15 * time.Second},
	} {
		r := &Reporter{randFn: fixedRand(tc.rnd)}
		if got := r.startupDelay(); got != tc.want {
			t.Errorf("startupDelay(rnd=%v) = %s, want %s", tc.rnd, got, tc.want)
		}
	}
	r := &Reporter{randFn: fixedRand(0.9999999)}
	if got := r.startupDelay(); got < 0 || got >= startupDelayMax {
		t.Errorf("startupDelay must stay within [0,%s), got %s", startupDelayMax, got)
	}
	// 真随机源：多次取样都在 [0, 60s) 内，并且不是常数。
	real := &Reporter{}
	seen := map[time.Duration]struct{}{}
	for i := 0; i < 200; i++ {
		d := real.startupDelay()
		if d < 0 || d >= startupDelayMax {
			t.Fatalf("real startupDelay out of range: %s", d)
		}
		seen[d] = struct{}{}
	}
	if len(seen) < 100 {
		t.Errorf("startup delay looks constant (%d distinct values in 200 samples)", len(seen))
	}
}

type fakeClock struct{ now time.Time }

func (c *fakeClock) Now() time.Time          { return c.now }
func (c *fakeClock) Advance(d time.Duration) { c.now = c.now.Add(d) }

func newBackoffReporter(clk *fakeClock, rnd float64) *Reporter {
	r := &Reporter{nowFn: clk.Now, randFn: fixedRand(rnd)}
	r.setCadence((120 * time.Second).Milliseconds(), (15 * time.Second).Milliseconds())
	return r
}

func TestNoteFailure_SetsBusyWindowAndResets(t *testing.T) {
	clk := &fakeClock{now: time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC)}
	r := newBackoffReporter(clk, 0)
	r.lastActive.Store(true)

	if r.inBackoff() {
		t.Fatal("fresh reporter must not be in backoff")
	}

	// 503 + Retry-After: 300 → 等待 ≥ 300s（大于 2 分钟基线）。
	r.noteFailure(&apiclient.HTTPError{StatusCode: 503, RetryAfter: 300 * time.Second})
	if r.lastActive.Load() {
		t.Error("a failure must drop the cadence back to idle")
	}
	if got := r.backoffRemaining(); got != 300*time.Second {
		t.Errorf("remaining = %s, want 300s (Retry-After is a floor)", got)
	}
	clk.Advance(299 * time.Second)
	if !r.inBackoff() {
		t.Error("still inside the window at +299s")
	}
	clk.Advance(2 * time.Second)
	if r.inBackoff() {
		t.Error("window must end at +301s")
	}

	// 无 Retry-After 的第 2 次失败：指数部分只有 30s，但至少要等一个空闲基线（120s），
	// 否则 watcher 会在基线间隔内提前补 tick（1.3.3 遗留的"503 后 5 秒又触发"）。
	r.noteFailure(&apiclient.HTTPError{StatusCode: 500})
	if got := r.backoffRemaining(); got != 120*time.Second {
		t.Errorf("remaining after 2nd failure = %s, want the 120s idle baseline", got)
	}
	if r.failStreak != 2 {
		t.Errorf("failStreak = %d, want 2", r.failStreak)
	}

	// 第 5 次：240s 的指数值超过基线，以指数为准。
	r.failStreak = 4
	r.noteFailure(&apiclient.HTTPError{StatusCode: 502})
	if got := r.backoffRemaining(); got != 240*time.Second {
		t.Errorf("remaining after 5th failure = %s, want 240s", got)
	}

	r.noteSuccess()
	if r.failStreak != 0 || r.inBackoff() {
		t.Errorf("success must clear the streak and the busy window (streak=%d remaining=%s)", r.failStreak, r.backoffRemaining())
	}
}

func TestNextTickDelay(t *testing.T) {
	clk := &fakeClock{now: time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC)}

	t.Run("jitter is applied around the adaptive interval", func(t *testing.T) {
		for _, tc := range []struct {
			rnd  float64
			want time.Duration
		}{{0, 12 * time.Second}, {0.5, 15 * time.Second}, {1, 18 * time.Second}} {
			r := newBackoffReporter(clk, tc.rnd)
			r.lastActive.Store(true)
			if got := r.nextTickDelay(); got != tc.want {
				t.Errorf("active, rnd=%v: %s, want %s", tc.rnd, got, tc.want)
			}
		}
		r := newBackoffReporter(clk, 0.5)
		if got := r.nextTickDelay(); got != 120*time.Second {
			t.Errorf("idle: %s, want 120s", got)
		}
	})

	t.Run("never earlier than the backoff deadline", func(t *testing.T) {
		r := newBackoffReporter(clk, 0)
		r.failStreak = 6
		r.noteFailure(&apiclient.HTTPError{StatusCode: 503}) // streak 7 → ≥300s
		if got, rem := r.nextTickDelay(), r.backoffRemaining(); got < rem {
			t.Errorf("next tick in %s but busy for another %s", got, rem)
		}
		if got := r.nextTickDelay(); got < 300*time.Second {
			t.Errorf("delay %s should reflect the >=300s backoff", got)
		}
	})

	t.Run("backlog drain cadence is used when idle, jittered, and still below baseline", func(t *testing.T) {
		r := newBackoffReporter(clk, 0.5)
		r.backlog.Store(true)
		if got := r.nextInterval(); got != backlogDrainInterval {
			t.Errorf("nextInterval with backlog = %s, want %s", got, backlogDrainInterval)
		}
		if got := r.nextTickDelay(); got != backlogDrainInterval {
			t.Errorf("nextTickDelay with backlog = %s", got)
		}
		// 基线本身比回填加速还短时不放慢。
		r.setCadence((20 * time.Second).Milliseconds(), (5 * time.Second).Milliseconds())
		if got := r.nextInterval(); got != 20*time.Second {
			t.Errorf("nextInterval = %s, want the shorter baseline", got)
		}
	})
}

// 退避期内任何触发路径都不得发起上报（含"刚好在节奏窗口内"的触发）。
func TestTriggerAllowed(t *testing.T) {
	clk := &fakeClock{now: time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC)}
	cases := []struct {
		name      string
		armed     bool
		active    bool
		backlog   bool
		busy      bool
		sinceLast time.Duration
		want      bool
	}{
		{"idle, armed, long since last tick", true, false, false, false, time.Minute, true},
		{"not armed (already fired this period)", false, false, false, false, time.Minute, false},
		{"fast cadence (active)", true, true, false, false, time.Minute, false},
		{"fast cadence (backlog drain)", true, false, true, false, time.Minute, false},
		{"in failure backoff", true, false, false, true, time.Minute, false},
		{"too soon after the last tick", true, false, false, false, 2 * time.Second, false},
		{"exactly at the minimum spacing", true, false, false, false, minActiveReportInterval, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := newBackoffReporter(clk, 0.5)
			r.lastActive.Store(tc.active)
			r.backlog.Store(tc.backlog)
			if tc.busy {
				r.noteFailure(&apiclient.HTTPError{StatusCode: 503})
				r.lastActive.Store(tc.active) // noteFailure 会把 active 清掉，这里只想单独验证 busy 分支
			}
			if got := r.triggerAllowed(tc.armed, tc.sinceLast); got != tc.want {
				t.Errorf("triggerAllowed = %v, want %v", got, tc.want)
			}
		})
	}
	// 退避结束后恢复。
	r := newBackoffReporter(clk, 0.5)
	r.noteFailure(&apiclient.HTTPError{StatusCode: 503})
	clk.Advance(10 * time.Minute)
	if !r.triggerAllowed(true, time.Minute) {
		t.Error("triggers must be allowed again once the busy window has passed")
	}
}

// watcher 在退避期内跳过 mtime 扫描（基线不动、不触发），退避结束后先重建基线再恢复触发——
// 与"快节奏时 watcher 跳过扫描并 re-baseline"同一套机制。
func TestWatcher_SkipsScanDuringBackoff(t *testing.T) {
	root := t.TempDir()
	f := filepath.Join(root, "s.jsonl")
	if err := os.WriteFile(f, []byte("a"), 0o644); err != nil {
		t.Fatal(err)
	}
	t0 := time.Unix(1_000_000, 0)
	for _, p := range []string{root, f} {
		if err := os.Chtimes(p, t0, t0); err != nil {
			t.Fatal(err)
		}
	}

	clk := &fakeClock{now: time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC)}
	r := newBackoffReporter(clk, 0.5)
	var armed atomic.Bool
	armed.Store(true)
	paused := func() bool { return r.fastCadence() || !armed.Load() || r.inBackoff() }

	ch := make(chan struct{}, 1)
	w := &activityWatcher{hints: []string{root}, triggerCh: ch, last: make(map[string]int64), interval: watchPollInterval, paused: paused}
	w.scanAll() // run() 的首轮基线

	touch := func(at time.Time) {
		t.Helper()
		if err := os.Chtimes(f, at, at); err != nil {
			t.Fatal(err)
		}
	}
	signalled := func() bool {
		select {
		case <-ch:
			return true
		default:
			return false
		}
	}

	r.noteFailure(&apiclient.HTTPError{StatusCode: 503})
	touch(t0.Add(time.Hour))
	w.poll()
	if signalled() {
		t.Fatal("watcher must not trigger a report during the busy window")
	}
	if w.last[root] != t0.UnixNano() {
		t.Fatalf("baseline moved to %d during backoff: the mtime walk must be skipped entirely", w.last[root])
	}

	clk.Advance(time.Hour) // 退避结束
	w.poll()               // 恢复后的第一轮：只重建基线，不触发
	if signalled() {
		t.Fatal("first poll after the backoff only re-baselines")
	}
	touch(t0.Add(2 * time.Hour))
	w.poll()
	if !signalled() {
		t.Fatal("a fresh change after the backoff must trigger again")
	}
}
