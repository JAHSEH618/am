//go:build cursorlive

package cursor

import (
	"os"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// TestLiveSnapshotMerge 用本机 state.vscdb 验证 Task 子 composer 不会单独上报。
// 运行：AM_CURSOR_DB="$HOME/Library/Application Support/Cursor/User/globalStorage/state.vscdb" \
//
//	go test -tags cursorlive ./internal/monitors/cursor/ -run TestLiveSnapshotMerge -v
func TestLiveSnapshotMerge(t *testing.T) {
	db := os.Getenv("AM_CURSOR_DB")
	if db == "" {
		db = os.Getenv("HOME") + "/Library/Application Support/Cursor/User/globalStorage/state.vscdb"
	}
	if _, err := os.Stat(db); err != nil {
		t.Skip("no cursor db")
	}
	t.Setenv("AM_CURSOR_DB", db)

	p := New("/Users/gz/projects/am")
	p.SetLookback(48 * time.Hour)
	snap, err := p.Snapshot(nil)
	if err != nil {
		t.Fatal(err)
	}

	reported := make(map[string]struct{}, len(snap.Sessions))
	for _, s := range snap.Sessions {
		reported[s.SessionID] = struct{}{}
	}
	t.Logf("reported sessions=%d suppressed=%d", len(snap.Sessions), len(snap.SuppressedSessionIDs))

	childParents := map[string]string{
		"d6d80361-18b0-4164-803d-cac78218a1b6": "b05ccacb-68c7-4166-8496-2de9a9bb47b3",
		"cac00e81-87dc-42b5-8e01-ee91e297c2bb": "b05ccacb-68c7-4166-8496-2de9a9bb47b3",
		"b8bf245f-00b2-4530-a7ab-33bbfa2f52bf": "d2c0827d-6b3e-4795-8ba9-c750665a46e6",
	}
	for child, parent := range childParents {
		if _, ok := reported[child]; ok {
			t.Errorf("child composer still reported separately: %s", child)
		}
		if _, ok := reported[parent]; !ok {
			t.Logf("parent %s not in snapshot (may be outside lookback)", parent)
		}
	}
	_ = monitor.DefaultLookback
	_ = time.Now
}
