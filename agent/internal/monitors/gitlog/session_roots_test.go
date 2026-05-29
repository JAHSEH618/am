package gitlog

import (
	"fmt"
	"testing"
	"time"
)

func TestMergeDiscoveryRoots_dedupeCfgOnly(t *testing.T) {
	got := mergeDiscoveryRoots([]string{"/a", "/b", "/a"})
	want := []string{"/a", "/b"}
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("got %#v want %#v", got, want)
	}
}

func TestPruneSessionRootsMap_ttl(t *testing.T) {
	now := time.Date(2026, 5, 15, 12, 0, 0, 0, time.UTC)
	repos := map[string]string{
		"/stale": now.Add(-200 * 24 * time.Hour).UTC().Format(time.RFC3339),
		"/fresh": now.Add(-time.Hour).UTC().Format(time.RFC3339),
	}
	pruneSessionRootsMap(repos, now)
	if _, ok := repos["/stale"]; ok {
		t.Fatal("expected stale path removed")
	}
	if _, ok := repos["/fresh"]; !ok {
		t.Fatal("expected fresh path kept")
	}
}

func TestPruneSessionRootsMap_maxCapKeepsNewest(t *testing.T) {
	now := time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC)
	repos := map[string]string{}
	for i := 0; i < maxSessionRoots+10; i++ {
		p := fmt.Sprintf("/repo%d", i)
		repos[p] = now.Add(time.Duration(i) * time.Second).UTC().Format(time.RFC3339)
	}
	pruneSessionRootsMap(repos, now.Add(time.Hour))
	if len(repos) != maxSessionRoots {
		t.Fatalf("want len %d got %d", maxSessionRoots, len(repos))
	}
	if _, ok := repos["/repo9"]; ok {
		t.Fatal("oldest bucket should have been trimmed")
	}
	if _, ok := repos[fmt.Sprintf("/repo%d", maxSessionRoots+9)]; !ok {
		t.Fatal("expected newest entries to survive cap prune")
	}
}
