// OpenCode 中间结构：把 SQLite 行（session + message + part）映射到 Provider 共用的
// parsedSession，再由 provider.go 折算 monitor.Session。
// gz
package opencode

import (
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

const (
	// maxRecentMessages 单 session 单次 Snapshot 拉取/保留的消息上限。
	// 与 codex / hermes 取齐 1000：reporter 端按游标做"自上次以来增量"，本地拉够多就不会在入口处截断。
	maxRecentMessages = 1000
	maxRecentTools    = 10
	maxMessageText    = 8000
)

// parsedSession 是 opencode.session 一行 + 其 message/part 聚合后的中间结构。
type parsedSession struct {
	SessionID string
	ProjectID string
	Directory string // session.directory，即会话 cwd
	Title     string
	Version   string
	Model     string

	StartedAt    time.Time
	LastActivity time.Time

	UserMessages      int
	AssistantMessages int

	InputTokens     int64
	OutputTokens    int64
	ReasoningTokens int64
	CacheRead       int64
	CacheCreate     int64

	CurrentTool    string
	RecentTools    []monitor.Tool
	RecentMessages []monitor.Message
	ActivityDeltas []monitor.ActivityDelta
}
