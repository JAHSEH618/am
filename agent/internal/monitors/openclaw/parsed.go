// OpenClaw 中间结构：把 ~/.openclaw/agents/<agent>/sessions/<uuid>.jsonl 折算成 monitor 上报格式。
// gz
package openclaw

import (
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// parsedSession 是 Provider 内部的会话累计快照，独立于服务端 DTO，
// 增量解析时把新行 merge 进同一份 parsedSession 即可。
//
// 字段命名与 Claude provider 对齐，便于 ResolveActivity 通用消费 SessionLike。
type parsedSession struct {
	SessionID         string
	JSONLPath         string
	AgentName         string // ~/.openclaw/agents/<AgentName>/sessions/...
	CWD               string
	Version           string
	Model             string
	ParentSessionID   string
	BubbleStatus      string
	CurrentTool       string
	UserMessages      int
	AssistantMessages int
	InputTokens       int64
	OutputTokens      int64
	CacheCreate       int64
	CacheRead         int64
	StartedAt         time.Time
	LastActivity      time.Time
	LastSummaryAt     time.Time
	RecentTools       []monitor.Tool
	RecentMessages    []monitor.Message
	ActivityDeltas    []monitor.ActivityDelta
}

// clone 增量解析前先 clone：避免并发 Provider 之间 mutate 同一份缓存。
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

const (
	maxRecentTools = 20
	// maxRecentMessages v2.7 起 40 → 1000，让员工首次安装时能上送完整历史。
	// 详见 codex/parsed.go 同名常量的注释。
	maxRecentMessages = 1000
	maxMessageText    = 8000
)

func (p *parsedSession) appendTool(name string, ts time.Time) {
	if name == "" {
		return
	}
	p.RecentTools = append(p.RecentTools, monitor.Tool{Name: name, Timestamp: monitor.LocalTime(ts)})
	if len(p.RecentTools) > maxRecentTools*2 {
		p.RecentTools = p.RecentTools[len(p.RecentTools)-maxRecentTools:]
	}
}

func (p *parsedSession) appendMessage(role, text, toolName, externalID string, ts time.Time, in, out int) {
	p.RecentMessages = append(p.RecentMessages, monitor.Message{
		ExternalMessageID: externalID,
		Role:              role,
		Text:              common.Truncate(text, maxMessageText),
		ToolName:          toolName,
		Timestamp:         monitor.LocalTime(ts),
		InputTokens:       in,
		OutputTokens:      out,
	})
	if len(p.RecentMessages) > maxRecentMessages*2 {
		p.RecentMessages = p.RecentMessages[len(p.RecentMessages)-maxRecentMessages:]
	}
}
