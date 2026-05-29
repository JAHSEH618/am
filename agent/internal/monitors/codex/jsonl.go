// Codex JSONL 扫描与解析。
//
// gz
package codex

import (
	"bufio"
	"encoding/json"
	"os"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// jsonlEnvelope 是 codex jsonl 的统一信封：所有行都有 timestamp/type/payload。
type jsonlEnvelope struct {
	Timestamp string          `json:"timestamp"`
	Type      string          `json:"type"`
	Payload   json.RawMessage `json:"payload"`
}

type sessionMetaPayload struct {
	ID            string          `json:"id"`
	CWD           string          `json:"cwd"`
	CLIVersion    string          `json:"cli_version"`
	AgentNickname string          `json:"agent_nickname"`
	Source        json.RawMessage `json:"source"`
}

type turnContextPayload struct {
	CWD   string `json:"cwd"`
	Model string `json:"model"`
	Git   struct {
		Branch string `json:"branch"`
	} `json:"git"`
}

type responseItemPayload struct {
	Type      string          `json:"type"`
	Name      string          `json:"name"`
	Role      string          `json:"role"`
	Arguments string          `json:"arguments"`
	Output    json.RawMessage `json:"output"`
	Content   []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	} `json:"content"`
}

type eventPayload struct {
	Type             string `json:"type"`
	Message          string `json:"message"`
	Images           []json.RawMessage `json:"images"`
	LocalImages      []json.RawMessage `json:"local_images"`
	LastAgentMessage string `json:"last_agent_message"`
	Info             *struct {
		TotalTokenUsage struct {
			InputTokens       int `json:"input_tokens"`
			CachedInputTokens int `json:"cached_input_tokens"`
			OutputTokens      int `json:"output_tokens"`
			ReasoningOutput   int `json:"reasoning_output_tokens"`
		} `json:"total_token_usage"`
	} `json:"info"`
}

type indexEntry struct {
	ID         string `json:"id"`
	ThreadName string `json:"thread_name"`
}

// loadSessionNames 读 ~/.codex/session_index.jsonl，把 session_id → thread_name 映射出来。
//
// 文件不存在 / 解析失败都返回空 map，调用方按 nil 处理即可。
func loadSessionNames(path string) map[string]string {
	names := make(map[string]string)
	f, err := os.Open(path)
	if err != nil {
		return names
	}
	defer func() { _ = f.Close() }()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64<<10), 1<<20)
	for sc.Scan() {
		var e indexEntry
		if err := json.Unmarshal(sc.Bytes(), &e); err == nil && e.ID != "" && e.ThreadName != "" {
			names[e.ID] = e.ThreadName
		}
	}
	return names
}

func parseFile(path string) (*parsedSession, int64, error) {
	ps := &parsedSession{JSONLPath: path}
	consumed, _, err := common.ScanJSONL(path, 0, 4<<20, makeLineHandler(ps))
	if err != nil {
		return nil, 0, err
	}
	return ps, consumed, nil
}

func parseFileIncremental(path string, offset int64, base *parsedSession) (*parsedSession, int64, error) {
	ps := base.clone()
	consumed, _, err := common.ScanJSONL(path, offset, 4<<20, makeLineHandler(ps))
	if err != nil {
		return nil, 0, err
	}
	return ps, consumed, nil
}

// makeLineHandler 与 claude 类似，merge 单行到累计 ps。
//
// codex 的 BubbleStatus 推断比 claude 简单：直接看最后一行的 type/role：
//
//	最后一行 user 消息            → thinking（人刚发完，模型在想）
//	最后一行 assistant 消息       → waiting_for_user（模型回完，等人）
//	最后一行 function_call        → executing_tool
//	最后一行 function_call_output → processing_tool_result
func makeLineHandler(ps *parsedSession) common.ScanLine {
	return func(line []byte, _ int64) bool {
		var env jsonlEnvelope
		if err := json.Unmarshal(line, &env); err != nil {
			return true
		}
		ts, _ := time.Parse(time.RFC3339Nano, env.Timestamp)
		if !ts.IsZero() {
			if ps.StartedAt.IsZero() {
				ps.StartedAt = ts
			}
			ps.LastActivity = ts
		}

		switch env.Type {
		case "session_meta":
			var meta sessionMetaPayload
			if err := json.Unmarshal(env.Payload, &meta); err != nil {
				return true
			}
			if meta.ID != "" {
				ps.SessionID = meta.ID
			}
			if meta.CWD != "" {
				ps.CWD = meta.CWD
			}
			if meta.CLIVersion != "" {
				ps.Version = meta.CLIVersion
			}
			if meta.AgentNickname != "" || strings.Contains(string(meta.Source), "\"subagent\"") {
				ps.IsSidechain = true
			}
			if parent := parseCodexParentThreadID(meta.Source); parent != "" {
				ps.ParentSessionID = parent
			}

		case "turn_context":
			var ctx turnContextPayload
			if err := json.Unmarshal(env.Payload, &ctx); err != nil {
				return true
			}
			if ctx.CWD != "" {
				ps.CWD = ctx.CWD
			}
			if ctx.Model != "" {
				ps.Model = ctx.Model
			}
			if ctx.Git.Branch != "" {
				ps.GitBranch = ctx.Git.Branch
			}

		case "response_item":
			var item responseItemPayload
			if err := json.Unmarshal(env.Payload, &item); err != nil {
				return true
			}
			handleResponseItem(ps, item, ts)

		case "event_msg":
			var ev eventPayload
			if err := json.Unmarshal(env.Payload, &ev); err != nil {
				return true
			}
			handleEvent(ps, ev, ts)
		}
		return true
	}
}

func handleResponseItem(ps *parsedSession, item responseItemPayload, ts time.Time) {
	switch item.Type {
	case "message":
		// user 侧：Codex 新版 JSONL 里除 input_text 外常见 type=text / 技能信封等；只认 input_text 会丢
		// 「$skill-creator」等片段，导致服务端 slash 统计永远为 0（UI 仍可能由客户端拼出完整气泡）。
		text := joinMessageItemText(item.Role, item.Content)
		switch item.Role {
		case "user":
			ps.UserMessages++
			ps.BubbleStatus = common.BubbleThinking
			ps.CurrentTool = ""
			if text != "" {
				parts := partsFromUserText(text)
				if len(parts) > 0 {
					ps.appendMessageParts("user", "", ts, parts)
				} else {
					ps.appendMessage("user", text, "", ts)
				}
			}
		case "assistant":
			ps.AssistantMessages++
			ps.BubbleStatus = common.BubbleWaitingUser
			ps.CurrentTool = ""
			if text != "" {
				ps.appendMessage("assistant", text, "", ts)
			}
		}
	case "function_call":
		name := common.NormalizeToolName(item.Name)
		ps.appendTool(name, ts)
		ps.BubbleStatus = common.BubbleExecTool
		ps.CurrentTool = name
		// codex 把工具入参放在 arguments 里（JSON string），保留原文便于复盘。
		ps.appendMessage("tool", item.Arguments, name, ts)
	case "function_call_output":
		ps.BubbleStatus = common.BubbleProcessing
		if len(item.Output) > 0 {
			parts := parseFunctionCallOutput(item.Output)
			if len(parts) > 0 {
				ps.appendMessageParts("tool", "", ts, parts)
			}
		}
	}
}

func handleEvent(ps *parsedSession, ev eventPayload, ts time.Time) {
	switch ev.Type {
	case "user_message":
		ps.BubbleStatus = common.BubbleThinking
		if strings.TrimSpace(ev.Message) != "" {
			ps.UserMessages++
			parts := []monitor.ContentPart{{
				Type: "text", Text: common.Truncate(ev.Message, maxMessageText), SortOrder: 0,
			}}
			ps.appendMessageParts("user", "", ts, parts)
			common.AppendActivityDelta(&ps.ActivityDeltas, ts, "user:"+ts.Format(time.RFC3339Nano),
				common.ActivitySourceUserTurn, 0, 0, 1)
		}
	case "agent_message":
		ps.BubbleStatus = common.BubbleWaitingUser
	case "task_complete":
		if strings.TrimSpace(ev.LastAgentMessage) != "" {
			ps.BubbleStatus = common.BubbleWaitingUser
		}
	case "token_count":
		if ev.Info != nil && !ts.IsZero() {
			u := ev.Info.TotalTokenUsage
			newIn := int64(u.InputTokens)
			newOut := int64(u.OutputTokens + u.ReasoningOutput)
			// total_token_usage 是会话累计值；Codex 在自动压缩 / 上下文重置后会把它回退到更小的基数。
			// 此时 new - prev 为负，若原样上报，服务端求和会让「今日 Token」变负、前端越界。
			// 计数回退视作一次重置：本次增量裁 0，但基线仍推进到新值，压缩后继续累积仍按新基线正确计。
			dIn := newIn - ps.InputTokens
			if dIn < 0 {
				dIn = 0
			}
			dOut := newOut - ps.OutputTokens
			if dOut < 0 {
				dOut = 0
			}
			if dIn != 0 || dOut != 0 {
				ref := "token_count:" + ts.Format(time.RFC3339Nano)
				common.AppendActivityDelta(&ps.ActivityDeltas, ts, ref, common.ActivitySourceTokenCount, dIn, dOut, 0)
			}
			ps.InputTokens = newIn
			ps.CacheRead = int64(u.CachedInputTokens)
			ps.OutputTokens = newOut
		}
	}
}

func joinMessageItemText(role string, content []struct {
	Type string `json:"type"`
	Text string `json:"text"`
}) string {
	roleLower := strings.ToLower(strings.TrimSpace(role))
	isUser := roleLower == "user"
	var parts []string
	for _, b := range content {
		if strings.TrimSpace(b.Text) == "" {
			continue
		}
		t := strings.ToLower(strings.TrimSpace(b.Type))
		if isUser {
			// user：合并所有带 text 的块（与旧版 input_text 兼容，并覆盖 type=text 等）
			parts = append(parts, b.Text)
			continue
		}
		if t == "output_text" || t == "text" {
			parts = append(parts, b.Text)
		}
	}
	return strings.TrimSpace(strings.Join(parts, "\n"))
}

func parseCodexParentThreadID(source json.RawMessage) string {
	if len(source) == 0 {
		return ""
	}
	var src struct {
		Subagent struct {
			ThreadSpawn struct {
				ParentThreadID string `json:"parent_thread_id"`
			} `json:"thread_spawn"`
		} `json:"subagent"`
	}
	if err := json.Unmarshal(source, &src); err != nil {
		return ""
	}
	return strings.TrimSpace(src.Subagent.ThreadSpawn.ParentThreadID)
}
