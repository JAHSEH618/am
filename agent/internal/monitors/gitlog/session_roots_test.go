package gitlog

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
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

func snapsWithCwds(cwds ...string) []monitor.Snapshot {
	var s monitor.Snapshot
	for _, c := range cwds {
		s.Sessions = append(s.Sessions, monitor.Session{Cwd: c})
	}
	return []monitor.Snapshot{s}
}

// cwd → 仓库根：同一 tick 内去重、跨 tick 在 TTL 内复用、过期后重查、条目数有上限。
func TestTopLevelCache(t *testing.T) {
	t0 := time.Date(2026, 9, 28, 10, 0, 0, 0, time.UTC)
	type step struct {
		at        time.Time
		cwds      []string
		wantForks int // 本步新增的 git rev-parse 次数
	}
	for _, tc := range []struct {
		name       string
		max        int
		steps      []step
		wantMaxLen int
	}{
		{"dedupe within one call", 16, []step{{t0, []string{"/r/a", "/r/a", "/r/a/sub", "", "/r/a"}, 2}}, 2},
		{"reuse within ttl incl. not-a-repo", 16, []step{
			{t0, []string{"/r/a", "/tmp/x"}, 2},
			{t0.Add(59 * time.Minute), []string{"/r/a", "/tmp/x"}, 0},
		}, 2},
		{"refresh after ttl", 16, []step{
			{t0, []string{"/r/a"}, 1},
			{t0.Add(topLevelTTL), []string{"/r/a"}, 1},
		}, 1},
		{"bounded size", 4, []step{
			{t0, []string{"/c1", "/c2", "/c3", "/c4", "/c5", "/c6", "/c7", "/c8", "/c9"}, 9},
		}, 4},
	} {
		t.Run(tc.name, func(t *testing.T) {
			forks := 0
			tl := newTopLevelCache(topLevelTTL, tc.max, func(cwd string) string {
				forks++
				if strings.HasPrefix(cwd, "/tmp") {
					return "" // 不在仓库内：空结果同样缓存
				}
				return "/r/a"
			})
			path := filepath.Join(t.TempDir(), sessionRootsJSON)
			for i, st := range tc.steps {
				before := forks
				recordSessionRepoRoots(path, snapsWithCwds(st.cwds...), st.at, tl)
				if got := forks - before; got != st.wantForks {
					t.Fatalf("step %d: forks=%d want %d", i, got, st.wantForks)
				}
			}
			if len(tl.m) > tc.wantMaxLen {
				t.Fatalf("cache len=%d want <= %d", len(tl.m), tc.wantMaxLen)
			}
		})
	}
}

// last_seen 只记到天：同一天内重复出现不重写文件；新增仓库、跨天、prune 才写；旧版秒级文件照常解析。
func TestRecordSessionRepoRoots_WritesOnlyOnChange(t *testing.T) {
	day1 := time.Date(2026, 9, 28, 1, 2, 3, 0, time.UTC)
	type step struct {
		at        time.Time
		cwds      []string
		wantWrite bool
	}
	for _, tc := range []struct {
		name     string
		initial  string // 预置文件内容；空 = 无文件
		steps    []step
		wantDays map[string]string
	}{
		{
			name: "fresh file then same day",
			steps: []step{
				{day1, []string{"/a"}, true},
				{day1.Add(10 * time.Hour), []string{"/a"}, false},
				{day1.Add(11 * time.Hour), []string{"/a", "/b"}, true},
				{day1.Add(12 * time.Hour), []string{"/b", "/a"}, false},
				{day1.Add(24 * time.Hour), []string{"/a"}, true},
			},
			wantDays: map[string]string{"/a": "2026-09-29T00:00:00Z", "/b": "2026-09-28T00:00:00Z"},
		},
		{
			name:    "legacy second-resolution file, same day",
			initial: `{"repos":{"/a":"2026-09-28T00:59:59Z","/old":"2026-09-01T08:00:00Z"}}`,
			steps: []step{
				{day1, []string{"/a"}, false},
				{day1, nil, false},
			},
			wantDays: map[string]string{"/a": "2026-09-28T00:59:59Z", "/old": "2026-09-01T08:00:00Z"},
		},
		{
			name:    "legacy file, next day rewrites that root only",
			initial: `{"repos":{"/a":"2026-09-27T23:59:59Z","/old":"2026-09-01T08:00:00Z"}}`,
			steps: []step{
				{day1, []string{"/a"}, true},
			},
			wantDays: map[string]string{"/a": "2026-09-28T00:00:00Z", "/old": "2026-09-01T08:00:00Z"},
		},
		{
			name:    "prune of expired root forces write",
			initial: `{"repos":{"/stale":"2026-01-01T00:00:00Z"}}`,
			steps: []step{
				{day1, nil, true},
			},
			wantDays: map[string]string{},
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), sessionRootsJSON)
			if tc.initial != "" {
				if err := os.WriteFile(path, []byte(tc.initial), 0o600); err != nil {
					t.Fatal(err)
				}
			}
			tl := newTopLevelCache(topLevelTTL, 16, func(cwd string) string { return cwd })
			for i, st := range tc.steps {
				if got := recordSessionRepoRoots(path, snapsWithCwds(st.cwds...), st.at, tl); got != st.wantWrite {
					t.Fatalf("step %d: wrote=%v want %v", i, got, st.wantWrite)
				}
			}
			got := loadSessionRootsFile(path).Repos
			if len(got) != len(tc.wantDays) {
				t.Fatalf("repos=%v want %v", got, tc.wantDays)
			}
			for k, v := range tc.wantDays {
				if got[k] != v {
					t.Fatalf("repos[%s]=%q want %q (all=%v)", k, got[k], v, got)
				}
			}
		})
	}
}
