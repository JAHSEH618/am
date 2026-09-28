package reporter

import (
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

func idleSession(id string) monitor.Session {
	return monitor.Session{
		SessionID:    id,
		Status:       "idle",
		LastActivity: monitor.LocalTime(time.Date(2026, 9, 1, 10, 0, 0, 0, time.Local)),
		InputTokens:  100,
	}
}

func snapOf(sessions ...monitor.Session) []monitor.Snapshot {
	return []monitor.Snapshot{{Type: "claude", Sessions: sessions}}
}

func TestDropUnchangedIdleSessions_FirstTickSendsAll(t *testing.T) {
	r := &Reporter{}
	mons := snapOf(idleSession("a"), idleSession("b"))
	if dropped := r.dropUnchangedIdleSessions(mons, time.Now()); dropped != 0 {
		t.Fatalf("first tick dropped %d, want 0", dropped)
	}
	if len(mons[0].Sessions) != 2 {
		t.Fatalf("want 2 sessions kept, got %d", len(mons[0].Sessions))
	}
}

func TestDropUnchangedIdleSessions_SkipsOnlyUnchangedIdle(t *testing.T) {
	r := &Reporter{}
	now := time.Now()
	r.dropUnchangedIdleSessions(snapOf(idleSession("a"), idleSession("b"), idleSession("c"), idleSession("d")), now)
	r.commitSentSessions()

	changed := idleSession("b")
	changed.InputTokens = 150
	active := idleSession("c")
	active.Status = "thinking"
	withMsg := idleSession("d")
	withMsg.RecentMessages = []monitor.Message{{ExternalMessageID: "m1", Role: "user"}}

	mons := snapOf(idleSession("a"), changed, active, withMsg)
	dropped := r.dropUnchangedIdleSessions(mons, now.Add(time.Minute))
	if dropped != 1 {
		t.Fatalf("dropped %d, want 1 (only a)", dropped)
	}
	for _, s := range mons[0].Sessions {
		if s.SessionID == "a" {
			t.Fatalf("unchanged idle session a should have been dropped")
		}
	}
	if len(mons[0].Sessions) != 3 {
		t.Fatalf("want 3 kept, got %d", len(mons[0].Sessions))
	}
}

func TestDropUnchangedIdleSessions_ResyncAfterInterval(t *testing.T) {
	r := &Reporter{}
	now := time.Now()
	r.dropUnchangedIdleSessions(snapOf(idleSession("a")), now)
	r.commitSentSessions()

	mons := snapOf(idleSession("a"))
	if dropped := r.dropUnchangedIdleSessions(mons, now.Add(unchangedResyncInterval+time.Second)); dropped != 0 {
		t.Fatalf("session past resync interval must be re-sent, dropped=%d", dropped)
	}
}

// 上报失败时暂存的指纹不提交：下个 tick 仍按上次成功的状态比对，变化过的会话不会被误判"已发过"。
func TestDropUnchangedIdleSessions_FailedTickDoesNotCommit(t *testing.T) {
	r := &Reporter{}
	now := time.Now()
	r.dropUnchangedIdleSessions(snapOf(idleSession("a")), now)
	r.commitSentSessions()

	changed := idleSession("a")
	changed.OutputTokens = 9
	r.dropUnchangedIdleSessions(snapOf(changed), now.Add(time.Minute))
	// 本 tick 发送失败：不调用 commitSentSessions。

	mons := snapOf(changed)
	if dropped := r.dropUnchangedIdleSessions(mons, now.Add(2*time.Minute)); dropped != 0 {
		t.Fatalf("change from a failed tick must be re-sent, dropped=%d", dropped)
	}
}

func TestCommitSentSessions_PrunesVanishedSessions(t *testing.T) {
	r := &Reporter{}
	now := time.Now()
	r.dropUnchangedIdleSessions(snapOf(idleSession("a"), idleSession("b")), now)
	r.commitSentSessions()
	r.dropUnchangedIdleSessions(snapOf(idleSession("a")), now.Add(time.Minute))
	r.commitSentSessions()
	if _, ok := r.sent[cursorKey("claude", "b")]; ok {
		t.Fatalf("session b left the window and should be pruned from the fingerprint table")
	}
	if _, ok := r.sent[cursorKey("claude", "a")]; !ok {
		t.Fatalf("session a must stay tracked")
	}
}
