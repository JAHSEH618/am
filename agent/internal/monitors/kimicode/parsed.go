// Kimi Code 解析中间结构。
//
// parsedSession 既作为单个 wire.jsonl 文件的增量解析结果（FileCache 的 value），
// 也作为按 <sessionId> 归并 main + subagent 后的会话级结构。
// gz
package kimicode

import (
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const (
	maxRecentTools    = 20
	maxRecentMessages = 1000
	maxMessageText    = 8000
)

type parsedSession struct {
	SessionID string
	AgentName string // "main" 为主线；subagent 文件归并进同 SessionID
	WirePath  string
	CWD       string
	Title     string
	Version   string
	Model     string

	BubbleStatus string
	CurrentTool  string

	UserMessages      int
	AssistantMessages int

	InputTokens  int64
	OutputTokens int64
	CacheRead    int64

	// token_usage 为文件内累计值；prev* 作为基线算增量（压缩/重置回退时裁 0，与 codex 同策略）。
	prevIn  int64
	prevOut int64

	StartedAt    time.Time
	LastActivity time.Time

	RecentTools    []monitor.Tool
	RecentMessages []monitor.Message
	ActivityDeltas []monitor.ActivityDelta
}

func (p *parsedSession) clone() *parsedSession {
	if p == nil {
		return nil
	}
	cp := *p
	if len(p.RecentTools) > 0 {
		cp.RecentTools = append([]monitor.Tool(nil), p.RecentTools...)
	}
	if len(p.RecentMessages) > 0 {
		cp.RecentMessages = append([]monitor.Message(nil), p.RecentMessages...)
	}
	if len(p.ActivityDeltas) > 0 {
		cp.ActivityDeltas = append([]monitor.ActivityDelta(nil), p.ActivityDeltas...)
	}
	return &cp
}

func (p *parsedSession) appendTool(name string, ts time.Time) {
	if name == "" {
		return
	}
	p.RecentTools = append(p.RecentTools, monitor.Tool{Name: name, Timestamp: monitor.LocalTime(ts)})
	if len(p.RecentTools) > maxRecentTools*2 {
		p.RecentTools = p.RecentTools[len(p.RecentTools)-maxRecentTools:]
	}
}

func (p *parsedSession) appendMessage(role, text, toolName string, ts time.Time) {
	if text == "" && toolName == "" {
		return
	}
	ext := common.SyntheticMessageIDByTime(p.SessionID, ts, role, text)
	p.RecentMessages = append(p.RecentMessages, monitor.Message{
		ExternalMessageID: ext,
		Role:              role,
		Text:              common.Truncate(text, maxMessageText),
		ToolName:          toolName,
		Timestamp:         monitor.LocalTime(ts),
	})
	if len(p.RecentMessages) > maxRecentMessages*2 {
		p.RecentMessages = p.RecentMessages[len(p.RecentMessages)-maxRecentMessages:]
	}
}

// mergeFrom 把另一个文件（通常是 subagent 的 wire.jsonl）的解析结果折叠进本会话：
// token 累加、消息/工具/增量拼接、时间取并集。主线提供 cwd/title/model 等身份字段。
func (p *parsedSession) mergeFrom(o *parsedSession) {
	if o == nil {
		return
	}
	p.InputTokens += o.InputTokens
	p.OutputTokens += o.OutputTokens
	p.CacheRead += o.CacheRead
	p.UserMessages += o.UserMessages
	p.AssistantMessages += o.AssistantMessages
	p.RecentMessages = append(p.RecentMessages, o.RecentMessages...)
	p.RecentTools = append(p.RecentTools, o.RecentTools...)
	p.ActivityDeltas = append(p.ActivityDeltas, o.ActivityDeltas...)
	if p.StartedAt.IsZero() || (!o.StartedAt.IsZero() && o.StartedAt.Before(p.StartedAt)) {
		p.StartedAt = o.StartedAt
	}
	if o.LastActivity.After(p.LastActivity) {
		p.LastActivity = o.LastActivity
	}
	if p.Model == "" {
		p.Model = o.Model
	}
}
