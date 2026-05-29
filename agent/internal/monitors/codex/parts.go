// Codex 消息 part 组装。
// gz
package codex

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const envCtxOpen = "<environment_context>"
const envCtxClose = "</environment_context>"

func splitEnvironmentContext(text string) (env, rest string) {
	text = strings.TrimSpace(text)
	if !strings.Contains(text, envCtxOpen) {
		return "", text
	}
	start := strings.Index(text, envCtxOpen)
	end := strings.Index(text, envCtxClose)
	if end < 0 {
		return strings.TrimSpace(text[start:]), ""
	}
	env = strings.TrimSpace(text[start : end+len(envCtxClose)])
	rest = strings.TrimSpace(text[end+len(envCtxClose):])
	return env, rest
}

func (p *parsedSession) appendMessageParts(role, toolName string, ts time.Time, parts []monitor.ContentPart) {
	if len(parts) == 0 {
		return
	}
	ext := common.SyntheticMessageIDByTime(p.SessionID, ts, role, monitor.FlattenParts(parts))
	msg := monitor.Message{
		ExternalMessageID: ext,
		Role:              role,
		ContentParts:      parts,
		ToolName:          toolName,
		Timestamp:         monitor.LocalTime(ts),
	}
	monitor.FinalizeMessage(&msg)
	p.RecentMessages = append(p.RecentMessages, msg)
	if len(p.RecentMessages) > maxRecentMessages*2 {
		p.RecentMessages = p.RecentMessages[len(p.RecentMessages)-maxRecentMessages:]
	}
}

func partsFromUserText(text string) []monitor.ContentPart {
	env, rest := splitEnvironmentContext(text)
	var parts []monitor.ContentPart
	order := 0
	if env != "" {
		parts = append(parts, monitor.ContentPart{
			Type: "system_context", Text: env, SortOrder: order,
		})
		order++
	}
	if strings.TrimSpace(rest) != "" {
		parts = append(parts, monitor.ContentPart{
			Type: "text", Text: common.Truncate(rest, maxMessageText), SortOrder: order,
		})
	}
	return parts
}

// parseFunctionCallOutput 解析 function_call_output 的 output 字段（string 或 JSON 数组）。
func parseFunctionCallOutput(raw json.RawMessage) []monitor.ContentPart {
	if len(raw) == 0 {
		return nil
	}
	var parts []monitor.ContentPart
	order := 0

	if raw[0] == '"' {
		var s string
		if json.Unmarshal(raw, &s) == nil && strings.TrimSpace(s) != "" {
			parts = append(parts, monitor.ContentPart{
				Type: "tool_result", Text: common.Truncate(s, maxMessageText), SortOrder: order,
			})
		}
		return parts
	}
	if raw[0] != '[' {
		return parts
	}
	var items []map[string]json.RawMessage
	if json.Unmarshal(raw, &items) != nil {
		return parts
	}
	for _, item := range items {
		var typ string
		_ = json.Unmarshal(item["type"], &typ)
		switch typ {
		case "input_image", "image":
			var url string
			_ = json.Unmarshal(item["image_url"], &url)
			if url == "" {
				_ = json.Unmarshal(item["url"], &url)
			}
			if rawImg := decodeDataURL(url); len(rawImg) > 0 {
				if b64, err := monitor.GzipBase64(rawImg); err == nil {
					parts = append(parts, monitor.ContentPart{
						Type: "image", Mime: "image/jpeg", BlobGzipBase64: b64, SortOrder: order,
					})
					order++
				}
			}
		default:
			var t string
			_ = json.Unmarshal(item["text"], &t)
			if strings.TrimSpace(t) != "" {
				parts = append(parts, monitor.ContentPart{
					Type: "tool_result", Text: common.Truncate(t, maxMessageText), SortOrder: order,
				})
				order++
			}
		}
	}
	return parts
}

func decodeDataURL(url string) []byte {
	const prefix = "base64,"
	idx := strings.Index(url, prefix)
	if idx < 0 {
		return nil
	}
	b, err := base64.StdEncoding.DecodeString(url[idx+len(prefix):])
	if err != nil {
		return nil
	}
	return b
}
