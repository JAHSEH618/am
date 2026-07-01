// OpenCode Provider 单测：用临时 SQLite 造 opencode 真实布局（opencode.db + session/message/part），
// 校验 session 列 token/model 直取、消息聚合、以及 time_updated 记忆化行为。
// gz
package opencode

import (
	"database/sql"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

func TestProvider_Snapshot(t *testing.T) {
	root := t.TempDir()
	t.Setenv("AM_OPENCODE_DIR", root)

	dbFile := filepath.Join(root, "opencode.db")

	base := time.Now().Add(-time.Minute).UnixMilli()
	seedDB(t, dbFile, base)

	p := New("/tmp/fallback")
	p.SetLookback(10 * 365 * 24 * time.Hour) // 大窗口，避免 fixture 时间过期

	snap, err := p.Snapshot(t.Context())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if snap.Type != TypeCode {
		t.Fatalf("Type = %q, want %q", snap.Type, TypeCode)
	}
	if len(snap.Sessions) != 1 {
		t.Fatalf("sessions = %d, want 1", len(snap.Sessions))
	}
	s := snap.Sessions[0]

	if s.SessionID != "ses_unit-test-0000" {
		t.Errorf("SessionID = %q", s.SessionID)
	}
	if s.Cwd != "/Users/x/proj" {
		t.Errorf("Cwd = %q, want /Users/x/proj", s.Cwd)
	}
	if s.ProjectName != "proj" {
		t.Errorf("ProjectName = %q, want proj", s.ProjectName)
	}
	if s.Model != "claude-sonnet-5" {
		t.Errorf("Model = %q, want claude-sonnet-5", s.Model)
	}
	if s.UserMessages != 1 || s.AssistantMessages != 1 {
		t.Errorf("messages user=%d assistant=%d, want 1/1", s.UserMessages, s.AssistantMessages)
	}
	// opencode token 直取 session 列（非从消息累加）。
	if s.InputTokens != 200 {
		t.Errorf("InputTokens = %d, want 200", s.InputTokens)
	}
	if s.OutputTokens != 80 {
		t.Errorf("OutputTokens = %d, want 80", s.OutputTokens)
	}
	if s.CacheReadTokens != 12 {
		t.Errorf("CacheReadTokens = %d, want 12", s.CacheReadTokens)
	}
	if s.CacheCreateTokens != 6 {
		t.Errorf("CacheCreateTokens = %d, want 6", s.CacheCreateTokens)
	}
	if len(s.RecentTools) != 1 || s.RecentTools[0].Name != "Read" {
		t.Errorf("RecentTools = %+v, want one Read", s.RecentTools)
	}
	// user "hi" + assistant("hello there" 文本) = 2 条带内容的消息
	if len(s.RecentMessages) != 2 {
		t.Errorf("RecentMessages = %d, want 2", len(s.RecentMessages))
	}
	if len(s.ActivityDeltas) == 0 {
		t.Errorf("ActivityDeltas empty")
	}
}

// TestProvider_MemoizesUnchangedSession 证明慢路径（data_version 已前进）里，time_updated 未变的
// session 复用上次 fillRecent 结果，不会重读 message/part：删掉 A 的全部消息行但保留 time_updated，
// 第二次 Snapshot 的 counts 必须仍等于第一次（若记忆化失效、真去重读空表，counts 会归零）。
// 随后反向验证：推进 time_updated 并写入新消息，第三次 Snapshot 必须反映新数据（证明"变了就重读"）。
func TestProvider_MemoizesUnchangedSession(t *testing.T) {
	root := t.TempDir()
	t.Setenv("AM_OPENCODE_DIR", root)

	dbFile := filepath.Join(root, "opencode.db")

	base := time.Now().Add(-time.Minute).UnixMilli()
	seedDB(t, dbFile, base)

	const sid = "ses_unit-test-0000"

	p := New("/tmp/fallback")
	p.SetLookback(10 * 365 * 24 * time.Hour)

	snap1, err := p.Snapshot(t.Context())
	if err != nil {
		t.Fatalf("Snapshot #1: %v", err)
	}
	if len(snap1.Sessions) != 1 {
		t.Fatalf("sessions #1 = %d, want 1", len(snap1.Sessions))
	}
	s1 := snap1.Sessions[0]
	if s1.UserMessages != 1 || s1.AssistantMessages != 1 {
		t.Fatalf("baseline messages user=%d assistant=%d, want 1/1", s1.UserMessages, s1.AssistantMessages)
	}

	// 写连接：删掉 A 的全部消息行，但不改 time_updated —— 这是一次写入（推进 data_version，走慢路径），
	// 但 session 级记忆化应命中，counts 不应归零。
	wdb, err := sql.Open("sqlite", dbFile)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := wdb.Exec(`DELETE FROM message WHERE session_id=?`, sid); err != nil {
		t.Fatalf("delete messages: %v", err)
	}

	snap2, err := p.Snapshot(t.Context())
	if err != nil {
		t.Fatalf("Snapshot #2: %v", err)
	}
	if len(snap2.Sessions) != 1 {
		t.Fatalf("sessions #2 = %d, want 1", len(snap2.Sessions))
	}
	s2 := snap2.Sessions[0]
	if s2.UserMessages != s1.UserMessages || s2.AssistantMessages != s1.AssistantMessages {
		t.Errorf("memoized counts changed: got user=%d assistant=%d, want user=%d assistant=%d (memoize should skip re-read)",
			s2.UserMessages, s2.AssistantMessages, s1.UserMessages, s1.AssistantMessages)
	}
	if len(s2.RecentMessages) != len(s1.RecentMessages) {
		t.Errorf("memoized RecentMessages changed: got %d, want %d", len(s2.RecentMessages), len(s1.RecentMessages))
	}

	// 反向：推进 time_updated 并写入新消息 —— 应触发重读，counts 反映新数据。
	newUpdated := base + 20000
	if _, err := wdb.Exec(`UPDATE session SET time_updated=? WHERE id=?`, newUpdated, sid); err != nil {
		t.Fatalf("advance time_updated: %v", err)
	}
	newUserData := `{"role":"user","time":{"created":` + itoa(newUpdated) + `}}`
	if _, err := wdb.Exec(`INSERT INTO message(id,session_id,time_created,data) VALUES(?,?,?,?)`,
		"m3", sid, newUpdated, newUserData); err != nil {
		t.Fatalf("insert new message: %v", err)
	}
	if err := wdb.Close(); err != nil {
		t.Fatal(err)
	}

	snap3, err := p.Snapshot(t.Context())
	if err != nil {
		t.Fatalf("Snapshot #3: %v", err)
	}
	if len(snap3.Sessions) != 1 {
		t.Fatalf("sessions #3 = %d, want 1", len(snap3.Sessions))
	}
	s3 := snap3.Sessions[0]
	if s3.UserMessages != 1 || s3.AssistantMessages != 0 {
		t.Errorf("post-update messages user=%d assistant=%d, want 1/0 (re-read should reflect new data)", s3.UserMessages, s3.AssistantMessages)
	}
}

// TestProvider_Empty 无 db 时应优雅返回空快照而非报错。
func TestProvider_Empty(t *testing.T) {
	t.Setenv("AM_OPENCODE_DIR", t.TempDir()) // 目录存在但无 opencode.db
	p := New("")
	snap, err := p.Snapshot(t.Context())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if len(snap.Sessions) != 0 {
		t.Fatalf("sessions = %d, want 0", len(snap.Sessions))
	}
}

func seedDB(t *testing.T, path string, base int64) {
	t.Helper()
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = db.Close() }()

	stmts := []string{
		`CREATE TABLE session (
			id text primary key, directory text, title text, version text, model text,
			tokens_input integer, tokens_output integer, tokens_reasoning integer,
			tokens_cache_read integer, tokens_cache_write integer,
			time_created integer, time_updated integer, time_archived integer
		)`,
		`CREATE TABLE message (
			id text primary key, session_id text, time_created integer, data text
		)`,
		`CREATE TABLE part (
			id text primary key, message_id text, session_id text, data text
		)`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			t.Fatalf("schema: %v", err)
		}
	}

	const sid = "ses_unit-test-0000"
	if _, err := db.Exec(
		`INSERT INTO session(id,directory,title,version,model,
			tokens_input,tokens_output,tokens_reasoning,tokens_cache_read,tokens_cache_write,
			time_created,time_updated,time_archived)
		 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL)`,
		sid, "/Users/x/proj", "hi", "0.1.53", "claude-sonnet-5",
		200, 80, 5, 12, 6, base, base+10000); err != nil {
		t.Fatalf("session: %v", err)
	}

	userData := `{"role":"user","time":{"created":` + itoa(base) + `}}`
	asstData := `{"role":"assistant","modelID":"claude-sonnet-5",` +
		`"tokens":{"input":50,"output":20,"reasoning":2,"cache":{"read":3,"write":1}},` +
		`"time":{"created":` + itoa(base+1000) + `,"completed":` + itoa(base+5000) + `}}`

	if _, err := db.Exec(`INSERT INTO message(id,session_id,time_created,data) VALUES(?,?,?,?)`,
		"m1", sid, base, userData); err != nil {
		t.Fatalf("msg m1: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO message(id,session_id,time_created,data) VALUES(?,?,?,?)`,
		"m2", sid, base+1000, asstData); err != nil {
		t.Fatalf("msg m2: %v", err)
	}

	parts := []struct{ id, msg, data string }{
		{"p1", "m1", `{"type":"text","text":"hi"}`},
		{"p2", "m2", `{"type":"text","text":"hello there"}`},
		{"p3", "m2", `{"type":"reasoning","text":"let me think"}`},
		{"p4", "m2", `{"type":"tool","tool":"Read","state":{"status":"completed"}}`},
	}
	for _, pt := range parts {
		if _, err := db.Exec(`INSERT INTO part(id,message_id,session_id,data) VALUES(?,?,?,?)`,
			pt.id, pt.msg, sid, pt.data); err != nil {
			t.Fatalf("part %s: %v", pt.id, err)
		}
	}
}

func itoa(v int64) string {
	return strconv.FormatInt(v, 10)
}
