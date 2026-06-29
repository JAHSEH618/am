package cursor

import (
	"context"
	"database/sql"
	"fmt"
	"path/filepath"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

// 36 字符 UUID，substr(key,10,36) 恰好截出它作为 sid。
const (
	sidA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
	sidB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
)

func mustCursorDB(t *testing.T, withoutRowid bool) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "state.vscdb"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	ddl := "CREATE TABLE cursorDiskKV (key TEXT PRIMARY KEY, value TEXT)"
	if withoutRowid {
		ddl += " WITHOUT ROWID"
	}
	if _, err := db.Exec(ddl); err != nil {
		t.Fatalf("ddl: %v", err)
	}
	return db
}

func insertBubble(t *testing.T, db *sql.DB, sid, bid, createdAt string) {
	t.Helper()
	key := fmt.Sprintf("bubbleId:%s:%s", sid, bid)
	val := fmt.Sprintf(`{"createdAt":%q}`, createdAt)
	if _, err := db.Exec("INSERT INTO cursorDiskKV(key,value) VALUES(?,?)", key, val); err != nil {
		t.Fatalf("insert: %v", err)
	}
}

func refsBySid(refs []sessionRef) map[string]time.Time {
	m := map[string]time.Time{}
	for _, r := range refs {
		m[r.sid] = r.lastAt
	}
	return m
}

// 全量建立基线后，新增 bubble 只走增量、且会话被发现。
func TestDiscoverRefsIncremental(t *testing.T) {
	db := mustCursorDB(t, false)
	defer db.Close()
	ctx := context.Background()
	now := time.Date(2026, 6, 29, 12, 0, 0, 0, time.UTC)
	cutoff := now.Add(-48 * time.Hour)

	insertBubble(t, db, sidA, "b1", "2026-06-29T11:00:00.000Z")
	p := New("")

	refs, err := p.discoverRefs(ctx, db, now, cutoff)
	if err != nil {
		t.Fatalf("full discoverRefs: %v", err)
	}
	if !p.incrementalReady || p.rowidUnusable {
		t.Fatalf("expected incrementalReady after full scan (ready=%v unusable=%v)", p.incrementalReady, p.rowidUnusable)
	}
	if got := refsBySid(refs); len(got) != 1 || got[sidA].IsZero() {
		t.Fatalf("full scan: expected 1 ref for sidA, got %v", got)
	}
	baseRowid := p.lastRowid

	// 新增 session B 的一条 bubble → 增量应只读这一行并发现 B，A 仍在。
	insertBubble(t, db, sidB, "b1", "2026-06-29T11:50:00.000Z")
	refs, err = p.discoverRefs(ctx, db, now, cutoff)
	if err != nil {
		t.Fatalf("incremental discoverRefs: %v", err)
	}
	if p.lastRowid <= baseRowid {
		t.Fatalf("expected lastRowid to advance (base=%d now=%d)", baseRowid, p.lastRowid)
	}
	got := refsBySid(refs)
	if len(got) != 2 || got[sidA].IsZero() || got[sidB].IsZero() {
		t.Fatalf("incremental: expected refs for A and B, got %v", got)
	}
}

// WITHOUT ROWID 表 → queryMaxRowid 报错 → 永久回退全量，不崩、不退化。
func TestDiscoverRefsWithoutRowidFallback(t *testing.T) {
	db := mustCursorDB(t, true)
	defer db.Close()
	ctx := context.Background()
	now := time.Date(2026, 6, 29, 12, 0, 0, 0, time.UTC)
	cutoff := now.Add(-48 * time.Hour)

	insertBubble(t, db, sidA, "b1", "2026-06-29T11:00:00.000Z")
	p := New("")

	refs, err := p.discoverRefs(ctx, db, now, cutoff)
	if err != nil {
		t.Fatalf("discoverRefs (without rowid): %v", err)
	}
	if !p.rowidUnusable {
		t.Fatalf("expected rowidUnusable=true on WITHOUT ROWID table")
	}
	if p.incrementalReady {
		t.Fatalf("expected incrementalReady=false when rowid unusable")
	}
	if got := refsBySid(refs); len(got) != 1 || got[sidA].IsZero() {
		t.Fatalf("full-scan fallback should return sidA, got %v", got)
	}
	// 第二次仍走全量、依然正确（不退化）。
	refs, err = p.discoverRefs(ctx, db, now, cutoff)
	if err != nil {
		t.Fatalf("second full-scan: %v", err)
	}
	if got := refsBySid(refs); len(got) != 1 {
		t.Fatalf("second full-scan should still return sidA, got %v", got)
	}
}

// queryBubblesAfter 只返回水位之后的新增 bubble。
func TestQueryBubblesAfter(t *testing.T) {
	db := mustCursorDB(t, false)
	defer db.Close()
	ctx := context.Background()

	insertBubble(t, db, sidA, "b1", "2026-06-29T10:00:00.000Z")
	insertBubble(t, db, sidA, "b2", "2026-06-29T11:00:00.000Z")
	mark, err := queryMaxRowid(ctx, db)
	if err != nil {
		t.Fatalf("maxrowid: %v", err)
	}
	insertBubble(t, db, sidB, "b1", "2026-06-29T12:00:00.000Z")

	rows, err := queryBubblesAfter(ctx, db, mark)
	if err != nil {
		t.Fatalf("queryBubblesAfter: %v", err)
	}
	if len(rows) != 1 || rows[0].sid != sidB {
		t.Fatalf("expected only the new sidB bubble after rowid %d, got %+v", mark, rows)
	}
}
