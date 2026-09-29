package cursor

import (
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// parent 是 parsedSessionCache 跨 tick 复用的同一个指针：对同一批缓存对象反复归并，父会话的 token / 消息 / 增量
// 与缓存对象本身都必须保持不变（修复前每次归并都原地把子会话再加一遍，2026-09 事故的机制）。
func TestMergeSubagentSessions_DoesNotMutateCachedParentAcrossTicks(t *testing.T) {
	t0 := time.Date(2026, 5, 20, 10, 0, 0, 0, time.UTC)
	parent := &parsedSession{
		SessionID: "parent-1", UserMessages: 1, InputTokens: 100, OutputTokens: 200,
		StartedAt: t0, LastActivity: t0.Add(5 * time.Minute),
		RecentMessages: []monitor.Message{{ExternalMessageID: "parent-1:b1", Role: "user", Timestamp: monitor.LocalTime(t0)}},
		ActivityDeltas: []monitor.ActivityDelta{{EventTime: monitor.LocalTime(t0), InputTokensDelta: 100, SourceRef: "parent-1:b1"}},
	}
	child := &parsedSession{
		SessionID: "child-a", ParentComposerID: "parent-1", UserMessages: 1, InputTokens: 50, OutputTokens: 80,
		StartedAt: t0.Add(time.Minute), LastActivity: t0.Add(6 * time.Minute),
		RecentMessages: []monitor.Message{{ExternalMessageID: "child-a:b1", Role: "user", Timestamp: monitor.LocalTime(t0.Add(time.Minute))}},
		ActivityDeltas: []monitor.ActivityDelta{{EventTime: monitor.LocalTime(t0.Add(time.Minute)), InputTokensDelta: 50, SourceRef: "child-a:b1"}},
	}

	for tick := 1; tick <= 30; tick++ {
		out, _ := mergeSubagentSessions([]*parsedSession{parent, child})
		if len(out) != 1 {
			t.Fatalf("tick %d: sessions=%d want 1", tick, len(out))
		}
		got := out[0]
		if got.InputTokens != 150 || got.OutputTokens != 280 {
			t.Fatalf("tick %d drifted: input=%d output=%d want 150/280", tick, got.InputTokens, got.OutputTokens)
		}
		if len(got.ActivityDeltas) != 2 || len(got.RecentMessages) != 2 {
			t.Fatalf("tick %d: deltas=%d messages=%d want 2/2", tick, len(got.ActivityDeltas), len(got.RecentMessages))
		}
		if got == parent {
			t.Fatalf("tick %d: merge must return a clone, not the cached parent pointer", tick)
		}
		if parent.InputTokens != 100 || parent.OutputTokens != 200 ||
			len(parent.ActivityDeltas) != 1 || len(parent.RecentMessages) != 1 {
			t.Fatalf("tick %d: cached parent was mutated: %+v", tick, parent)
		}
	}
}
