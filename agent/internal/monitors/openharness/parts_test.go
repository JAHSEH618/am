// OpenHarness parts 单元测试。
// gz
package openharness

import "testing"

func TestExtractMessageParts_ToolUseAndResult(t *testing.T) {
	parts := extractMessageParts("assistant", []rawContent{
		{Type: "tool_use", Name: "read", Input: []byte(`{"path":"a.go"}`)},
		{Type: "tool_result", Content: []byte(`"ok"`)},
	})
	if len(parts) != 2 {
		t.Fatalf("parts=%+v", parts)
	}
	if parts[0].Type != "tool_call" || parts[1].Type != "tool_result" {
		t.Fatalf("types=%s %s", parts[0].Type, parts[1].Type)
	}
}
