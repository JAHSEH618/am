package trae

import (
	"testing"
	"time"
)

func TestSessionsFromChatStore(t *testing.T) {
	raw := []byte(`{"list":[{"id":"chat-1","workspacePath":"/tmp/demo","model":"doubao","messages":[{"id":"u1","role":"user","timestamp":1783904400000,"parsedQuery":["hello"]},{"id":"a1","role":"assistant","timestamp":1783904402000,"content":[{"type":"text","text":"ok"},{"type":"tool_call","name":"read_file","arguments":{"path":"a.go"}}],"inputTokens":8,"outputTokens":3}]}]}`)
	got := sessionsFromValue(raw, "fixture", time.Time{})
	if len(got) != 1 {
		t.Fatalf("sessions=%d", len(got))
	}
	ps := got[0]
	if ps.ID != "chat-1" || ps.UserMessages != 1 || ps.AssistantMessages != 1 {
		t.Fatalf("unexpected: %+v", ps)
	}
	if ps.InputTokens != 8 || ps.OutputTokens != 3 || len(ps.Tools) != 1 || ps.Tools[0].Name != "Read" {
		t.Fatalf("unexpected metrics: %+v", ps)
	}
}

func TestNestedJSONStringIsDecoded(t *testing.T) {
	raw := []byte(`"{\"sessions\":[{\"sessionId\":\"s2\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}]}"`)
	got := sessionsFromValue(raw, "nested", time.Unix(100, 0))
	if len(got) != 1 || got[0].ID != "s2" {
		t.Fatalf("got=%+v", got)
	}
}
