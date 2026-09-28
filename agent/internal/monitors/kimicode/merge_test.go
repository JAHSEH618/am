package kimicode

import (
	"path/filepath"
	"testing"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// mergeBySession 的输入是 FileCache 跨 tick 复用的指针：归并不得改写它们，否则每个 tick
// 都把 subagent 的 token / 增量再累加一遍。
func TestMergeBySession_DoesNotMutateCachedFiles(t *testing.T) {
	sub := &parsedSession{SessionID: "s1", AgentName: "sub", InputTokens: 10,
		ActivityDeltas: []monitor.ActivityDelta{{SourceRef: "sub-1"}}}
	main := &parsedSession{SessionID: "s1", AgentName: "main", InputTokens: 100,
		ActivityDeltas: []monitor.ActivityDelta{{SourceRef: "main-1"}}}

	for tick := 0; tick < 3; tick++ {
		out := mergeBySession([]*parsedSession{sub, main})
		if len(out) != 1 {
			t.Fatalf("tick %d: want 1 merged session, got %d", tick, len(out))
		}
		if out[0].InputTokens != 110 || len(out[0].ActivityDeltas) != 2 || out[0].AgentName != "main" {
			t.Fatalf("tick %d: merged = tokens %d deltas %d agent %q", tick,
				out[0].InputTokens, len(out[0].ActivityDeltas), out[0].AgentName)
		}
	}
	if main.InputTokens != 100 || len(main.ActivityDeltas) != 1 || sub.InputTokens != 10 || len(sub.ActivityDeltas) != 1 {
		t.Fatalf("cached inputs were mutated: main=%d/%d sub=%d/%d",
			main.InputTokens, len(main.ActivityDeltas), sub.InputTokens, len(sub.ActivityDeltas))
	}
}

func TestWireGroupKey(t *testing.T) {
	base := filepath.Join("/h", ".kimi-code", "sessions", "wd", "sid")
	for _, agent := range []string{"main", "sub-1"} {
		if got := wireGroupKey(filepath.Join(base, "agents", agent, "wire.jsonl")); got != base {
			t.Errorf("wireGroupKey(%s) = %q want %q", agent, got, base)
		}
	}
	legacy := filepath.Join("/h", ".kimi", "sessions", "sid", "wire.jsonl")
	if got := wireGroupKey(legacy); got != filepath.Dir(legacy) {
		t.Errorf("legacy wireGroupKey = %q", got)
	}
}
