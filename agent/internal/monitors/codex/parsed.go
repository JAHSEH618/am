// Codex 解析中间结构。
//
// gz
package codex

import (
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

type parsedSession struct {
	SessionID         string
	JSONLPath         string
	Name              string // 取自 ~/.codex/session_index.jsonl 的 thread_name（可选）
	CWD               string
	GitBranch         string
	Version           string
	Model             string
	ParentSessionID   string
	IsSidechain       bool
	BubbleStatus      string
	CurrentTool       string
	UserMessages      int
	AssistantMessages int
	InputTokens       int64
	OutputTokens      int64
	CacheRead         int64
	StartedAt         time.Time
	LastActivity      time.Time
	LastSummaryAt     time.Time
	RecentTools       []monitor.Tool
	RecentMessages    []monitor.Message
	ActivityDeltas    []monitor.ActivityDelta
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

const (
	maxRecentTools = 20
	// maxRecentMessages 单 session 内存中保留的消息上限。
	//
	// <p>v2.7 起从 40 提到 1000，配合去掉 reporter tail(10) + cursor 持久化 +
	// reporter MaxMessagesPerSession 分批回填，让员工**首次安装**时能把这个 session
	// 在 jsonl 里能看到的全量历史送到 server，而不是只送最近 40 条。
	//
	// <p>1000 的取值依据：单条 message 文本最多 maxMessageText=8000 字符，加上元数据约
	// 9 KB，1000 条 ≈ 9 MB 内存上限——单进程常驻可控；99% 员工的单 session 历史
	// 都不会到 1000。
	//
	// <p>截断策略保持"达到 2× 触发回收到 1×"避免抖动，与 v2.6 之前同款。
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

// appendMessage 把一条消息追加到 RecentMessages，并自动合成 ExternalMessageID。
//
// codex jsonl 没有原生的 message id（envelope 只有 timestamp/type/payload），如果上报时
// ExternalMessageID 留空，服务端 (ai_session_id, external_message_id) 唯一索引会在 NULL 上
// 退化（MySQL 允许多个 NULL），每个 reporter tick 把"最近 N 条" RecentMessages 重新 INSERT，
// 最终库里出现同一条消息几十 / 几百份 —— 这跟 v1.6 早期 hermes / openharness 的脏数据完全
// 同款，v1.6.1 已经在那两个 Provider 修过。codex 的修复滞后到 v2.0.1。
//
// 合成形态：<sessionID>:<ts_microseconds>:<role>:<contentHash8>，跨重启 / VACUUM 稳定。
func (p *parsedSession) appendMessage(role, text, toolName string, ts time.Time) {
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
