//go:build cursorlive

package cursor

import (
	"strings"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

func TestLiveSessionB05Timeline(t *testing.T) {
	p := New("/Users/gz/projects/am")
	p.SetLookback(monitor.BootstrapLookback)
	snap, err := p.Snapshot(nil)
	if err != nil {
		t.Fatal(err)
	}
	const want = "b05ccacb-68c7-4166-8496-2de9a9bb47b3"
	for _, s := range snap.Sessions {
		if s.SessionID != want {
			continue
		}
		subagent, childPrefix := 0, 0
		for _, m := range s.RecentMessages {
			if m.Role == "subagent" {
				subagent++
			}
			composer, _, ok := strings.Cut(m.ExternalMessageID, ":")
			if ok && composer != want {
				childPrefix++
			}
		}
		start := s.StartedAt.Time()
		t.Logf("msgs=%d subagent=%d child_prefix=%d started=%v",
			len(s.RecentMessages), subagent, childPrefix, start.Format(time.RFC3339))
		if start.Year() == 2026 && start.Month() == time.May && start.Day() == 21 && start.Hour() >= 10 && start.Hour() <= 11 {
			t.Errorf("started_at still looks like rewritten bubble time: %v", start)
		}
		if subagent == 0 {
			t.Errorf("expected subagent messages in merged timeline")
		}
		return
	}
	t.Fatalf("session %s not found", want)
}
