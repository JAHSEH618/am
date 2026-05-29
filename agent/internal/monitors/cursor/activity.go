// Cursor 会话活跃与心跳辅助。
//
// gz
package cursor

import (
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const (
	waitingGrace    = common.WaitingGrace
	statusExecTool  = common.BubbleExecTool
	statusThinking  = common.BubbleThinking
	statusWaitingUser = common.BubbleWaitingUser
)

type activityTracker struct {
	mu           sync.Mutex
	activities   map[string]string
	waitingSince map[string]time.Time
}

func newActivityTracker() *activityTracker {
	return &activityTracker{
		activities:   make(map[string]string),
		waitingSince: make(map[string]time.Time),
	}
}

func (t *activityTracker) updateFromParsed(parsed []*parsedSession, now time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()

	active := make(map[string]struct{}, len(parsed))
	for _, ps := range parsed {
		if ps == nil || ps.SessionID == "" {
			continue
		}
		id := ps.SessionID
		active[id] = struct{}{}

		kind := common.ResolveActivity(common.SessionLike{
			LastActivity:  ps.LastActivity,
			BubbleStatus:  ps.BubbleStatus,
			RecentTools:   ps.RecentTools,
			CurrentTool:   ps.CurrentTool,
			LastSummaryAt: time.Time{},
		}, now)
		if kind == common.StatusWaiting {
			if _, ok := t.waitingSince[id]; !ok {
				t.waitingSince[id] = now
			}
			if now.Sub(t.waitingSince[id]) < waitingGrace {
				continue
			}
		} else {
			delete(t.waitingSince, id)
		}
		t.activities[id] = kind
	}

	for id := range t.activities {
		if _, ok := active[id]; !ok {
			delete(t.activities, id)
			delete(t.waitingSince, id)
		}
	}
}

func (t *activityTracker) get(id string) string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if k, ok := t.activities[id]; ok {
		return k
	}
	return common.StatusIdle
}

func determineBubbleStatus(lastType int, lastHadTool bool) string {
	switch lastType {
	case 1:
		return statusThinking
	case 2:
		if lastHadTool {
			return statusExecTool
		}
		return statusWaitingUser
	}
	return common.BubbleUnknown
}
