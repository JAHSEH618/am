package common

import (
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

// 超长行必须被越过（其后的行照常解析、offset 前进），未写完的超长行留待下次；consumed 精确到字节。
func TestScanJSONL_OverlongLines(t *testing.T) {
	const small = 128 << 10 // > 64KiB 初始缓冲，limit == small
	big := strings.Repeat("x", 5<<20)
	a, b, c := `{"a":1}`, `{"b":2}`, `{"c":3}`
	show := func(line string) string {
		if len(line) > 64 {
			return fmt.Sprintf("<%d bytes>", len(line))
		}
		return line
	}
	type step struct {
		appendData string
		wantLines  []string
		wantOffset int64 // 绝对偏移
	}
	for _, tc := range []struct {
		name    string
		maxLine int
		steps   []step
	}{
		{"5MiB line followed by a normal line", 4 << 20, []step{
			{a + "\n" + big + "\n" + b + "\n", []string{a, b}, int64(len(a) + 1 + len(big) + 1 + len(b) + 1)},
		}},
		{"unterminated long line waits, then is skipped once terminated", 4 << 20, []step{
			{a + "\n" + big, []string{a}, int64(len(a) + 1)},
			{"", nil, int64(len(a) + 1)},
			{"\n" + c + "\n", []string{c}, int64(len(a) + 1 + len(big) + 1 + len(c) + 1)},
		}},
		{"incremental scan starting at a long line", 4 << 20, []step{
			{a + "\n", []string{a}, int64(len(a) + 1)},
			{big + "\n" + b + "\n", []string{b}, int64(len(a) + 1 + len(big) + 1 + len(b) + 1)},
		}},
		{"back-to-back long lines", 4 << 20, []step{
			{big + "\n" + big + "\n" + a + "\n", []string{a}, int64(2*(len(big)+1) + len(a) + 1)},
		}},
		{"boundary: limit-1 bytes kept, limit bytes skipped", small, []step{
			{strings.Repeat("y", small-1) + "\n" + strings.Repeat("z", small) + "\n" + a + "\n",
				[]string{show(strings.Repeat("y", small-1)), a}, int64(small + small + 1 + len(a) + 1)},
		}},
		{"CRLF lines counted exactly", 4 << 20, []step{
			{a + "\r\n" + b + "\r\n", []string{a, b}, int64(len(a) + 2 + len(b) + 2)},
			{c + "\r\n", []string{c}, int64(len(a) + 2 + len(b) + 2 + len(c) + 2)},
		}},
		{"short unterminated tail still delivered (unchanged)", 4 << 20, []step{
			{a + "\n" + `{"b":`, []string{a, `{"b":`}, int64(len(a) + 1 + len(`{"b":`))},
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "s.jsonl")
			if err := os.WriteFile(path, nil, 0o644); err != nil {
				t.Fatal(err)
			}
			var offset int64
			for i, st := range tc.steps {
				f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
				if err != nil {
					t.Fatal(err)
				}
				if _, err := f.WriteString(st.appendData); err != nil {
					t.Fatal(err)
				}
				_ = f.Close()

				var got []string
				consumed, parsed, err := ScanJSONL(path, offset, tc.maxLine, func(line []byte, _ int64) bool {
					got = append(got, show(string(line)))
					return true
				})
				if err != nil {
					t.Fatalf("step %d: err=%v", i, err)
				}
				if !reflect.DeepEqual(got, st.wantLines) {
					t.Fatalf("step %d: lines=%v want %v", i, got, st.wantLines)
				}
				if parsed != (len(st.wantLines) > 0) {
					t.Fatalf("step %d: parsed=%v", i, parsed)
				}
				if consumed != st.wantOffset {
					t.Fatalf("step %d: consumed=%d want %d", i, consumed, st.wantOffset)
				}
				offset = consumed
			}
		})
	}
}

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
