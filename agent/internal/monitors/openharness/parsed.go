// OpenHarness 中间结构：一个 session-*.json 文件解析成一条 parsedSession。
// gz
package openharness

import (
	"encoding/json"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

type parsedSession struct {
	SessionID    string
	UserHash     string // 来自所在子目录名（如 "gz-3746be4978de"）
	Cwd          string
	Model        string
	StartedAt    time.Time
	LastActivity time.Time

	UserMessages      int
	AssistantMessages int
	InputTokens       int64
	OutputTokens      int64

	Summary       string
	CurrentTool   string
	RecentTools    []monitor.Tool
	RecentMessages []monitor.Message
	ActivityDeltas []monitor.ActivityDelta
}

// rawSession 对应 session-*.json 文件原文。
//
// OpenHarness 目前把 tool_metadata 写成对象（permission_mode / read_file_state / invoked_skills /
// async_agent_state / recent_work_log / recent_verified_work / task_focus_state ...），
// 而非工具调用数组。我们暂时不消费它的内部结构（工具调用从 messages 的 tool_use content 提取），
// 因此用 json.RawMessage 占位，避免反序列化失败连累整条 session。
type rawSession struct {
	SessionID    string          `json:"session_id"`
	Cwd          string          `json:"cwd"`
	Model        string          `json:"model"`
	SystemPrompt string          `json:"system_prompt"`
	Messages     []rawMessage    `json:"messages"`
	Usage        rawUsage        `json:"usage"`
	ToolMeta     json.RawMessage `json:"tool_metadata"`
	CreatedAt    float64         `json:"created_at"`
	Summary      string          `json:"summary"`
	MessageCount int             `json:"message_count"`
}

type rawMessage struct {
	Role    string       `json:"role"`
	Content []rawContent `json:"content"`
}

// rawContent OpenHarness 的 content 元素：
//
//	{"type":"text","text":"..."}                  普通文本
//	{"type":"tool_use","name":"...","input":{}}   assistant 调起工具
//	{"type":"tool_result","tool_use_id":"...","content":...}  工具回执
type rawContent struct {
	Type      string          `json:"type"`
	Text      string          `json:"text,omitempty"`
	Name      string          `json:"name,omitempty"`
	ToolUseID string          `json:"tool_use_id,omitempty"`
	Input     json.RawMessage `json:"input,omitempty"`
	Content   json.RawMessage `json:"content,omitempty"`
}

type rawUsage struct {
	InputTokens  int64 `json:"input_tokens"`
	OutputTokens int64 `json:"output_tokens"`
}
