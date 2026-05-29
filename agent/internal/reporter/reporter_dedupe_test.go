package reporter

import (
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

func TestCountSnapshotMessages(t *testing.T) {
	monitors := []monitor.Snapshot{
		{
			Sessions: []monitor.Session{
				{RecentMessages: make([]monitor.Message, 3)},
				{RecentMessages: make([]monitor.Message, 2)},
			},
		},
	}
	if got := countSnapshotMessages(monitors); got != 5 {
		t.Fatalf("count=%d want 5", got)
	}
}

func TestApplyDedupeReplayFastForwardDisabled(t *testing.T) {
	cursors, err := LoadCursorStore()
	if err != nil {
		t.Fatal(err)
	}
	r := &Reporter{
		cursors:            cursors,
		dedupeReplayStreak: dedupeReplayFastForwardAfter,
		uncappedTail: map[string]MsgCursor{
			"cursor:s1": {LastMsgID: "cursor:s1:m999", LastMsgTime: time.Unix(1000, 0)},
		},
	}

	if got := r.applyDedupeReplayFastForward(1200, 200, 0); got != 0 {
		t.Fatalf("fast-forward disabled, got=%d want 0", got)
	}
	cur, ok := r.cursors.Get("cursor", "s1")
	if ok && cur.LastMsgID == "cursor:s1:m999" {
		t.Fatalf("cursor must not advance via fast-forward")
	}
}

func TestApplyMsgCursorsRecordsUncappedTail(t *testing.T) {
	r := &Reporter{
		cursors: &MsgCursorStore{cursors: make(map[string]MsgCursor)},
	}
	msgs := make([]monitor.Message, 0, 250)
	for i := 0; i < 250; i++ {
		msgs = append(msgs, monitor.Message{
			ExternalMessageID: "m" + string(rune('a'+i%26)),
			Timestamp:         monitor.LocalTime(time.Unix(int64(i+1), 0)),
		})
	}
	monitors := []monitor.Snapshot{{
		Type: "cursor",
		Sessions: []monitor.Session{{
			SessionID:      "s1",
			RecentMessages: msgs,
		}},
	}}
	pending := r.applyMsgCursors(monitors)
	if pending != 50 {
		t.Fatalf("pending=%d want 50", pending)
	}
	if len(r.uncappedTail) != 1 {
		t.Fatalf("uncappedTail=%d want 1", len(r.uncappedTail))
	}
	if len(monitors[0].Sessions[0].RecentMessages) != MaxMessagesPerSession {
		t.Fatalf("sent cap=%d want %d", len(monitors[0].Sessions[0].RecentMessages), MaxMessagesPerSession)
	}
}
