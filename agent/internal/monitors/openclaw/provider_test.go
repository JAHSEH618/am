package openclaw

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 子 session 归并不得改写缓存里的父会话：修复前父会话文件 idle 时每个 tick 都把子会话的 token 再累加一遍。
func TestProvider_OpenClaw_SubagentMergeStableAcrossTicks(t *testing.T) {
	root := t.TempDir()
	t.Setenv("AM_OPENCLAW_DIR", root)
	if err := os.WriteFile(filepath.Join(root, "openclaw.json"), []byte(`{}`), 0o644); err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(root, "agents", "main", "sessions")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	parentBody := `{"type":"session","version":3,"id":"parent","timestamp":"2026-05-07T10:00:00Z","cwd":"/tmp/proj"}
{"type":"message","id":"u1","timestamp":"2026-05-07T10:00:01Z","message":{"role":"user","content":[{"type":"text","text":"go"}]}}
{"type":"message","id":"a1","timestamp":"2026-05-07T10:00:02Z","message":{"role":"assistant","model":"gpt-5.4","content":[{"type":"text","text":"ok"}],"usage":{"input":1000,"output":10,"cacheRead":500,"cacheWrite":0}}}
`
	childBody := `{"type":"session","version":3,"id":"child","parentId":"parent","timestamp":"2026-05-07T10:00:03Z","cwd":"/tmp/proj"}
{"type":"message","id":"cu1","timestamp":"2026-05-07T10:00:04Z","message":{"role":"user","content":[{"type":"text","text":"sub"}]}}
{"type":"message","id":"ca1","timestamp":"2026-05-07T10:00:05Z","message":{"role":"assistant","content":[{"type":"text","text":"done"}],"usage":{"input":2000,"output":20,"cacheRead":700,"cacheWrite":0}}}
`
	if err := os.WriteFile(filepath.Join(dir, "parent.jsonl"), []byte(parentBody), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "child.jsonl"), []byte(childBody), 0o644); err != nil {
		t.Fatal(err)
	}

	p := New("/tmp/proj")
	p.SetLookback(10 * 365 * 24 * time.Hour)
	for tick := 1; tick <= 50; tick++ {
		snap, err := p.Snapshot(t.Context())
		if err != nil {
			t.Fatalf("tick %d: %v", tick, err)
		}
		if len(snap.Sessions) != 1 {
			t.Fatalf("tick %d: want 1 merged session, got %d", tick, len(snap.Sessions))
		}
		s := snap.Sessions[0]
		if s.InputTokens != 3000 || s.OutputTokens != 30 || s.CacheReadTokens != 1200 {
			t.Fatalf("tick %d drifted: input=%d output=%d cacheRead=%d want 3000/30/1200",
				tick, s.InputTokens, s.OutputTokens, s.CacheReadTokens)
		}
	}
}
