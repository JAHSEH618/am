// Cursor 时间锚点与 Task 归并单元测试。
//
// gz
package cursor

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

func TestParseComposerCreatedAtMs(t *testing.T) {
	raw := json.RawMessage(`1779268560890`)
	got := ParseComposerCreatedAtMs(raw)
	want := time.UnixMilli(1779268560890).In(time.Local)
	if !got.Equal(want) {
		t.Fatalf("got=%v want=%v", got, want)
	}
}

func TestResolveHeaderMessageTime_RewrittenBubbleUsesAnchor(t *testing.T) {
	anchor := time.Date(2026, 5, 20, 17, 16, 0, 0, time.Local)
	rewritten := time.Date(2026, 5, 21, 10, 42, 19, 0, time.Local)
	var last time.Time
	got := ResolveHeaderMessageTime(anchor, 1, rewritten, &last)
	want := anchor
	if !got.Equal(want) {
		t.Fatalf("first header got=%v want=%v", got, want)
	}
	got2 := ResolveHeaderMessageTime(anchor, 2, rewritten, &last)
	if !got2.Equal(anchor.Add(time.Second)) {
		t.Fatalf("second header got=%v", got2)
	}
}

func TestSpliceSubagentMessagesAfterAgentTool(t *testing.T) {
	parent := &parsedSession{
		SessionID:       "parent-1",
		StartedAt:       time.Date(2026, 5, 20, 17, 16, 0, 0, time.Local),
		AgentToolOrders: []int{3},
		SubagentComposerIDs: []string{"child-a"},
		RecentMessages: []monitor.Message{
			{ExternalMessageID: "parent-1:u1", Role: "user", ConversationOrder: 1, Timestamp: monitor.LocalTime(time.Date(2026, 5, 20, 17, 16, 0, 0, time.Local))},
			{ExternalMessageID: "parent-1:a1", Role: "assistant", ConversationOrder: 2, Timestamp: monitor.LocalTime(time.Date(2026, 5, 20, 17, 16, 1, 0, time.Local))},
			{ExternalMessageID: "parent-1:agent", Role: "tool", ToolName: "Agent", ConversationOrder: 3, Timestamp: monitor.LocalTime(time.Date(2026, 5, 20, 17, 16, 2, 0, time.Local))},
			{ExternalMessageID: "parent-1:a2", Role: "assistant", ConversationOrder: 4, Timestamp: monitor.LocalTime(time.Date(2026, 5, 20, 17, 20, 0, 0, time.Local))},
		},
	}
	child := &parsedSession{
		SessionID:        "child-a",
		ParentComposerID: "parent-1",
		RecentMessages: []monitor.Message{
			{ExternalMessageID: "child-a:task", Role: "subagent", ConversationOrder: 1, Timestamp: monitor.LocalTime(time.Date(2026, 5, 20, 17, 16, 3, 0, time.Local))},
			{ExternalMessageID: "child-a:tool", Role: "tool", ToolName: "Read", ConversationOrder: 2, Timestamp: monitor.LocalTime(time.Date(2026, 5, 20, 17, 16, 4, 0, time.Local))},
		},
	}
	spliceSubagentMessages(parent, []*parsedSession{child})
	if len(parent.RecentMessages) != 6 {
		t.Fatalf("msgs=%d want 6", len(parent.RecentMessages))
	}
	if parent.RecentMessages[3].Role != "subagent" {
		t.Fatalf("msg[3] role=%q want subagent", parent.RecentMessages[3].Role)
	}
	if parent.RecentMessages[3].ConversationOrder != 4 {
		t.Fatalf("msg[3] order=%d want 4", parent.RecentMessages[3].ConversationOrder)
	}
	if !parent.RecentMessages[0].Timestamp.Time().Equal(time.Date(2026, 5, 20, 17, 16, 0, 0, time.Local)) {
		t.Fatalf("started msg time=%v", parent.RecentMessages[0].Timestamp.Time())
	}
}
