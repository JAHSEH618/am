// Claude JSONL 模块单元测试。
//
// gz
package claude

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// fixtureJSONL 是 Claude Code CLI 写出的 jsonl 真实结构的精简样本（2026-05）。
//
//   - 一个 user 行（人类提问）
//   - 一个 assistant 行（含 tool_use Edit）
//   - 一个 user 行（tool_result，应该走 isToolResult 分支不算 user message）
//   - 一个 assistant 文本回复（带 usage） → 末态 waiting_for_user
const fixtureJSONL = `{"type":"user","uuid":"u1","sessionId":"s","cwd":"/tmp/proj","gitBranch":"main","timestamp":"2026-05-07T10:00:00Z","version":"1.0.0","message":{"role":"user","content":[{"type":"text","text":"please refactor"}]}}
{"type":"assistant","uuid":"a1","sessionId":"s","timestamp":"2026-05-07T10:00:05Z","message":{"role":"assistant","model":"claude-opus-4-7","content":[{"type":"text","text":"on it"},{"type":"tool_use","name":"Edit","input":{"file_path":"/tmp/proj/x.go"}}],"usage":{"input_tokens":120,"output_tokens":42,"cache_creation_input_tokens":0,"cache_read_input_tokens":50}}}
{"type":"user","uuid":"u2","sessionId":"s","timestamp":"2026-05-07T10:00:06Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"a1-0","is_error":false}]}}
{"type":"assistant","uuid":"a2","sessionId":"s","timestamp":"2026-05-07T10:00:08Z","message":{"role":"assistant","model":"claude-opus-4-7","content":[{"type":"text","text":"done"}],"usage":{"input_tokens":80,"output_tokens":12,"cache_creation_input_tokens":0,"cache_read_input_tokens":120}}}
`

func writeFixture(t *testing.T, body string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "session.jsonl")
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatalf("write fixture: %v", err)
	}
	return path
}

func TestParseFile_Basic(t *testing.T) {
	path := writeFixture(t, fixtureJSONL)
	ps, consumed, err := parseFile(path)
	if err != nil {
		t.Fatalf("parseFile: %v", err)
	}
	if consumed == 0 {
		t.Fatalf("consumed should be > 0")
	}
	if ps.SessionID != "session" {
		t.Errorf("SessionID = %q want %q", ps.SessionID, "session")
	}
	if ps.CWD != "/tmp/proj" {
		t.Errorf("CWD = %q", ps.CWD)
	}
	if ps.GitBranch != "main" {
		t.Errorf("GitBranch = %q", ps.GitBranch)
	}
	if ps.Model != "claude-opus-4-7" {
		t.Errorf("Model = %q", ps.Model)
	}
	if ps.UserMessages != 1 {
		t.Errorf("UserMessages = %d want 1 (tool_result row should not count)", ps.UserMessages)
	}
	if ps.AssistantMessages != 2 {
		t.Errorf("AssistantMessages = %d want 2", ps.AssistantMessages)
	}
	if ps.InputTokens != 200 {
		t.Errorf("InputTokens = %d want 200", ps.InputTokens)
	}
	if ps.OutputTokens != 54 {
		t.Errorf("OutputTokens = %d want 54", ps.OutputTokens)
	}
	if ps.CacheRead != 170 {
		t.Errorf("CacheRead = %d want 170", ps.CacheRead)
	}
	if ps.BubbleStatus != common.BubbleWaitingUser {
		t.Errorf("BubbleStatus = %q want waiting_for_user", ps.BubbleStatus)
	}
	if len(ps.RecentTools) != 1 || ps.RecentTools[0].Name != "Edit" {
		t.Errorf("RecentTools = %+v", ps.RecentTools)
	}
}

func TestParseFile_Incremental(t *testing.T) {
	const head = `{"type":"user","uuid":"u1","sessionId":"s","cwd":"/tmp/proj","timestamp":"2026-05-07T10:00:00Z","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}
`
	const tail = `{"type":"assistant","uuid":"a1","sessionId":"s","timestamp":"2026-05-07T10:00:05Z","message":{"role":"assistant","model":"gpt-x","content":[{"type":"text","text":"yo"}],"usage":{"input_tokens":10,"output_tokens":2,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}
`
	path := writeFixture(t, head)
	first, off, err := parseFile(path)
	if err != nil {
		t.Fatalf("first parseFile: %v", err)
	}
	if first.UserMessages != 1 || first.AssistantMessages != 0 {
		t.Fatalf("baseline messages wrong: %d/%d", first.UserMessages, first.AssistantMessages)
	}

	if err := os.WriteFile(path, []byte(head+tail), 0o644); err != nil {
		t.Fatalf("rewrite fixture: %v", err)
	}

	second, _, err := parseFileIncremental(path, off, first)
	if err != nil {
		t.Fatalf("incremental parseFile: %v", err)
	}
	if second.UserMessages != 1 || second.AssistantMessages != 1 {
		t.Fatalf("incremental messages wrong: %d/%d", second.UserMessages, second.AssistantMessages)
	}
	if second.OutputTokens != 2 {
		t.Errorf("incremental OutputTokens = %d want 2", second.OutputTokens)
	}
	if first.AssistantMessages != 0 {
		t.Errorf("base session was mutated by incremental parse")
	}
}

func TestProvider_Snapshot(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("AM_CLAUDE_DIR", dir)

	projDir := filepath.Join(dir, "projects", "-tmp-proj")
	if err := os.MkdirAll(projDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(projDir, "abc.jsonl"), []byte(fixtureJSONL), 0o644); err != nil {
		t.Fatal(err)
	}

	p := New("/tmp/proj")
	// fixture 时间戳固定为 2026-05-07，需放宽 lookback 否则稳态 48h 窗口会过滤掉
	p.SetLookback(365 * 24 * time.Hour)
	snap, err := p.Snapshot(t.Context())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if snap.Type != TypeCode {
		t.Fatalf("type = %q", snap.Type)
	}
	if len(snap.Sessions) != 1 {
		t.Fatalf("expected 1 session, got %d", len(snap.Sessions))
	}
	got := snap.Sessions[0]
	if got.SessionID != "abc" {
		t.Errorf("SessionID = %q", got.SessionID)
	}
	if got.Cwd != "/tmp/proj" {
		t.Errorf("Cwd = %q", got.Cwd)
	}
	if got.Model != "claude-opus-4-7" {
		t.Errorf("Model = %q", got.Model)
	}
	if got.InputTokens != 200 || got.OutputTokens != 54 {
		t.Errorf("tokens = %d/%d", got.InputTokens, got.OutputTokens)
	}
	if got.CacheReadTokens != 170 {
		t.Errorf("cache tokens = %d want 170", got.CacheReadTokens)
	}
}

func TestProvider_Claude_SubagentMerge(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("AM_CLAUDE_DIR", dir)

	projDir := filepath.Join(dir, "projects", "-tmp-proj")
	sessionDir := filepath.Join(projDir, "parent-session")
	subDir := filepath.Join(sessionDir, "subagents")
	if err := os.MkdirAll(subDir, 0o755); err != nil {
		t.Fatal(err)
	}

	subBody := strings.ReplaceAll(fixtureJSONL, `"sessionId":"s"`, `"sessionId":"parent-session","isSidechain":true`)
	if err := os.WriteFile(filepath.Join(subDir, "agent-a.jsonl"), []byte(subBody), 0o644); err != nil {
		t.Fatal(err)
	}

	p := New("/tmp/proj")
	p.SetLookback(365 * 24 * time.Hour)
	snap, err := p.Snapshot(t.Context())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if len(snap.Sessions) != 1 {
		t.Fatalf("want 1 merged session, got %d", len(snap.Sessions))
	}
	if snap.Sessions[0].SessionID != "parent-session" {
		t.Fatalf("SessionID=%q want parent-session", snap.Sessions[0].SessionID)
	}
}
