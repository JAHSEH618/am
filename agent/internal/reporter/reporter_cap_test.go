package reporter

import (
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

func TestMaxMessagesPerSessionBootstrap(t *testing.T) {
	r := &Reporter{bootstrap: true}
	sess := monitor.Session{LastActivity: monitor.LocalTime(time.Now().Add(-30 * time.Minute))}
	if got := r.maxMessagesPerSession(sess); got != MaxMessagesPerSessionBootstrap {
		t.Fatalf("bootstrap cap=%d want %d", got, MaxMessagesPerSessionBootstrap)
	}
}

func TestMaxMessagesPerSessionSteady(t *testing.T) {
	r := &Reporter{bootstrap: false}
	sess := monitor.Session{LastActivity: monitor.LocalTime(time.Now().Add(-30 * time.Minute))}
	if got := r.maxMessagesPerSession(sess); got != MaxMessagesPerSessionActive {
		t.Fatalf("active steady cap=%d want %d", got, MaxMessagesPerSessionActive)
	}
}

func TestMaxMessagesPerSessionSteadyIdle(t *testing.T) {
	r := &Reporter{bootstrap: false}
	sess := monitor.Session{LastActivity: monitor.LocalTime(time.Now().Add(-5 * time.Hour))}
	if got := r.maxMessagesPerSession(sess); got != MaxMessagesPerSession {
		t.Fatalf("idle steady cap=%d want %d", got, MaxMessagesPerSession)
	}
}

func TestEstimateBackfillTicks(t *testing.T) {
	if got := estimateBackfillTicks(2500, true); got != 3 {
		t.Fatalf("bootstrap ticks=%d want 3", got)
	}
	if got := estimateBackfillTicks(2500, false); got != 13 {
		t.Fatalf("steady ticks=%d want 13", got)
	}
}
