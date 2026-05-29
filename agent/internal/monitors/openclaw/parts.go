// OpenClaw jsonl 消息 → monitor.ContentPart。
// gz
package openclaw

import (
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

func extractMessageParts(role string, text string, contents []jsonlContent, toolName string) []monitor.ContentPart {
	if role == "toolResult" {
		body := strings.TrimSpace(text)
		if body == "" {
			for _, c := range contents {
				if c.Type == "text" && strings.TrimSpace(c.Text) != "" {
					body = c.Text
					break
				}
			}
		}
		if body == "" {
			return nil
		}
		return []monitor.ContentPart{{
			Type:      "tool_result",
			ToolName:  common.NormalizeToolName(toolName),
			Text:      common.Truncate(body, maxMessageText),
			SortOrder: 0,
		}}
	}

	var parts []monitor.ContentPart
	order := 0
	add := func(p monitor.ContentPart) {
		p.SortOrder = order
		order++
		parts = append(parts, p)
	}

	if strings.TrimSpace(text) != "" {
		add(monitor.ContentPart{Type: "text", Text: common.Truncate(text, maxMessageText)})
	}
	for _, c := range contents {
		switch c.Type {
		case "toolCall":
			args := ""
			if c.Arguments != nil {
				args = string(c.Arguments)
			}
			add(monitor.ContentPart{
				Type:          "tool_call",
				ToolName:      common.NormalizeToolName(c.Name),
				ArgumentsJSON: common.Truncate(args, maxMessageText),
			})
		case "text":
			if c.Text != "" && strings.TrimSpace(text) == "" {
				add(monitor.ContentPart{Type: "text", Text: common.Truncate(c.Text, maxMessageText)})
			}
		}
	}
	return parts
}

func appendMessageWithParts(ps *parsedSession, role, toolName, externalID string, ts time.Time, in, out int, parts []monitor.ContentPart) {
	if len(parts) == 0 {
		return
	}
	msg := monitor.Message{
		ExternalMessageID: externalID,
		Role:              role,
		ContentParts:      parts,
		ToolName:          toolName,
		Timestamp:         monitor.LocalTime(ts),
		InputTokens:       in,
		OutputTokens:      out,
	}
	monitor.FinalizeMessage(&msg)
	ps.RecentMessages = append(ps.RecentMessages, msg)
	if len(ps.RecentMessages) > maxRecentMessages*2 {
		ps.RecentMessages = ps.RecentMessages[len(ps.RecentMessages)-maxRecentMessages:]
	}
}
