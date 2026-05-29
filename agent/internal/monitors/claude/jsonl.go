// Claude Code JSONL 会话与消息扫描。
//
// gz
package claude

import (
	"encoding/json"
	"path/filepath"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// jsonlEntry 是单行通用信封。Message 用 RawMessage 延迟反序列化，
// 避免 system / summary 这种没 message 字段的行白白分配嵌套结构。
type jsonlEntry struct {
	Type        string          `json:"type"`
	Subtype     string          `json:"subtype"`
	UUID        string          `json:"uuid"`
	SessionID   string          `json:"sessionId"`
	CWD         string          `json:"cwd"`
	Version     string          `json:"version"`
	GitBranch   string          `json:"gitBranch"`
	Timestamp   string          `json:"timestamp"`
	IsSidechain bool            `json:"isSidechain"`
	RawMessage  json.RawMessage `json:"message"`
}

type jsonlMessage struct {
	Role       string          `json:"role"`
	Model      string          `json:"model"`
	RawContent json.RawMessage `json:"content"`
	Usage      *jsonlUsage     `json:"usage"`
}

type jsonlUsage struct {
	InputTokens         int `json:"input_tokens"`
	OutputTokens        int `json:"output_tokens"`
	CacheCreationTokens int `json:"cache_creation_input_tokens"`
	CacheReadTokens     int `json:"cache_read_input_tokens"`
}

type jsonlContent struct {
	Type      string          `json:"type"`
	Text      string          `json:"text"`
	Name      string          `json:"name"` // tool_use
	ToolUseID string          `json:"tool_use_id"`
	Input     json.RawMessage `json:"input"`
	Source    *jsonlImageSource `json:"source"`
	IsError   bool            `json:"is_error"`
}

// parseFile 全量重新解析 path，返回新会话 + 已消耗字节数。
func parseFile(path string) (*parsedSession, int64, error) {
	ps := newSessionFromPath(path)
	consumed, _, err := common.ScanJSONL(path, 0, 4<<20, makeLineHandler(ps))
	if err != nil {
		return nil, 0, err
	}
	return ps, consumed, nil
}

// parseFileIncremental 在已缓存的 base 上从 offset 继续读，merge 新行。
func parseFileIncremental(path string, offset int64, base *parsedSession) (*parsedSession, int64, error) {
	ps := base.clone()
	consumed, _, err := common.ScanJSONL(path, offset, 4<<20, makeLineHandler(ps))
	if err != nil {
		return nil, 0, err
	}
	return ps, consumed, nil
}

func newSessionFromPath(path string) *parsedSession {
	return &parsedSession{
		SessionID:      strings.TrimSuffix(filepath.Base(path), ".jsonl"),
		JSONLPath:      path,
		IsSubagentFile: strings.Contains(filepath.ToSlash(path), "/subagents/"),
	}
}

// makeLineHandler 返回一个 ScanLine 闭包，把每行 JSON merge 进 ps。
//
// 关键不变量：
//
//	BubbleStatus 是流末态的 raw 状态（thinking / waiting_for_user / executing_tool / processing_tool_result）。
//	平台 10 态由 Provider 在 Snapshot 阶段调 common.ResolveActivity 合成，不在这里固化。
func makeLineHandler(ps *parsedSession) common.ScanLine {
	return func(line []byte, _ int64) bool {
		var e jsonlEntry
		if err := json.Unmarshal(line, &e); err != nil {
			return true
		}

		ts, _ := time.Parse(time.RFC3339Nano, e.Timestamp)

		if e.IsSidechain {
			ps.IsSidechain = true
		}
		if ps.IsSubagentFile && e.SessionID != "" {
			ps.ParentSessionID = e.SessionID
		}

		// 元信息只在第一次见到时补齐，避免被后续行覆盖（CWD 在 Claude 里偶尔会变）。
		if ps.CWD == "" && e.CWD != "" {
			ps.CWD = e.CWD
		}
		if ps.GitBranch == "" && e.GitBranch != "" {
			ps.GitBranch = e.GitBranch
		}
		if ps.Version == "" && e.Version != "" {
			ps.Version = e.Version
		}

		if e.Type == "summary" {
			ps.LastSummaryAt = ts
			return true
		}
		if e.Type == "system" && e.Subtype == "compact_boundary" {
			ps.LastSummaryAt = ts
			return true
		}
		if e.Type != "user" && e.Type != "assistant" {
			return true
		}

		var msg jsonlMessage
		if len(e.RawMessage) == 0 || e.RawMessage[0] == 'n' {
			return true
		}
		if err := json.Unmarshal(e.RawMessage, &msg); err != nil {
			return true
		}

		// 累计 token（assistant.usage 才有）。
		if msg.Usage != nil {
			ps.InputTokens += int64(msg.Usage.InputTokens)
			ps.OutputTokens += int64(msg.Usage.OutputTokens)
			ps.CacheCreate += int64(msg.Usage.CacheCreationTokens)
			ps.CacheRead += int64(msg.Usage.CacheReadTokens)
		}
		if msg.Model != "" {
			ps.Model = msg.Model
		}

		text, contents := decodeContent(msg.RawContent)
		parts := extractParts(contents, msg.RawContent, e.Type)

		switch e.Type {
		case "user":
			if isToolResult(contents) {
				ps.BubbleStatus = common.BubbleProcessing
				if len(parts) > 0 {
					ps.appendMessageWithParts("tool", "", e.UUID, ts, 0, 0, parts)
				}
				return true
			}
			ps.UserMessages++
			ps.BubbleStatus = common.BubbleThinking
			ps.CurrentTool = ""
			if !ts.IsZero() {
				ps.LastActivity = ts
				if ps.StartedAt.IsZero() {
					ps.StartedAt = ts
				}
			}
			if len(parts) > 0 {
				ps.appendMessageWithParts("user", "", e.UUID, ts, 0, 0, parts)
			} else if text != "" {
				ps.appendMessage("user", text, "", e.UUID, ts, 0, 0)
			}
			common.AppendActivityDelta(&ps.ActivityDeltas, ts, e.UUID+":user", common.ActivitySourceUserTurn, 0, 0, 1)

		case "assistant":
			ps.AssistantMessages++
			if !ts.IsZero() {
				ps.LastActivity = ts
				if ps.StartedAt.IsZero() {
					ps.StartedAt = ts
				}
			}
			hasTool := false
			for _, c := range contents {
				if c.Type == "tool_use" {
					hasTool = true
					name := common.NormalizeToolName(c.Name)
					ps.appendTool(name, ts)
					ps.CurrentTool = name
				}
			}
			if hasTool {
				ps.BubbleStatus = common.BubbleExecTool
			} else {
				ps.BubbleStatus = common.BubbleWaitingUser
				ps.CurrentTool = ""
			}
			inTok, outTok := 0, 0
			if msg.Usage != nil {
				inTok, outTok = msg.Usage.InputTokens, msg.Usage.OutputTokens
			}
			if len(parts) > 0 {
				ps.appendMessageWithParts("assistant", "", e.UUID, ts, inTok, outTok, parts)
			} else if text != "" {
				ps.appendMessage("assistant", text, "", e.UUID, ts, inTok, outTok)
			}
			common.AppendActivityDelta(&ps.ActivityDeltas, ts, e.UUID, common.ActivitySourceAssistantTurn,
				int64(inTok), int64(outTok), 1)
		}
		return true
	}
}

func decodeContent(raw json.RawMessage) (text string, contents []jsonlContent) {
	if len(raw) == 0 {
		return "", nil
	}
	switch raw[0] {
	case '"':
		_ = json.Unmarshal(raw, &text)
	case '[':
		_ = json.Unmarshal(raw, &contents)
		for _, c := range contents {
			if c.Type == "text" && c.Text != "" {
				text = c.Text
				break
			}
		}
	}
	return text, contents
}

func isToolResult(contents []jsonlContent) bool {
	for _, c := range contents {
		if c.Type == "tool_result" {
			return true
		}
	}
	return false
}

