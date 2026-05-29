// Turn/Session 状态机与归一化。
//
// gz
package common

import (
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/monitor"
)

// 平台级 10 态活动状态，与服务端 AiSessionStatus / lazyagent ResolveActivity 一一对应。
//
// Provider 不要自己造词：所有 Provider 上报的 status 必须落在这 10 个之一，否则前端渲染会丢色。
const (
	StatusIdle       = "idle"
	StatusWaiting    = "waiting"
	StatusThinking   = "thinking"
	StatusCompacting = "compacting"
	StatusReading    = "reading"
	StatusWriting    = "writing"
	StatusRunning    = "running"
	StatusSearching  = "searching"
	StatusBrowsing   = "browsing"
	StatusSpawning   = "spawning"
)

// Provider 内部"原子状态"：bubble / JSONL 解析后还需要时间窗口判定才能映射到平台 10 态。
const (
	BubbleThinking    = "thinking"
	BubbleWaitingUser = "waiting_for_user"
	BubbleExecTool    = "executing_tool"
	BubbleProcessing  = "processing_tool_result"
	BubbleUnknown     = "unknown"
)

const (
	minActivityTimeout      = 30 * time.Second
	minWaitingTimeout       = 2 * time.Minute
	spawningTimeout         = 20 * time.Minute
	WaitingGrace            = 10 * time.Second
	activityWindowMultiplier = 2.5
)

var (
	activityTimeout = minActivityTimeout
	waitingTimeout  = minWaitingTimeout
)

// ConfigureActivityTimeouts 按 reporter 上报间隔缩放活动判定窗口（默认 2.5×，与服务端 online-window 对齐）。
// reporter 启动时调用一次；单测可传入较短 interval 保持用例稳定。
func ConfigureActivityTimeouts(reportIntervalMs int64) {
	interval := time.Duration(reportIntervalMs) * time.Millisecond
	if interval <= 0 {
		interval = time.Duration(config.DefaultReportIntervalMs) * time.Millisecond
	}
	scaled := time.Duration(float64(interval) * activityWindowMultiplier)
	if scaled < minActivityTimeout {
		scaled = minActivityTimeout
	}
	activityTimeout = scaled
	waitingTimeout = scaled
	if waitingTimeout < minWaitingTimeout {
		waitingTimeout = minWaitingTimeout
	}
}

// SessionLike 是状态机需要的最小 Session 投影。Provider 自定义的临时结构只要实现这几个 getter 即可。
type SessionLike struct {
	LastActivity  time.Time
	BubbleStatus  string         // BubbleThinking / BubbleWaitingUser / BubbleExecTool / BubbleProcessing / BubbleUnknown
	RecentTools   []monitor.Tool // 最近若干次工具调用（用于判断是否仍在执行工具）
	CurrentTool   string         // 最近一次工具名（用于 toolActivity）
	LastSummaryAt time.Time      // 最近一次 compact summary 的时间（无则零值）
}

// ResolveActivity 把 Provider 的原子状态 + 时间戳折算成平台 10 态。
func ResolveActivity(s SessionLike, now time.Time) string {
	since := now.Sub(s.LastActivity)

	if !s.LastSummaryAt.IsZero() && now.Sub(s.LastSummaryAt) < activityTimeout {
		return StatusCompacting
	}
	if len(s.RecentTools) > 0 {
		last := s.RecentTools[len(s.RecentTools)-1]
		ts := last.Timestamp.Time()
		if !ts.IsZero() && now.Sub(ts) < activityTimeout {
			return ToolActivity(last.Name)
		}
	}
	if s.BubbleStatus == BubbleWaitingUser {
		if !s.LastActivity.IsZero() && since < waitingTimeout {
			return StatusWaiting
		}
		return StatusIdle
	}
	if s.BubbleStatus == BubbleExecTool && ToolActivity(s.CurrentTool) == StatusSpawning && since < spawningTimeout {
		return StatusSpawning
	}
	if s.BubbleStatus == BubbleThinking || s.BubbleStatus == BubbleProcessing {
		if !s.LastActivity.IsZero() && since <= activityTimeout {
			return StatusThinking
		}
	}
	if s.LastActivity.IsZero() || since > activityTimeout {
		return StatusIdle
	}
	switch s.BubbleStatus {
	case BubbleThinking, BubbleProcessing:
		return StatusThinking
	case BubbleExecTool:
		return ToolActivity(s.CurrentTool)
	}
	return StatusIdle
}

// ToolActivity 把工具名映射成平台 10 态中的"动作色"（reading/writing/running/...）。
//
// 这里覆盖 Claude / Cursor / Codex 三家常见工具命名。Provider 上报前应当先调用 NormalizeToolName
// 对工具名做归一化，确保不同 IDE 的命名差异不会绕过这张表。
func ToolActivity(tool string) string {
	switch tool {
	case "Read":
		return StatusReading
	case "Write", "Edit", "NotebookEdit":
		return StatusWriting
	case "Bash", "Shell":
		return StatusRunning
	case "Glob", "Grep":
		return StatusSearching
	case "WebFetch", "WebSearch":
		return StatusBrowsing
	case "Agent":
		return StatusSpawning
	}
	if tool != "" {
		return StatusRunning
	}
	return StatusIdle
}

// NormalizeToolName 把不同 Agent 的原始工具名归一到平台标准词汇。
//
// 设计原则：归一后的名字必须能命中 ToolActivity，否则状态机会回退到 "running"。
//
// 参考样本：
//
//	Claude Code      Read / Write / Edit / NotebookEdit / Bash / Glob / Grep / WebFetch / WebSearch / Task
//	Cursor           read_file_v2 / write_file_v2 / edit_file_v2 / run_terminal_command_v2 / codebase_search / ...
//	Codex CLI        apply_patch / shell / read_file / search / web_search / fetch / spawn_subagent
func NormalizeToolName(name string) string {
	if name == "" {
		return ""
	}
	lower := strings.ToLower(name)
	switch lower {
	case "read", "read_file", "read_file_v2", "view", "view_file":
		return "Read"
	case "write", "write_file", "write_to_file", "write_file_v2", "write_to_file_v2":
		return "Write"
	case "edit", "edit_file", "edit_file_v2", "apply_patch", "applypatch", "notebookedit", "notebook_edit":
		return "Edit"
	case "bash", "shell", "run_terminal_command", "run_terminal_command_v2", "exec":
		return "Bash"
	case "glob", "glob_file_search", "list_dir", "list_dir_v2", "find":
		return "Glob"
	case "grep", "grep_search", "codebase_search", "ripgrep", "ripgrep_raw_search", "search":
		return "Grep"
	case "websearch", "web_search":
		return "WebSearch"
	case "webfetch", "web_fetch", "fetch":
		return "WebFetch"
	case "agent", "subagent", "task", "spawn_subagent":
		return "Agent"
	}
	// 未识别就保留首字母大写，便于人工排查。
	return strings.ToUpper(name[:1]) + name[1:]
}
