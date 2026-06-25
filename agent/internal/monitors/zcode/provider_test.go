// zcode Provider 单测：用临时 SQLite 造 zcode 真实布局（cli/db/db.sqlite + session/message/part），
// 校验 token 从 message.data.tokens 累加、model 取自 assistant modelID、工具抽取与版本回报。
// gz
package zcode

import (
	"database/sql"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

func TestProvider_Snapshot(t *testing.T) {
	root := t.TempDir()
	t.Setenv("AM_ZCODE_DIR", root)

	dbDir := filepath.Join(root, "cli", "db")
	if err := os.MkdirAll(dbDir, 0o755); err != nil {
		t.Fatal(err)
	}
	dbFile := filepath.Join(dbDir, "db.sqlite")

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

	if s.SessionID != "sess_unit-test-0000" {
		t.Errorf("SessionID = %q", s.SessionID)
	}
	if s.Cwd != "/Users/x/proj" {
		t.Errorf("Cwd = %q, want /Users/x/proj", s.Cwd)
	}
	if s.ProjectName != "proj" {
		t.Errorf("ProjectName = %q, want proj", s.ProjectName)
	}
	if s.Model != "GLM-5.2" {
		t.Errorf("Model = %q, want GLM-5.2", s.Model)
	}
	if s.UserMessages != 1 || s.AssistantMessages != 1 {
		t.Errorf("messages user=%d assistant=%d, want 1/1", s.UserMessages, s.AssistantMessages)
	}
	// token 分项累加（单条 assistant 消息）：tokens.input(100) 含缓存，拆出净输入 = 100-8-4 = 88
	if s.InputTokens != 88 {
		t.Errorf("InputTokens = %d, want 88 (=100-cacheRead8-cacheWrite4)", s.InputTokens)
	}
	if s.OutputTokens != 50 {
		t.Errorf("OutputTokens = %d, want 50", s.OutputTokens)
	}
	if s.CacheReadTokens != 8 {
		t.Errorf("CacheReadTokens = %d, want 8", s.CacheReadTokens)
	}
	if s.CacheCreateTokens != 4 {
		t.Errorf("CacheCreateTokens = %d, want 4", s.CacheCreateTokens)
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
	if got := p.TargetVersion(); got != "0.14.9" {
		t.Errorf("TargetVersion = %q, want 0.14.9", got)
	}
}

// TestProvider_Empty 无 db 时应优雅返回空快照而非报错。
func TestProvider_Empty(t *testing.T) {
	t.Setenv("AM_ZCODE_DIR", t.TempDir()) // 目录存在但无 cli/db/db.sqlite
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
			id text primary key, project_id text, directory text, title text, version text,
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

	const sid = "sess_unit-test-0000"
	if _, err := db.Exec(
		`INSERT INTO session(id,project_id,directory,title,version,time_created,time_updated,time_archived)
		 VALUES(?,?,?,?,?,?,?,NULL)`,
		sid, "proj-1", "/Users/x/proj", "hi'", "0.14.9", base, base+10000); err != nil {
		t.Fatalf("session: %v", err)
	}

	userData := `{"role":"user","model":{"modelID":"GLM-5.2","providerID":"builtin:bigmodel-start-plan"},"time":{"created":` + itoa(base) + `}}`
	asstData := `{"role":"assistant","modelID":"GLM-5.2","providerID":"builtin:bigmodel-start-plan","mode":"build",` +
		`"tokens":{"input":100,"output":50,"reasoning":7,"cache":{"read":8,"write":4}},` +
		`"time":{"created":` + itoa(base+1000) + `,"completed":` + itoa(base+5000) + `}}`

	if _, err := db.Exec(`INSERT INTO message(id,session_id,time_created,data) VALUES(?,?,?,?)`,
		"m1", sid, base, userData); err != nil {
		t.Fatalf("msg m1: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO message(id,session_id,time_created,data) VALUES(?,?,?,?)`,
		"m2", sid, base+1000, asstData); err != nil {
		t.Fatalf("msg m2: %v", err)
	}

	// 覆盖已确认的真实 part 形状：text / reasoning(→thinking) / tool(含 state 嵌套) / step-finish / step-start
	parts := []struct{ id, msg, data string }{
		{"p1", "m1", `{"type":"text","text":"hi","time":{"start":` + itoa(base) + `,"end":` + itoa(base) + `}}`},
		{"p2", "m2", `{"type":"text","text":"hello there"}`},
		{"p3", "m2", `{"type":"reasoning","text":"let me think"}`},
		{"p4", "m2", `{"type":"tool","tool":"Read","callID":"call_x","state":{"status":"completed","title":"Read","input":{"file_path":"/a/b"},"output":"...","time":{"start":1,"end":2}}}`},
		{"p5", "m2", `{"type":"step-finish","cost":0,"reason":"stop","tokens":{"cache":{"read":8,"write":4},"input":100,"output":50,"reasoning":7,"total":150}}`}, // 应被忽略
		{"p6", "m2", `{"type":"step-start"}`}, // 应被忽略
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
