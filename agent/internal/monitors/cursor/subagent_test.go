// Cursor subagent composer 归并单元测试。
//
// gz
package cursor

import (
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

func TestMergeSubagentSessions_CollapsesChildrenIntoParent(t *testing.T) {
	parent := &parsedSession{
		SessionID:         "parent-1",
		UserMessages:      1,
		AssistantMessages: 2,
		InputTokens:       100,
		OutputTokens:      200,
		StartedAt:         time.Date(2026, 5, 20, 10, 0, 0, 0, time.UTC),
		LastActivity:      time.Date(2026, 5, 20, 10, 5, 0, 0, time.UTC),
		RecentMessages: []monitor.Message{
			{ExternalMessageID: "parent-1:b1", Role: "user", Timestamp: monitor.LocalTime(time.Date(2026, 5, 20, 10, 0, 0, 0, time.UTC))},
		},
	}
	childA := &parsedSession{
		SessionID:         "child-a",
		ParentComposerID:  "parent-1",
		UserMessages:      1,
		AssistantMessages: 3,
		InputTokens:       50,
		OutputTokens:      80,
		StartedAt:         time.Date(2026, 5, 20, 10, 1, 0, 0, time.UTC),
		LastActivity:      time.Date(2026, 5, 20, 10, 6, 0, 0, time.UTC),
		CurrentTool:       "Read",
		RecentMessages: []monitor.Message{
			{ExternalMessageID: "child-a:b1", Role: "user", Timestamp: monitor.LocalTime(time.Date(2026, 5, 20, 10, 1, 0, 0, time.UTC))},
			{ExternalMessageID: "child-a:b2", Role: "tool", Timestamp: monitor.LocalTime(time.Date(2026, 5, 20, 10, 2, 0, 0, time.UTC))},
		},
	}
	childB := &parsedSession{
		SessionID:         "child-b",
		ParentComposerID:  "parent-1",
		UserMessages:      1,
		AssistantMessages: 1,
		InputTokens:       30,
		OutputTokens:      40,
		StartedAt:         time.Date(2026, 5, 20, 10, 3, 0, 0, time.UTC),
		LastActivity:      time.Date(2026, 5, 20, 10, 4, 0, 0, time.UTC),
		RecentMessages: []monitor.Message{
			{ExternalMessageID: "child-b:b1", Role: "user", Timestamp: monitor.LocalTime(time.Date(2026, 5, 20, 10, 3, 0, 0, time.UTC))},
		},
	}

	out, suppressed := mergeSubagentSessions([]*parsedSession{parent, childA, childB})
	if len(out) != 1 {
		t.Fatalf("sessions=%d want 1", len(out))
	}
	if len(suppressed) != 2 {
		t.Fatalf("suppressed=%d want 2", len(suppressed))
	}
	got := out[0]
	if got.SessionID != "parent-1" {
		t.Fatalf("session_id=%q want parent-1", got.SessionID)
	}
	if got.UserMessages != 3 || got.AssistantMessages != 0 {
		t.Fatalf("messages user=%d assistant=%d want 3/0 after recount", got.UserMessages, got.AssistantMessages)
	}
	if len(got.RecentMessages) != 4 {
		t.Fatalf("recent_messages=%d want 4", len(got.RecentMessages))
	}
}

func TestEnrichSubagentLinks_UsesParentComposerIdsList(t *testing.T) {
	parent := &parsedSession{
		SessionID:           "parent-1",
		SubagentComposerIDs: []string{"child-a"},
	}
	child := &parsedSession{SessionID: "child-a"}

	merged, suppressed := mergeSubagentSessions([]*parsedSession{parent, child})
	if len(merged) != 1 || merged[0].SessionID != "parent-1" {
		t.Fatalf("merged=%+v", merged)
	}
	if len(suppressed) != 1 || suppressed[0] != "child-a" {
		t.Fatalf("suppressed=%v", suppressed)
	}
}

func TestMergeSubagentSessions_SuppressesChildrenListedOnParentEvenOutsideBatch(t *testing.T) {
	parent := &parsedSession{
		SessionID:           "parent-1",
		UserMessages:        1,
		SubagentComposerIDs: []string{"child-offlookback", "child-in-batch"},
	}
	child := &parsedSession{
		SessionID:        "child-in-batch",
		ParentComposerID: "parent-1",
		UserMessages:     1,
	}

	out, suppressed := mergeSubagentSessions([]*parsedSession{parent, child})
	if len(out) != 1 || out[0].SessionID != "parent-1" {
		t.Fatalf("merged=%+v", out)
	}
	if len(suppressed) != 2 {
		t.Fatalf("suppressed=%v want 2 ids", suppressed)
	}
	want := map[string]struct{}{
		"child-in-batch":      {},
		"child-offlookback":   {},
	}
	for _, id := range suppressed {
		delete(want, id)
	}
	if len(want) != 0 {
		t.Fatalf("missing suppressed ids: %v", want)
	}
}

func TestMergeSubagentSessions_SuppressesOrphanWhenParentMissing(t *testing.T) {
	orphan := &parsedSession{
		SessionID:        "child-only",
		ParentComposerID: "missing-parent",
		UserMessages:     1,
	}

	out, suppressed := mergeSubagentSessions([]*parsedSession{orphan})
	if len(out) != 0 {
		t.Fatalf("sessions=%d want 0 standalone child", len(out))
	}
	if len(suppressed) != 1 || suppressed[0] != "child-only" {
		t.Fatalf("suppressed=%v want child-only", suppressed)
	}
}

func TestMergeSubagentSessions_PreservesStandaloneSessions(t *testing.T) {
	a := &parsedSession{SessionID: "a", UserMessages: 1}
	b := &parsedSession{SessionID: "b", UserMessages: 2}

	out, suppressed := mergeSubagentSessions([]*parsedSession{a, b})
	if len(out) != 2 {
		t.Fatalf("sessions=%d want 2", len(out))
	}
	if len(suppressed) != 0 {
		t.Fatalf("suppressed=%v", suppressed)
	}
}
