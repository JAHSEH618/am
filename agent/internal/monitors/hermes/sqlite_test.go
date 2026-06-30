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
	if len(snap.Sessions) != 2 {
		t.Fatalf("sessions = %d, want 2 (%+v)", len(snap.Sessions), snap.Sessions)
	}
	var have1, have2 bool
	var u1, a1, u2, a2 int
	var last1 time.Time
	for _, s := range snap.Sessions {
		switch s.SessionID {
		case "sess-h-1":
			have1, u1, a1, last1 = true, s.UserMessages, s.AssistantMessages, s.LastActivity.Time()
		case "sess-h-2":
			have2, u2, a2 = true, s.UserMessages, s.AssistantMessages
		}
	}
	if !have1 {
		t.Fatalf("missing sess-h-1")
	}
	if u1 != 2 || a1 != 1 {
		t.Errorf("sess-h-1 counts user=%d assistant=%d, want 2/1", u1, a1)
	}
	// LastActivity 应取 messages 里最大 timestamp(= base+30)
	wantLast := time.Unix(int64(base+30), 0)
	if last1.Unix() != wantLast.Unix() {
		t.Errorf("sess-h-1 LastActivity = %v, want ~%v", last1, wantLast)
	}
	// 无消息会话:必须出现(LEFT JOIN 不丢行)、计数为 0
	if !have2 {
		t.Fatalf("sess-h-2 (no messages) missing — LEFT JOIN/COALESCE 回退路径漏掉了无消息会话")
	}
	if u2 != 0 || a2 != 0 {
		t.Errorf("sess-h-2 counts user=%d assistant=%d, want 0/0", u2, a2)
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
	// 第二个会话:无任何消息且 ended_at 为 NULL —— 走 hoist 的关键边界:
	// CTE LEFT JOIN 的 lm.ts=NULL 须与原关联子查询返回 NULL 一致,COALESCE 回退到 started_at,
	// 该会话仍应出现在快照里(LEFT JOIN 不丢行)、计数为 0。
	if _, err := db.Exec(`INSERT INTO sessions
		(id,source,user_id,model,title,started_at,ended_at,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,reasoning_tokens,tool_call_count)
		VALUES('sess-h-2','hermes-cli','u','glm','t2',?,NULL,0,0,0,0,0,0)`, base); err != nil {
		t.Fatalf("session2: %v", err)
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
