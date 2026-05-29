// Hermes 中间结构：把 SQLite 行映射到 Provider 共用的 parsedSession 格式，再由 provider.go 折算 monitor.Session。
// gz
package hermes

import (
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// parsedSession 是 hermes.sessions 表 + 其 messages 聚合后的中间结构。
type parsedSession struct {
	SessionID    string
	Source       string  // cli / api_server / ...
	UserID       string
	Model        string
	Title        string
	StartedAt    time.Time
	EndedAt      time.Time
	LastActivity time.Time

	UserMessages      int
	AssistantMessages int
	ToolCallCount     int

	InputTokens     int64
	OutputTokens    int64
	CacheRead       int64
	CacheCreate     int64
	ReasoningTokens int64

	CurrentTool   string
	RecentTools    []monitor.Tool
	RecentMessages []monitor.Message
	ActivityDeltas []monitor.ActivityDelta
}
