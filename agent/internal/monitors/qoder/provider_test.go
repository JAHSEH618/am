package qoder

import (
	"encoding/json"
	"testing"
)

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
