package gitlog

import (
	"testing"
	"time"
)

func TestParseLog_numstatOnFollowingLinesAfterRecordSeparator(t *testing.T) {
	blob := "abc123def456789012345678901234567890abcd\x1f2026-05-15T12:00:00+08:00\x1fn\x1fe@x.com\x1fsubject\x1e\n" +
		"10\t5\ta.go\n" +
		"3\t0\tb.go\n" +
		"deadbeefdeadbeefdeadbeefdeadbeefdeadbeef\x1f2026-05-15T13:00:00+08:00\x1fn\x1fe@x.com\x1fsecond\x1e\n" +
		"1\t1\tc.go\n"
	got := parseLog(blob, "https://ex/repo.git", "main", DefaultMaxFilesPerCommit)
	if len(got) != 2 {
		t.Fatalf("commits: want 2 got %d", len(got))
	}
	if got[0].LinesAdded != 13 || got[0].LinesDeleted != 5 || got[0].FilesChanged != 2 {
		t.Fatalf("commit0 stats: +%d -%d files=%d", got[0].LinesAdded, got[0].LinesDeleted, got[0].FilesChanged)
	}
	if len(got[0].PathStats) != 2 || got[0].PathStats[0].Path != "a.go" || got[0].PathStats[0].LinesAdded != 10 || got[0].PathStats[1].Path != "b.go" {
		t.Fatalf("commit0 path_stats: %#v", got[0].PathStats)
	}
	if got[1].LinesAdded != 1 || got[1].LinesDeleted != 1 || got[1].FilesChanged != 1 {
		t.Fatalf("commit1 stats: +%d -%d files=%d", got[1].LinesAdded, got[1].LinesDeleted, got[1].FilesChanged)
	}
	t0, _ := time.Parse(time.RFC3339, "2026-05-15T12:00:00+08:00")
	t1, _ := time.Parse(time.RFC3339, "2026-05-15T13:00:00+08:00")
	if time.Time(got[0].CommitTime) != t0 || time.Time(got[1].CommitTime) != t1 {
		t.Fatalf("times: %#v %#v", got[0].CommitTime, got[1].CommitTime)
	}
	if got[0].CommitHash != "abc123def456789012345678901234567890abcd" {
		t.Fatalf("hash0 %q", got[0].CommitHash)
	}
	if got[0].RepoURL != "https://ex/repo.git" || got[0].BranchName != "main" {
		t.Fatalf("meta0")
	}
}