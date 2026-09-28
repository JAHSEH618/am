package common

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestFilterFreshGroups(t *testing.T) {
	dir := t.TempDir()
	write := func(name string, age time.Duration) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte("{}\n"), 0o644); err != nil {
			t.Fatal(err)
		}
		ts := time.Now().Add(-age)
		if err := os.Chtimes(p, ts, ts); err != nil {
			t.Fatal(err)
		}
		return p
	}
	fresh := write("a-fresh.jsonl", time.Hour)
	stale := write("b-stale.jsonl", 72*time.Hour)
	staleSibling := write("a-stale-child.jsonl", 72*time.Hour)
	missing := filepath.Join(dir, "missing.jsonl")
	cutoff := time.Now().Add(-48 * time.Hour)

	got := FilterFreshGroups([]string{fresh, stale, missing}, nil, cutoff)
	if len(got) != 2 || got[0] != fresh || got[1] != missing {
		t.Fatalf("per-file filter = %v, want [fresh missing]", got)
	}

	byPrefix := func(p string) string { return filepath.Base(p)[:1] }
	got = FilterFreshGroups([]string{staleSibling, fresh, stale}, byPrefix, cutoff)
	if len(got) != 2 || got[0] != staleSibling || got[1] != fresh {
		t.Fatalf("group filter = %v, want [staleSibling fresh]", got)
	}

	if got := FilterFreshGroups([]string{stale}, nil, time.Time{}); len(got) != 1 {
		t.Fatalf("zero cutoff must keep everything, got %v", got)
	}
}
