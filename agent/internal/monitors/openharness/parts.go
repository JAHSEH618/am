// OpenHarness session JSON 消息 → monitor.ContentPart。
// gz
package openharness

import (
	"encoding/json"
	"strings"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const maxOpenHarnessTextBytes = 512 * 1024

func extractMessageParts(role string, items []rawContent) []monitor.ContentPart {
	var parts []monitor.ContentPart
	order := 0
	add := func(p monitor.ContentPart) {
		p.SortOrder = order
		order++
		parts = append(parts, p)
	}
	for _, it := range items {
		switch it.Type {
		case "text":
			if strings.TrimSpace(it.Text) != "" {
				add(monitor.ContentPart{Type: "text", Text: truncateOpenHarness(it.Text)})
			}
		case "tool_use":
			args := ""
			if len(it.Input) > 0 {
				args = string(it.Input)
			}
			add(monitor.ContentPart{
				Type:          "tool_call",
				ToolName:      common.NormalizeToolName(it.Name),
				ArgumentsJSON: truncateOpenHarness(args),
			})
		case "tool_result":
			body := toolResultText(it)
			if body != "" {
				add(monitor.ContentPart{
					Type:     "tool_result",
					ToolName: common.NormalizeToolName(it.Name),
					Text:     truncateOpenHarness(body),
				})
			}
		}
	}
	if len(parts) == 0 && role == "user" {
		return nil
	}
	return parts
}

func toolResultText(it rawContent) string {
	if strings.TrimSpace(it.Text) != "" {
		return it.Text
	}
	if len(it.Content) == 0 {
		return ""
	}
	if it.Content[0] == '"' {
		var s string
		if json.Unmarshal(it.Content, &s) == nil {
			return s
		}
	}
	return string(it.Content)
}

func truncateOpenHarness(s string) string {
	if len(s) <= maxOpenHarnessTextBytes {
		return s
	}
	return s[:maxOpenHarnessTextBytes] + "…"
}
