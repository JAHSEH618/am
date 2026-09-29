package qoder

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// 增量解析在副本上进行：缓存里的会话对象不能被改写，否则解析失败后同一批行会被重复累加。
func TestParseOne_IncrementalDoesNotMutateCachedBase(t *testing.T) {
	const head = `{"type":"user","sessionId":"s1","uuid":"u1","timestamp":"2026-07-13T09:00:00Z","message":{"role":"user","content":"hi"}}
`
	const tail = `{"type":"assistant","sessionId":"s1","uuid":"a1","timestamp":"2026-07-13T09:00:02Z","message":{"role":"assistant","content":[{"type":"text","text":"yo"}],"usage":{"input_tokens":12,"output_tokens":7}}}
`
	path := filepath.Join(t.TempDir(), "s1.jsonl")
	if err := os.WriteFile(path, []byte(head), 0o644); err != nil {
		t.Fatal(err)
	}
	p := &Provider{}
	base, off, err := p.parseOne(path, 0, nil)
	if err != nil || base == nil {
		t.Fatalf("first parse: %v %v", base, err)
	}
	if err := os.WriteFile(path, []byte(head+tail), 0o644); err != nil {
		t.Fatal(err)
	}
	next, _, err := p.parseOne(path, off, base)
	if err != nil {
		t.Fatal(err)
	}
	if next == base {
		t.Fatal("incremental parse must return a new object, not the cached pointer")
	}
	if base.AssistantMessages != 0 || base.InputTokens != 0 || len(base.Messages) != 1 {
		t.Fatalf("cached base was mutated: %+v", base)
	}
	if next.AssistantMessages != 1 || next.InputTokens != 12 || next.OutputTokens != 7 {
		t.Fatalf("incremental result wrong: %+v", next)
	}
	// 同一个 base 再增量一次（模拟上一次结果因故被丢弃、游标没前进）：结果必须与第一次相同，不得翻倍
	again, _, err := p.parseOne(path, off, base)
	if err != nil {
		t.Fatal(err)
	}
	if again.InputTokens != 12 || again.OutputTokens != 7 || again.AssistantMessages != 1 {
		t.Fatalf("re-parsing from the same cursor must be idempotent: %+v", again)
	}
}

func TestMergeRecordParsesMessagesToolsAndTokens(t *testing.T) {
	lines := []string{
		`{"type":"user","sessionId":"s1","uuid":"u1","timestamp":"2026-07-13T09:00:00Z","cwd":"/tmp/demo","message":{"role":"user","content":"fix it"}}`,
		`{"type":"assistant","sessionId":"s1","uuid":"a1","timestamp":"2026-07-13T09:00:02Z","message":{"role":"assistant","model":"qoder-model","usage":{"input_tokens":12,"output_tokens":7},"content":[{"type":"text","text":"done"},{"type":"tool_use","name":"read_file","input":{"file_path":"a.go"}}]}}`,
	}
	ps := &parsedSession{}
	for _, line := range lines {
		var rec transcriptRecord
		if err := json.Unmarshal([]byte(line), &rec); err != nil {
			t.Fatal(err)
		}
		mergeRecord(ps, rec)
	}
	if ps.SessionID != "s1" || ps.UserMessages != 1 || ps.AssistantMessages != 1 {
		t.Fatalf("unexpected session: %+v", ps)
	}
	if ps.InputTokens != 12 || ps.OutputTokens != 7 {
		t.Fatalf("tokens=%d/%d", ps.InputTokens, ps.OutputTokens)
	}
	if len(ps.Tools) != 1 || ps.Tools[0].Name != "Read" {
		t.Fatalf("tools=%+v", ps.Tools)
	}
	if len(ps.Messages) != 2 || ps.Messages[1].Text == "" {
		t.Fatalf("messages=%+v", ps.Messages)
	}
}

func TestToolResultDoesNotInflateUserCount(t *testing.T) {
	var rec transcriptRecord
	if err := json.Unmarshal([]byte(`{"type":"user","sessionId":"s1","uuid":"r1","timestamp":"2026-07-13T09:00:03Z","message":{"role":"user","content":[{"type":"tool_result","content":"ok"}]}}`), &rec); err != nil {
		t.Fatal(err)
	}
	ps := &parsedSession{}
	mergeRecord(ps, rec)
	if ps.UserMessages != 0 || len(ps.Messages) != 1 || ps.Messages[0].Role != "tool" {
		t.Fatalf("unexpected: %+v", ps)
	}
}
