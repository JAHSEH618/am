package codex

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 子 thread 归并不得改写缓存里的父会话：父会话文件不再增长（idle）时，每个 tick 都命中缓存并重新归并，
// 修复前每个 tick 都把子 thread 的 token 再累加一遍（token 随 tick 数线性膨胀，2026-09 事故的机制）。
// input / output 与 cache_read 都必须跨 tick 稳定；ActivityDeltas 不随 tick 增长。
func TestProvider_Codex_SubagentMergeStableAcrossTicks(t *testing.T) {
	root := t.TempDir()
	t.Setenv("AM_CODEX_DIR", root)
	subDir := filepath.Join(root, "sessions", "2026", "05", "07")
	if err := os.MkdirAll(subDir, 0o755); err != nil {
		t.Fatal(err)
	}
	parentBody := `{"timestamp":"2026-05-07T10:00:00Z","type":"session_meta","payload":{"id":"parent-1","cwd":"/tmp/proj","cli_version":"0.5.0"}}
{"timestamp":"2026-05-07T10:00:02Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":1000000,"cached_input_tokens":800000,"output_tokens":5000,"reasoning_output_tokens":0}}}}
`
	childBody := `{"timestamp":"2026-05-07T10:00:01Z","type":"session_meta","payload":{"id":"child-1","cwd":"/tmp/proj","cli_version":"0.5.0","source":{"subagent":{"thread_spawn":{"parent_thread_id":"parent-1"}}}}}
{"timestamp":"2026-05-07T10:00:03Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":2000000,"cached_input_tokens":1500000,"output_tokens":10000,"reasoning_output_tokens":0}}}}
`
	if err := os.WriteFile(filepath.Join(subDir, "rollout-parent.jsonl"), []byte(parentBody), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(subDir, "rollout-child.jsonl"), []byte(childBody), 0o644); err != nil {
		t.Fatal(err)
	}

	p := New("/tmp/proj")
	p.SetLookback(10 * 365 * 24 * time.Hour)
	const wantIn, wantOut = int64(3_000_000), int64(15_000)
	deltas := -1
	for tick := 1; tick <= 50; tick++ {
		snap, err := p.Snapshot(t.Context())
		if err != nil {
			t.Fatalf("tick %d: %v", tick, err)
		}
		if len(snap.Sessions) != 1 {
			t.Fatalf("tick %d: want 1 merged session, got %d", tick, len(snap.Sessions))
		}
		s := snap.Sessions[0]
		if s.InputTokens != wantIn || s.OutputTokens != wantOut {
			t.Fatalf("tick %d drifted: input=%d output=%d want %d/%d", tick, s.InputTokens, s.OutputTokens, wantIn, wantOut)
		}
		if s.CacheReadTokens != 2_300_000 { // 父 800k + 子 1.5M，恰好一份
			t.Fatalf("tick %d: cache_read=%d want 2300000", tick, s.CacheReadTokens)
		}
		if deltas == -1 {
			deltas = len(s.ActivityDeltas)
		} else if len(s.ActivityDeltas) != deltas {
			t.Fatalf("tick %d: activity_deltas grew %d→%d", tick, deltas, len(s.ActivityDeltas))
		}
	}
}
