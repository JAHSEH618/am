package codebuddy

import "testing"

func TestAppendMessageParsesLocalHistory(t *testing.T) {
	ps := &parsedSession{ID: "c1"}
	appendMessage(ps, map[string]any{"id": "u1", "role": "user", "content": "hello", "timestamp": "2026-07-13T09:00:00Z"}, 0)
	appendMessage(ps, map[string]any{"id": "a1", "role": "assistant", "content": []any{map[string]any{"type": "text", "text": "ok"}, map[string]any{"type": "tool_use", "name": "run_terminal_command", "input": map[string]any{"command": "pwd"}}}, "timestamp": "2026-07-13T09:00:01Z", "input_tokens": float64(9), "output_tokens": float64(4)}, 1)
	if ps.UserMessages != 1 || ps.AssistantMessages != 1 || ps.InputTokens != 9 || ps.OutputTokens != 4 {
		t.Fatalf("unexpected: %+v", ps)
	}
	if len(ps.Tools) != 1 || ps.Tools[0].Name != "Bash" {
		t.Fatalf("tools=%+v", ps.Tools)
	}
}
