// Hermes SQLite 消息 → monitor.ContentPart。
// gz
package hermes

import (
	"encoding/json"
	"strings"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const maxHermesTextBytes = 512 * 1024

func buildMessageParts(role, content, toolName string) []monitor.ContentPart {
	content = strings.TrimSpace(content)
	if content == "" && toolName == "" {
		return nil
	}
	switch role {
	case "tool":
		return []monitor.ContentPart{{
			Type:     "tool_result",
			ToolName: common.NormalizeToolName(toolName),
			Text:     truncateHermesText(content),
			SortOrder: 0,
		}}
	case "assistant":
		// assistant 消息里可能是工具 JSON；能解析为 tool_use 则拆 part。
		if parts := parseAssistantToolParts(content); len(parts) > 0 {
			return parts
		}
		return []monitor.ContentPart{{Type: "text", Text: truncateHermesText(content), SortOrder: 0}}
	default:
		return []monitor.ContentPart{{Type: "text", Text: truncateHermesText(content), SortOrder: 0}}
	}
}

func parseAssistantToolParts(content string) []monitor.ContentPart {
	var items []struct {
		Type  string          `json:"type"`
		Name  string          `json:"name"`
		Input json.RawMessage `json:"input"`
		Text  string          `json:"text"`
	}
	if err := json.Unmarshal([]byte(content), &items); err != nil {
		var one struct {
			Type  string          `json:"type"`
			Name  string          `json:"name"`
			Input json.RawMessage `json:"input"`
		}
		if err2 := json.Unmarshal([]byte(content), &one); err2 != nil || one.Type != "tool_use" {
			return nil
		}
		items = []struct {
			Type  string          `json:"type"`
			Name  string          `json:"name"`
			Input json.RawMessage `json:"input"`
			Text  string          `json:"text"`
		}{{Type: one.Type, Name: one.Name, Input: one.Input}}
	}
	var parts []monitor.ContentPart
	order := 0
	for _, it := range items {
		switch it.Type {
		case "tool_use":
			args := string(it.Input)
			if args == "" {
				args = it.Text
			}
			parts = append(parts, monitor.ContentPart{
				Type:          "tool_call",
				ToolName:      common.NormalizeToolName(it.Name),
				ArgumentsJSON: truncateHermesText(args),
				SortOrder:     order,
			})
			order++
		case "text":
			if strings.TrimSpace(it.Text) != "" {
				parts = append(parts, monitor.ContentPart{
					Type:      "text",
					Text:      truncateHermesText(it.Text),
					SortOrder: order,
				})
				order++
			}
		}
	}
	return parts
}

func truncateHermesText(s string) string {
	if len(s) <= maxHermesTextBytes {
		return s
	}
	return s[:maxHermesTextBytes] + "…"
}
