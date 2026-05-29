// Claude message content → monitor.ContentPart 转换。
// gz
package claude

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

type jsonlImageSource struct {
	Type string `json:"type"`
	Data string `json:"data"`
	MediaType string `json:"media_type"`
}

// extractParts 从 message content 解析结构化 part 列表。
func extractParts(contents []jsonlContent, rawContent json.RawMessage, role string) []monitor.ContentPart {
	var parts []monitor.ContentPart
	order := 0

	addText := func(typ, text string) {
		text = strings.TrimSpace(text)
		if text == "" {
			return
		}
		parts = append(parts, monitor.ContentPart{
			Type:      typ,
			Text:      common.Truncate(text, maxMessageText),
			SortOrder: order,
		})
		order++
	}

	for _, c := range contents {
		switch c.Type {
		case "text":
			addText("text", c.Text)
		case "thinking":
			addText("thinking", c.Text)
		case "tool_use":
			args := ""
			if len(c.Input) > 0 {
				args = string(c.Input)
			}
			parts = append(parts, monitor.ContentPart{
				Type:          "tool_call",
				ToolName:      common.NormalizeToolName(c.Name),
				ArgumentsJSON: common.Truncate(args, maxMessageText),
				SortOrder:     order,
			})
			order++
		case "tool_result":
			if c.Text != "" {
				addText("tool_result", c.Text)
			}
		case "image":
			if c.Source != nil && c.Source.Data != "" {
				raw, err := base64.StdEncoding.DecodeString(c.Source.Data)
				if err == nil && len(raw) > 0 {
					b64, err := monitor.GzipBase64(raw)
					if err == nil {
						mime := c.Source.MediaType
						if mime == "" {
							mime = "image/png"
						}
						parts = append(parts, monitor.ContentPart{
							Type:           "image",
							Mime:           mime,
							BlobGzipBase64: b64,
							SortOrder:      order,
						})
						order++
					}
				}
			}
		}
	}

	if len(parts) == 0 && len(rawContent) > 0 {
		text, _ := decodeContent(rawContent)
		if text != "" {
			addText("text", text)
		}
	}
	_ = role
	return parts
}

func (p *parsedSession) appendMessageWithParts(role, toolName, externalID string, ts time.Time, in, out int, parts []monitor.ContentPart) {
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
	p.RecentMessages = append(p.RecentMessages, msg)
	if len(p.RecentMessages) > maxRecentMessages*2 {
		p.RecentMessages = p.RecentMessages[len(p.RecentMessages)-maxRecentMessages:]
	}
}
