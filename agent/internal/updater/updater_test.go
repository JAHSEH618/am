package updater

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestManifest_RolloutPercentParsing(t *testing.T) {
	cases := []struct {
		name string
		json string
		want int
	}{
		{"missing field = 100 (old manifests stay compatible)", `{"version":"1.0.0","artifacts":{}}`, 100},
		{"explicit 100", `{"version":"1.0.0","rollout_percent":100}`, 100},
		{"explicit 0", `{"version":"1.0.0","rollout_percent":0}`, 0},
		{"10", `{"version":"1.0.0","rollout_percent":10}`, 10},
		{"float written by jq / hand", `{"version":"1.0.0","rollout_percent":50.0}`, 50},
		{"fractional is floored", `{"version":"1.0.0","rollout_percent":33.9}`, 33},
		{"above 100 clamps", `{"version":"1.0.0","rollout_percent":250}`, 100},
		{"negative clamps to 0", `{"version":"1.0.0","rollout_percent":-5}`, 0},
		{"null is treated as missing", `{"version":"1.0.0","rollout_percent":null}`, 100},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var m Manifest
			if err := json.Unmarshal([]byte(tc.json), &m); err != nil {
				t.Fatalf("manifest must parse: %v", err)
			}
			if got := m.EffectiveRolloutPercent(); got != tc.want {
				t.Errorf("EffectiveRolloutPercent = %d, want %d", got, tc.want)
			}
		})
	}
	var nilM *Manifest
	if nilM.EffectiveRolloutPercent() != 100 {
		t.Error("nil manifest = 100")
	}
}

// build-dist.sh 生成的真实形态（含 rollout_percent 与 artifacts）能被客户端解析。
func TestFetchManifest_WithRollout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/install/manifest.json" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte(`{
  "version": "1.3.4",
  "generated_at": "2026-09-29T02:10:31Z",
  "rollout_percent": 10,
  "artifacts": {
    "linux-amd64": {"filename": "aiwatchd-linux-amd64", "sha256": "abc", "size": 12}
  }
}`))
	}))
	defer srv.Close()

	m, err := fetchManifest(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if m.Version != "1.3.4" || m.EffectiveRolloutPercent() != 10 || m.Artifacts["linux-amd64"].SHA256 != "abc" {
		t.Errorf("manifest = %+v", m)
	}
}

func TestRolloutBucket_StableAndWellDistributed(t *testing.T) {
	if RolloutBucket("agent-123") != RolloutBucket("agent-123") {
		t.Fatal("bucket must be deterministic")
	}
	// 已知向量：sha256("agent-1") 前 4 字节 0x6ff3b3bd = 1878242237，mod 100 = 37。
	// 防止有人无意改了哈希方案导致全网每台机器的灰度分组被重新洗牌。
	if got := RolloutBucket("agent-1"); got != 37 {
		t.Errorf("RolloutBucket(agent-1) = %d, want 37 (changing the hashing scheme reshuffles every agent's cohort)", got)
	}
	if got := RolloutBucket("agent-123"); got != 35 {
		t.Errorf("RolloutBucket(agent-123) = %d, want 35", got)
	}

	const n = 20000
	counts := make([]int, 100)
	for i := 0; i < n; i++ {
		b := RolloutBucket(fmt.Sprintf("00000000-0000-4000-8000-%012d", i))
		if b < 0 || b >= 100 {
			t.Fatalf("bucket %d out of [0,100)", b)
		}
		counts[b]++
	}
	for b, c := range counts {
		// 期望 200/桶；允许很宽的统计波动，只防"严重不均匀"（如只用了低位）。
		if c < 120 || c > 290 {
			t.Errorf("bucket %d has %d/%d agents (expected ~200)", b, c, n)
		}
	}
}

func TestInRollout(t *testing.T) {
	id := "agent-1" // bucket 37
	cases := []struct {
		name    string
		agentID string
		percent int
		want    bool
	}{
		{"100% always upgrades", id, 100, true},
		{"above 100 always upgrades", id, 500, true},
		{"0% never", id, 0, false},
		{"negative never", id, -1, false},
		{"bucket 37 is outside 37%", id, 37, false},
		{"bucket 37 is inside 38%", id, 38, true},
		{"bucket 37 outside 10%", id, 10, false},
		{"unregistered agent (no id) is held back during a partial rollout", "", 50, false},
		{"whitespace id is treated as no id", "   ", 50, false},
		{"unregistered agent still upgrades on a full rollout", "", 100, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := InRollout(tc.agentID, tc.percent); got != tc.want {
				t.Errorf("InRollout(%q, %d) = %v, want %v", tc.agentID, tc.percent, got, tc.want)
			}
		})
	}
}

// 比例只增不减时，放行集合单调扩大：10% → 50% → 100% 不会让已升级的机器"掉出去"。
func TestInRollout_MonotonicAndProportional(t *testing.T) {
	ids := make([]string, 5000)
	for i := range ids {
		ids[i] = fmt.Sprintf("agent-%d", i)
	}
	prev := map[string]bool{}
	for _, pct := range []int{0, 1, 10, 25, 50, 75, 99, 100} {
		cur := map[string]bool{}
		for _, id := range ids {
			if InRollout(id, pct) {
				cur[id] = true
			}
		}
		for id := range prev {
			if !cur[id] {
				t.Fatalf("%s was in the rollout at a smaller percent but dropped out at %d%%", id, pct)
			}
		}
		if pct > 0 && pct < 100 {
			got := float64(len(cur)) / float64(len(ids)) * 100
			if got < float64(pct)-3 || got > float64(pct)+3 {
				t.Errorf("%d%% rollout admitted %.1f%% of agents", pct, got)
			}
		}
		prev = cur
	}
	if len(prev) != len(ids) {
		t.Errorf("100%% must admit everyone, got %d/%d", len(prev), len(ids))
	}
}

func TestUpgradeDecision(t *testing.T) {
	pct := func(p float64) *float64 { return &p }
	inside, outside := "agent-1", "agent-1" // 桶号 37
	cases := []struct {
		name    string
		current string
		m       Manifest
		agentID string
		want    bool
	}{
		{"same version: nothing to do", "1.3.3", Manifest{Version: "1.3.3"}, inside, false},
		{"new version, no rollout field: everyone", "1.3.3", Manifest{Version: "1.3.4"}, inside, true},
		{"new version, 100%", "1.3.3", Manifest{Version: "1.3.4", RolloutPercent: pct(100)}, outside, true},
		{"new version, in cohort", "1.3.3", Manifest{Version: "1.3.4", RolloutPercent: pct(38)}, inside, true},
		{"new version, out of cohort", "1.3.3", Manifest{Version: "1.3.4", RolloutPercent: pct(10)}, outside, false},
		{"paused rollout (0%)", "1.3.3", Manifest{Version: "1.3.4", RolloutPercent: pct(0)}, inside, false},
		{"manifest without version", "1.3.3", Manifest{}, inside, false},
		{"version compare ignores surrounding whitespace", " 1.3.4 ", Manifest{Version: "1.3.4 "}, inside, false},
		{"unregistered agent held back at 50%", "1.3.3", Manifest{Version: "1.3.4", RolloutPercent: pct(50)}, "", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, reason := upgradeDecision(tc.current, &tc.m, tc.agentID)
			if got != tc.want {
				t.Errorf("upgradeDecision = %v (%s), want %v", got, reason, tc.want)
			}
			if !got && reason == "" {
				t.Error("a negative decision must carry a reason for the log")
			}
		})
	}
}

func TestNextAutoUpdateDelay_JitterBounds(t *testing.T) {
	lo, hi := NextAutoUpdateDelay(0), NextAutoUpdateDelay(0.999999999)
	if lo != 45*time.Minute {
		t.Errorf("lower bound = %s, want 45m (−25%%)", lo)
	}
	if hi <= 74*time.Minute || hi > 75*time.Minute {
		t.Errorf("upper bound = %s, want just under 75m (+25%%)", hi)
	}
	if mid := NextAutoUpdateDelay(0.5); mid != time.Hour {
		t.Errorf("midpoint = %s, want 1h", mid)
	}
	prev := time.Duration(0)
	for _, r := range []float64{0, 0.1, 0.4, 0.7, 0.99} {
		d := NextAutoUpdateDelay(r)
		if d < prev {
			t.Errorf("delay must be monotonic in rnd: %s < %s", d, prev)
		}
		prev = d
	}
}
