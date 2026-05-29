package common

import (
	"testing"
	"time"
)

func TestInterpolateTimes(t *testing.T) {
	start := time.Date(2026, 5, 1, 10, 0, 0, 0, time.UTC)
	end := time.Date(2026, 5, 1, 11, 0, 0, 0, time.UTC)
	ts := InterpolateTimes(start, end, 3)
	if len(ts) != 3 {
		t.Fatalf("len=%d", len(ts))
	}
	if !ts[0].Equal(start) || !ts[2].Equal(end) {
		t.Fatalf("endpoints=%v %v", ts[0], ts[2])
	}
	if !ts[1].After(ts[0]) || !ts[2].After(ts[1]) {
		t.Fatalf("order=%v", ts)
	}
}
