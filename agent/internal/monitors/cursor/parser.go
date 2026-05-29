// Cursor composer 数据解析。
//
// gz
package cursor

import (
	"database/sql"
	"encoding/json"
	"net/url"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// 可读片段最大长度，避免把整个 read_file_v2 result（兆级）写入 message_text。
const (
	maxToolTextSnippet = 4000
	maxToolMessageSize = 8000

	// maxRecentTools 单 session 内存中保留的工具调用条数上限（达到 2× 触发回收到 1×）。
	// 与 codex/claude/openclaw 同步保持 20。
	maxRecentTools = 20

	// maxRecentMessages v2.7 起 40 → 1000，让员工首次安装时能上送完整历史。
	// 详见 codex/parsed.go 同名常量的注释。
	maxRecentMessages = 1000
)

// parsedSession 是单次从 state.vscdb 解析出的会话（尚未套用 activityTracker）。
type parsedSession struct {
	SessionID         string
	// ParentComposerID 非空表示 Cursor Task/subagent 子 composer，应归并到父会话上报。
	ParentComposerID     string
	// SubagentComposerIDs 父 composer 记录的子 composer UUID 列表（比 subagentInfo 更早可用）。
	SubagentComposerIDs  []string
	CWD               string
	UserMessages      int
	AssistantMessages int
	InputTokens       int64
	OutputTokens      int64
	StartedAt         time.Time
	LastActivity      time.Time
	BubbleStatus      string
	CurrentTool       string
	RecentTools       []monitor.Tool
	RecentMessages    []monitor.Message
	ActivityDeltas    []monitor.ActivityDelta
	// AgentToolOrders 父 composer 每次 Agent/Task 工具调用的 header 序号（与 subagentComposerIds 顺序对齐）。
	AgentToolOrders   []int
	IsWorktree        bool
	MainRepo          string
	Model             string
}

// buildToolText 把 toolFormer 的入参 + 结果 + 状态拼成对人类可读的 message 内容。
//
//	[args]      工具入参（优先 rawArgs，回退 params）
//	[result]    工具响应（截断到 maxToolTextSnippet）
//	[status]    工具状态（completed / error / running …）
//
// 总长度限制 maxToolMessageSize，避免把 read_file_v2 的整个文件塞进 ai_session_message.content_text。
func buildToolText(b bubbleData) string {
	t := b.ToolFormerData
	var sb strings.Builder
	if name := t.Name; name != "" {
		sb.WriteString("[tool] ")
		sb.WriteString(name)
		sb.WriteString("\n")
	}
	if t.Status != "" {
		sb.WriteString("[status] ")
		sb.WriteString(t.Status)
		sb.WriteString("\n")
	}
	args := strings.TrimSpace(t.RawArgs)
	if args == "" {
		args = strings.TrimSpace(t.Params)
	}
	if args != "" {
		sb.WriteString("[args]\n")
		sb.WriteString(truncate(args, maxToolTextSnippet))
		sb.WriteString("\n")
	}
	if t.Result != "" {
		sb.WriteString("[result]\n")
		sb.WriteString(truncate(t.Result, maxToolTextSnippet))
		sb.WriteString("\n")
	}
	if b.Text != "" {
		sb.WriteString("[note]\n")
		sb.WriteString(truncate(b.Text, maxToolTextSnippet))
	}
	out := sb.String()
	if len(out) > maxToolMessageSize {
		out = out[:maxToolMessageSize] + "\n…[truncated]"
	}
	return out
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

// parseISO 解析 Cursor state.vscdb 中的 createdAt（UTC ISO 字符串），
// 并转换到本地时区以便后续以 LocalTime 形式上报，与服务端 LocalDateTime 时区对齐。
func parseISO(s string) time.Time {
	if s == "" {
		return time.Time{}
	}
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		t, _ = time.Parse("2006-01-02T15:04:05.000Z", s)
	}
	if t.IsZero() {
		return t
	}
	return t.In(time.Local)
}

func fetchBubbles(db *sql.DB, sid string) map[string]string {
	rows, err := db.Query(
		"SELECT key, value FROM cursorDiskKV WHERE key LIKE ?",
		"bubbleId:"+sid+":%",
	)
	if err != nil {
		return nil
	}
	defer rows.Close()

	prefix := "bubbleId:" + sid + ":"
	result := make(map[string]string)
	for rows.Next() {
		var key, value string
		if err := rows.Scan(&key, &value); err != nil {
			continue
		}
		bubbleID := strings.TrimPrefix(key, prefix)
		result[bubbleID] = value
	}
	return result
}

// buildParsedSession 按 composerData 气泡顺序解析单会话（消息不截断）。
//
// workspaceMap 是 composer-id → workspace 根目录的精确映射（来自 Cursor workspaceStorage），
// 命中即作为 CWD 终值，越过基于 hints 的启发式推断。
func buildParsedSession(db *sql.DB, sid string, lastBubbleAt time.Time, workspaceCtx map[string]WorkspaceContext) *parsedSession {
	var composerJSON string
	err := db.QueryRow(
		"SELECT value FROM cursorDiskKV WHERE key = ?",
		"composerData:"+sid,
	).Scan(&composerJSON)
	if err != nil {
		return nil
	}

	// composer 顶层字段名采样自真实 state.vscdb（2026-05）：
	//   modelConfig.modelName  当前会话使用的模型（如 claude-opus-4-7）
	//   contextTokensUsed      已消耗的上下文 token（会话级，不分 in/out）
	//   contextTokenLimit      上下文 token 上限
	var composer struct {
		ModelConfig struct {
			ModelName string `json:"modelName"`
		} `json:"modelConfig"`
		ContextTokensUsed int64 `json:"contextTokensUsed"`
		ContextTokenLimit int64 `json:"contextTokenLimit"`
		SubagentInfo      struct {
			ParentComposerID string `json:"parentComposerId"`
		} `json:"subagentInfo"`
		SubagentComposerIDs []string `json:"subagentComposerIds"`
		CreatedAt           json.RawMessage `json:"createdAt"`
		Headers             []struct {
			BubbleID string `json:"bubbleId"`
			Type     int    `json:"type"`
		} `json:"fullConversationHeadersOnly"`
	}
	if err := json.Unmarshal([]byte(composerJSON), &composer); err != nil {
		return nil
	}
	if len(composer.Headers) == 0 {
		return nil
	}

	bubbleMap := fetchBubbles(db, sid)
	if len(bubbleMap) == 0 {
		return nil
	}

	ps := &parsedSession{
		SessionID:           sid,
		ParentComposerID:    strings.TrimSpace(composer.SubagentInfo.ParentComposerID),
		SubagentComposerIDs: append([]string(nil), composer.SubagentComposerIDs...),
		LastActivity:        lastBubbleAt,
		Model:               composer.ModelConfig.ModelName,
	}
	sessionAnchor := ParseComposerCreatedAtMs(composer.CreatedAt)
	if !sessionAnchor.IsZero() {
		ps.StartedAt = sessionAnchor
	}

	var recentTools []monitor.Tool
	var recentMessages []monitor.Message
	var lastBubbleType int
	var lastHadTool bool
	var pathHints []string
	// chars 累计：用于 input/output token 启发式估算（chars/4，对齐主流 LLM）。
	// Cursor 不在 bubble 上记 in/out token，必须自己估，否则 ai_session.output_tokens 永远为 0。
	var inputChars, outputChars int64
	var estInTokens, estOutTokens int64
	var lastMonotonicTS time.Time

	emitBubbleActivity := func(ts time.Time, extID, role string, msgDelta int) {
		if ts.IsZero() {
			return
		}
		newIn := inputChars / 4
		newOut := outputChars / 4
		dIn := newIn - estInTokens
		dOut := newOut - estOutTokens
		estInTokens, estOutTokens = newIn, newOut
		ref := extID
		if ref == "" {
			ref = role + ":" + ts.Format(time.RFC3339Nano)
		}
		common.AppendActivityDelta(&ps.ActivityDeltas, ts, ref, common.ActivitySourceBubbleEst, dIn, dOut, msgDelta)
	}

	for hi, header := range composer.Headers {
		conversationOrder := hi + 1
		raw, ok := bubbleMap[header.BubbleID]
		if !ok {
			continue
		}
		var bubble bubbleData
		if err := json.Unmarshal([]byte(raw), &bubble); err != nil {
			continue
		}

		lastBubbleType = bubble.Type
		lastHadTool = false

		ts := ResolveHeaderMessageTime(sessionAnchor, conversationOrder, parseISO(bubble.CreatedAt), &lastMonotonicTS)
		if !ts.IsZero() {
			if ps.StartedAt.IsZero() {
				ps.StartedAt = ts
			}
			ps.LastActivity = ts
		}

		if len(bubble.WorkspaceUris) > 0 {
			for _, uri := range bubble.WorkspaceUris {
				if decoded, err := url.PathUnescape(strings.TrimPrefix(uri, "file://")); err == nil {
					pathHints = append(pathHints, decoded)
				}
			}
		}
		if len(pathHints) < 50 {
			// 顶层 raw 兜底（可能是裸路径，被 fileURIRe 抓不到）
			collectPathHints(raw, &pathHints)
			collectPathHints(bubble.ToolFormerData.RawArgs, &pathHints)
			collectPathHints(bubble.ToolFormerData.Params, &pathHints)
			collectPathHints(bubble.ToolFormerData.Result, &pathHints)
		}

		// bubble 自带 token 通常为 0，仅当 Cursor 真返回值时累加（向后兼容）。
		ps.InputTokens += int64(bubble.TokenCount.InputTokens)
		ps.OutputTokens += int64(bubble.TokenCount.OutputTokens)

		extID := sid + ":" + header.BubbleID
		lt := monitor.LocalTime(ts)
		imagesDir := ""
		if ws, ok := workspaceCtx[sid]; ok {
			imagesDir = ws.ImagesDir
		}

		switch bubble.Type {
		case 1:
			// 子 composer 的 type=1 是 Cursor Task 自动注入的 prompt，不是员工真实提问。
			role := "user"
			if ps.ParentComposerID != "" {
				role = "subagent"
			} else {
				ps.UserMessages++
			}
			inputChars += int64(len(bubble.Text))
			for _, p := range bubble.AttachedCodeChunks {
				inputChars += int64(len(strings.Join(p.Lines, "\n")))
			}
			parts := extractUserParts(bubble, imagesDir)
			if len(parts) > 0 {
				appendMessagePartsTo(&recentMessages, extID, role, "", ts, bubble.TokenCount.InputTokens, bubble.TokenCount.OutputTokens, parts, conversationOrder)
				msgDelta := 0
				if role == "user" {
					msgDelta = 1
				}
				emitBubbleActivity(ts, extID, role, msgDelta)
			}
		case 2:
			if bubble.ToolFormerData.Name != "" {
				toolName := normalizeToolName(bubble.ToolFormerData.Name)
				recentTools = append(recentTools, monitor.Tool{Name: toolName, Timestamp: lt})
				lastHadTool = true
				outputChars += int64(len(bubble.ToolFormerData.RawArgs)) + int64(len(bubble.ToolFormerData.Params))
				inputChars += int64(len(bubble.ToolFormerData.Result))
				parts := extractToolParts(bubble)
				if len(parts) == 0 {
					parts = []monitor.ContentPart{{Type: "tool_result", ToolName: toolName, Text: buildToolText(bubble)}}
				}
				appendMessagePartsTo(&recentMessages, extID, "tool", toolName, ts, bubble.TokenCount.InputTokens, bubble.TokenCount.OutputTokens, parts, conversationOrder)
				emitBubbleActivity(ts, extID, "tool", 0)
				if toolName == "Agent" && ps.ParentComposerID == "" {
					ps.AgentToolOrders = append(ps.AgentToolOrders, conversationOrder)
				}
			} else if bubble.Text != "" || bubble.Thinking.Text != "" {
				ps.AssistantMessages++
				if think := strings.TrimSpace(bubble.Thinking.Text); think != "" {
					outputChars += int64(len(think))
					appendMessagePartsTo(&recentMessages, extID+":thinking", "thinking", "", ts, 0, 0,
						[]monitor.ContentPart{{Type: "thinking", Text: truncateBytes(think, maxTextBytesPerPart), SortOrder: 0}}, conversationOrder)
				}
				if bubble.Text != "" {
					outputChars += int64(len(bubble.Text))
					appendMessagePartsTo(&recentMessages, extID, "assistant", "", ts, bubble.TokenCount.InputTokens, bubble.TokenCount.OutputTokens,
						[]monitor.ContentPart{{Type: "text", Text: truncateBytes(bubble.Text, maxTextBytesPerPart), SortOrder: 0}}, conversationOrder)
				}
				emitBubbleActivity(ts, extID, "assistant", 1)
			}
		}

		if len(recentTools) > maxRecentTools*2 {
			recentTools = recentTools[len(recentTools)-maxRecentTools:]
		}
		if len(recentMessages) > maxRecentMessages*2 {
			// 与末尾 capMessagesFromStart 一致：超长会话保留对话开头，避免回填时丢失早期 user 消息。
			recentMessages = recentMessages[:maxRecentMessages]
		}
	}

	// CWD 决策优先级（从强到弱）：
	//   1. workspaceStorage 中 composer→workspace 的精确映射（来自 Cursor 自己的工作区记录）
	//   2. 路径 hint 投票（fileURI / 裸路径 / toolFormerData 内的路径）
	//
	// 第 1 步覆盖了 monorepo 顶层无 .git、bubble 全在子模块的情况：只要 Cursor 把这个 chat 注册
	// 在 am 工作区，我们就直接拿到 /Users/gz/projects/am。
	if ws, ok := workspaceCtx[sid]; ok && ws.Folder != "" {
		ps.CWD = ws.Folder
	} else {
		ps.CWD = inferWorkspace(pathHints)
	}
	if len(recentTools) > maxRecentTools {
		recentTools = recentTools[len(recentTools)-maxRecentTools:]
	}
	if len(recentMessages) > maxRecentMessages {
		recentMessages = recentMessages[:maxRecentMessages]
	}
	ps.RecentTools = recentTools
	ps.RecentMessages = recentMessages
	recountParsedRoleStats(ps)

	// Cursor 在 bubble 上不记 in/out token；用字符 / 4 启发式估算（对齐主流 BPE 编码）。
	// 若同时存在 contextTokensUsed，按比例校准 input：保证总 token ≈ Cursor 内置面板显示值。
	estInput := inputChars / 4
	estOutput := outputChars / 4
	if ps.InputTokens == 0 && ps.OutputTokens == 0 {
		ps.InputTokens = estInput
		ps.OutputTokens = estOutput
		if composer.ContextTokensUsed > 0 && ps.InputTokens+ps.OutputTokens > 0 {
			scale := float64(composer.ContextTokensUsed) / float64(ps.InputTokens+ps.OutputTokens)
			if scale > 1 {
				ps.InputTokens = int64(float64(ps.InputTokens) * scale)
				ps.OutputTokens = int64(float64(ps.OutputTokens) * scale)
			}
		} else if composer.ContextTokensUsed > 0 {
			// 没有任何文本可估算（极端情况）：把 contextTokensUsed 全部记到 input，至少不丢数据。
			ps.InputTokens = composer.ContextTokensUsed
		}
	}

	ps.BubbleStatus = determineBubbleStatus(lastBubbleType, lastHadTool)
	if ps.BubbleStatus == statusExecTool && len(ps.RecentTools) > 0 {
		ps.CurrentTool = ps.RecentTools[len(ps.RecentTools)-1].Name
	}

	wt, main := detectWorktree(ps.CWD)
	ps.IsWorktree = wt
	ps.MainRepo = main

	if !lastBubbleAt.IsZero() {
		ps.LastActivity = lastBubbleAt
	}
	return ps
}

func recountParsedRoleStats(ps *parsedSession) {
	stats := &common.MergeableSessionStats{RecentMessages: ps.RecentMessages}
	common.RecountRoleStats(stats)
	ps.UserMessages = stats.UserMessages
	ps.AssistantMessages = stats.AssistantMessages
}
