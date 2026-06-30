package hermes

import (
	"database/sql"
	"path/filepath"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

func TestQuerySessions_HoistedCountsAndLastActivity(t *testing.T) {
	root := t.TempDir()
	t.Setenv("AM_HERMES_DIR", root)
	dbFile := filepath.Join(root, "state.db")

	base := float64(time.Now().Add(-time.Minute).Unix())
	seedHermesDB(t, dbFile, base)

	p := New("/tmp/fallback")
	p.SetLookback(10 * 365 * 24 * time.Hour) // 大窗口,避免 fixture 时间过期

	snap, err := p.Snapshot(t.Context())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if len(snap.Sessions) != 1 {
		t.Fatalf("sessions = %d, want 1 (%+v)", len(snap.Sessions), snap.Sessions)
	}
	s := snap.Sessions[0]
	if s.SessionID != "sess-h-1" {
		t.Errorf("SessionID = %q", s.SessionID)
	}
	if s.UserMessages != 2 || s.AssistantMessages != 1 {
		t.Errorf("counts user=%d assistant=%d, want 2/1", s.UserMessages, s.AssistantMessages)
	}
	// LastActivity 应取 messages 里最大 timestamp(= base+30)
	wantLast := time.Unix(int64(base+30), 0)
	if got := s.LastActivity.Time(); got.Unix() != wantLast.Unix() {
		t.Errorf("LastActivity = %v, want ~%v", got, wantLast)
	}
}

func seedHermesDB(t *testing.T, path string, base float64) {
	t.Helper()
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = db.Close() }()

	stmts := []string{
		`CREATE TABLE sessions (
			id TEXT PRIMARY KEY, source TEXT, user_id TEXT, model TEXT, title TEXT,
			started_at REAL, ended_at REAL,
			input_tokens INTEGER, output_tokens INTEGER,
			cache_read_tokens INTEGER, cache_write_tokens INTEGER, reasoning_tokens INTEGER,
			tool_call_count INTEGER
		)`,
		`CREATE TABLE messages (
			id TEXT PRIMARY KEY, session_id TEXT, role TEXT, content TEXT,
			tool_name TEXT, timestamp REAL, token_count INTEGER
		)`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			t.Fatalf("schema: %v", err)
		}
	}
	if _, err := db.Exec(`INSERT INTO sessions
		(id,source,user_id,model,title,started_at,ended_at,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,reasoning_tokens,tool_call_count)
		VALUES('sess-h-1','hermes-cli','u','glm','t',?,0,10,5,0,0,0,0)`, base); err != nil {
		t.Fatalf("session: %v", err)
	}
	msgs := []struct {
		id, role, content string
		ts                float64
	}{
		{"m1", "user", "hi", base},
		{"m2", "assistant", "hello", base + 10},
		{"m3", "user", "more", base + 30},
	}
	for _, m := range msgs {
		if _, err := db.Exec(`INSERT INTO messages(id,session_id,role,content,tool_name,timestamp,token_count)
			VALUES(?,?,?,?,'',?,1)`, m.id, "sess-h-1", m.role, m.content, m.ts); err != nil {
			t.Fatalf("msg %s: %v", m.id, err)
		}
	}
}
