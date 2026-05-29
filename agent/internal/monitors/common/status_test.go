// 状态模块单元测试。
//
// gz
package common

import (
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

func TestNormalizeToolName(t *testing.T) {
	cases := map[string]string{
		"Read":                    "Read",
		"read_file":               "Read",
		"read_file_v2":            "Read",
		"write_file_v2":           "Write",
		"apply_patch":             "Edit",
		"Edit":                    "Edit",
		"run_terminal_command_v2": "Bash",
		"shell":                   "Bash",
		"codebase_search":         "Grep",
		"web_search":              "WebSearch",
		"web_fetch":               "WebFetch",
		"task":                    "Agent",
		"":                        "",
		"customTool":              "CustomTool",
	}
	for in, want := range cases {
		if got := NormalizeToolName(in); got != want {
			t.Errorf("NormalizeToolName(%q)=%q want %q", in, got, want)
		}
	}
}

func TestResolveActivity(t *testing.T) {
	ConfigureActivityTimeouts(10_000) // 10s → 30s 窗口，保持用例与旧默认一致
	now := time.Date(2026, 5, 7, 10, 0, 0, 0, time.UTC)

	t.Run("idle when no activity", func(t *testing.T) {
		got := ResolveActivity(SessionLike{}, now)
		if got != StatusIdle {
			t.Fatalf("want idle, got %q", got)
		}
	})

	t.Run("compacting wins over recent tool", func(t *testing.T) {
		got := ResolveActivity(SessionLike{
			LastActivity:  now.Add(-5 * time.Second),
			LastSummaryAt: now.Add(-10 * time.Second),
			BubbleStatus:  BubbleThinking,
		}, now)
		if got != StatusCompacting {
			t.Fatalf("want compacting, got %q", got)
		}
	})

	t.Run("recent tool maps to action", func(t *testing.T) {
		got := ResolveActivity(SessionLike{
			LastActivity: now.Add(-5 * time.Second),
			BubbleStatus: BubbleExecTool,
			RecentTools: []monitor.Tool{{
				Name:      "Read",
				Timestamp: monitor.LocalTime(now.Add(-2 * time.Second)),
			}},
		}, now)
		if got != StatusReading {
			t.Fatalf("want reading, got %q", got)
		}
	})

	t.Run("waiting after assistant text", func(t *testing.T) {
		got := ResolveActivity(SessionLike{
			LastActivity: now.Add(-30 * time.Second),
			BubbleStatus: BubbleWaitingUser,
		}, now)
		if got != StatusWaiting {
			t.Fatalf("want waiting, got %q", got)
		}
	})

	t.Run("idle after waiting timeout", func(t *testing.T) {
		got := ResolveActivity(SessionLike{
			LastActivity: now.Add(-3 * time.Minute),
			BubbleStatus: BubbleWaitingUser,
		}, now)
		if got != StatusIdle {
			t.Fatalf("want idle, got %q", got)
		}
	})

	t.Run("thinking when user just spoke", func(t *testing.T) {
		got := ResolveActivity(SessionLike{
			LastActivity: now.Add(-5 * time.Second),
			BubbleStatus: BubbleThinking,
		}, now)
		if got != StatusThinking {
			t.Fatalf("want thinking, got %q", got)
		}
	})

	t.Run("thinking survives 2min tick gap", func(t *testing.T) {
		ConfigureActivityTimeouts(120_000)
		got := ResolveActivity(SessionLike{
			LastActivity: now.Add(-90 * time.Second),
			BubbleStatus: BubbleThinking,
		}, now)
		if got != StatusThinking {
			t.Fatalf("want thinking at 90s with 2min interval, got %q", got)
		}
	})
}

func TestTruncate(t *testing.T) {
	if Truncate("hello", 3) != "hel…" {
		t.Fatalf("ascii truncation broken")
	}
	if Truncate("中文测试", 2) != "中文…" {
		t.Fatalf("utf8 truncation broken")
	}
	if Truncate("short", 100) != "short" {
		t.Fatalf("should not truncate when shorter than limit")
	}
}
