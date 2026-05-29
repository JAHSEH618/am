// OpenClaw jsonl 解析。
//
// 协议（节选自真实 ~/.openclaw/agents/main/sessions/<uuid>.jsonl，2026-05）：
//
//	{"type":"session","version":3,"id":"<uuid>","timestamp":"...","cwd":"/"}                         //  0  会话头（首行）
//	{"type":"message","id":"...","timestamp":"...","message":{                                       //     用户消息
//	    "role":"user","content":[{"type":"text","text":"..."}]
//	}}
//	{"type":"message","id":"...","timestamp":"...","message":{                                       //     assistant + 工具
//	    "role":"assistant","model":"gpt-5.4",
//	    "content":[{"type":"text","text":"..."},
//	               {"type":"toolCall","id":"call_xx","name":"read","arguments":{...},"partialJson":"..."}],
//	    "usage":{"input":9738,"output":214,"cacheRead":3584,"cacheWrite":0,"totalTokens":13536,
//	             "cost":{"input":0.024,"output":0.003,"cacheRead":0.0008,"cacheWrite":0,"total":0.028}}
//	}}
//	{"type":"message","id":"...","timestamp":"...","message":{                                       //     工具结果
//	    "role":"toolResult","toolCallId":"call_xx","toolName":"read",
//	    "content":[{"type":"text","text":"..."}]
//	}}
//	{"type":"custom","customType":"model-snapshot","data":{"modelId":"gpt-5.4",...}}                 //     模型切换
//	{"type":"thinking_level_change","thinkingLevel":"off"}                                           //     状态线索（暂时不消费）
//
// 与 Claude jsonl 的差异：
//
//	role        Claude 的 user/assistant/tool_result（嵌在 content）vs OpenClaw 的 user/assistant/toolResult（独立行）
//	tool        Claude content type=tool_use      vs OpenClaw content type=toolCall（驼峰）
//	usage       Claude input_tokens/output_tokens vs OpenClaw input/output/cacheRead/cacheWrite/totalTokens
//	cost        Claude 走 costUSD 顶层字段        vs OpenClaw 走 message.usage.cost.total
// gz
package openclaw

import (
	"encoding/json"
	"path/filepath"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// jsonlEntry 是单行通用信封。Message / Data 用 RawMessage 延迟反序列化。
type jsonlEntry struct {
	Type       string          `json:"type"`
	CustomType string          `json:"customType"`
	ID         string          `json:"id"`
	ParentID   string          `json:"parentId"`
	Timestamp  string          `json:"timestamp"`
	Version    int             `json:"version"`
	CWD        string          `json:"cwd"`
	RawMessage json.RawMessage `json:"message"`
	RawData    json.RawMessage `json:"data"`
}

// jsonlMessage 是 type=message 行的内部 message 对象。
//
// toolResult 行的 toolName 在顶层（不在 content 数组里），所以这里也要单独抓。
type jsonlMessage struct {
	Role       string          `json:"role"`
	Model      string          `json:"model"`
	ToolName   string          `json:"toolName"` // 仅 toolResult 行有
	ToolCallID string          `json:"toolCallId"`
	RawContent json.RawMessage `json:"content"`
	Usage      *jsonlUsage     `json:"usage"`
}

// jsonlUsage 与 Claude 的字段名完全不同：input/output/cacheRead/cacheWrite，cost 嵌套独立对象。
type jsonlUsage struct {
	Input      int      `json:"input"`
	Output     int      `json:"output"`
	CacheRead  int      `json:"cacheRead"`
	CacheWrite int      `json:"cacheWrite"`
	Cost       *costObj `json:"cost"`
}

type costObj struct {
	Total float64 `json:"total"`
}

// jsonlContent 同时覆盖 text 段和 toolCall 段。
type jsonlContent struct {
	Type      string          `json:"type"` // text / toolCall
	Text      string          `json:"text"`
	Name      string          `json:"name"` // toolCall.name
	ToolCall  string          `json:"id"`   // toolCall.id（仅 type=toolCall 时有意义）
	Arguments json.RawMessage `json:"arguments"`
}

// modelSnapshot 是 type=custom + customType=model-snapshot 的 data 内容。
type modelSnapshot struct {
	ModelID string `json:"modelId"`
}

// parseFile 全量重新解析 path，返回新会话 + 已消耗字节数。
func parseFile(path, agentName string) (*parsedSession, int64, error) {
	ps := newSessionFromPath(path, agentName)
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

func newSessionFromPath(path, agentName string) *parsedSession {
	return &parsedSession{
		SessionID: strings.TrimSuffix(filepath.Base(path), ".jsonl"),
		JSONLPath: path,
		AgentName: agentName,
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

		switch e.Type {
		case "session":
			if ps.CWD == "" && e.CWD != "" && e.CWD != "/" {
				ps.CWD = e.CWD
			}
			if ps.SessionID == "" && e.ID != "" {
				ps.SessionID = e.ID
			}
			if pid := strings.TrimSpace(e.ParentID); pid != "" {
				ps.ParentSessionID = pid
			}
			if ps.StartedAt.IsZero() && !ts.IsZero() {
				ps.StartedAt = ts
				ps.LastActivity = ts
			}
			return true

		case "custom":
			if e.CustomType == "model-snapshot" && len(e.RawData) > 0 {
				var ms modelSnapshot
				if err := json.Unmarshal(e.RawData, &ms); err == nil && ms.ModelID != "" {
					ps.Model = ms.ModelID
				}
			}
			return true

		case "thinking_level_change":
			// 暂不消费：状态由消息流末态 + ResolveActivity 决定。
			return true

		case "message":
			// 走下面的 message 解析
		default:
			return true
		}

		// type=message 解析
		if len(e.RawMessage) == 0 || e.RawMessage[0] == 'n' {
			return true
		}
		var msg jsonlMessage
		if err := json.Unmarshal(e.RawMessage, &msg); err != nil {
			return true
		}

		// usage 累加（assistant 才有）。OpenClaw 的 usage.cost.total v2.0 起不再消费——
		// 平台已下线成本视角，只记录 token。
		if msg.Usage != nil {
			ps.InputTokens += int64(msg.Usage.Input)
			ps.OutputTokens += int64(msg.Usage.Output)
			ps.CacheRead += int64(msg.Usage.CacheRead)
			ps.CacheCreate += int64(msg.Usage.CacheWrite)
		}
		if msg.Model != "" {
			ps.Model = msg.Model
		}

		text, contents := decodeContent(msg.RawContent)
		parts := extractMessageParts(msg.Role, text, contents, msg.ToolName)

		switch msg.Role {
		case "user":
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
				appendMessageWithParts(ps, "user", "", e.ID, ts, 0, 0, parts)
			}
			common.AppendActivityDelta(&ps.ActivityDeltas, ts, e.ID+":user", common.ActivitySourceUserTurn, 0, 0, 1)

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
				if c.Type == "toolCall" {
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
			if len(parts) > 0 {
				inTok, outTok := 0, 0
				if msg.Usage != nil {
					inTok, outTok = msg.Usage.Input, msg.Usage.Output
				}
				appendMessageWithParts(ps, "assistant", "", e.ID, ts, inTok, outTok, parts)
				common.AppendActivityDelta(&ps.ActivityDeltas, ts, e.ID, common.ActivitySourceAssistantTurn,
					int64(inTok), int64(outTok), 1)
			}

		case "toolResult":
			ps.BubbleStatus = common.BubbleProcessing
			if !ts.IsZero() {
				ps.LastActivity = ts
			}
			toolName := common.NormalizeToolName(msg.ToolName)
			if len(parts) > 0 {
				appendMessageWithParts(ps, "tool", toolName, e.ID, ts, 0, 0, parts)
			}
		}
		return true
	}
}

// decodeContent 把 message.content 反序列化成数组（OpenClaw 总是数组形式）+ 抽取首个 text 段。
func decodeContent(raw json.RawMessage) (text string, contents []jsonlContent) {
	if len(raw) == 0 {
		return "", nil
	}
	if raw[0] != '[' {
		// 罕见：旧版本可能直接是字符串
		_ = json.Unmarshal(raw, &text)
		return text, nil
	}
	_ = json.Unmarshal(raw, &contents)
	for _, c := range contents {
		if c.Type == "text" && c.Text != "" {
			text = c.Text
			break
		}
	}
	return text, contents
}
