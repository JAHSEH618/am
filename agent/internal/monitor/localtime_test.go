package monitor

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

// TestLocalTimeMarshalNormalizesToLocalZone 复现并守护「活跃会话被时区错位打成 idle」的根因。
//
// claude/codex/cursor 等 monitor 从工具会话文件里解析出的时间戳是 UTC（带 Z），
// LocalTime 在序列化前必须先归一到 agent 本地时区；否则会把 UTC 墙钟数字（08:25:52）
// 当作本地时间发给服务端，服务端(Asia/Shanghai)按 LocalDateTime 字面入库后，用本机 now
// 比对，会判定每个会话都已过期 5min → AiSessionStaleCloser 全部置 idle → 大盘「活跃会话=0」。
func TestLocalTimeMarshalNormalizesToLocalZone(t *testing.T) {
	// 固定本地时区为 +08:00，使断言与运行 go test 的机器时区无关。
	orig := time.Local
	time.Local = time.FixedZone("CST", 8*60*60)
	defer func() { time.Local = orig }()

	// 模拟 time.Parse(time.RFC3339Nano, "...Z") 得到的 UTC 时刻。
	utc := time.Date(2026, 6, 25, 8, 25, 52, 0, time.UTC)

	b, err := json.Marshal(LocalTime(utc))
	if err != nil {
		t.Fatalf("marshal LocalTime: %v", err)
	}
	got := strings.Trim(string(b), `"`)

	const want = "2026-06-25T16:25:52" // +08:00 本地墙钟
	if got != want {
		t.Fatalf("LocalTime 未归一到本地时区: got %q, want %q "+
			"(若得到 08:25:52 说明仍是 UTC 数字 → 服务端会把会话误判为过期 idle)", got, want)
	}
}

// TestLocalTimeMarshalKeepsLocalWallClock 确认对本就是本地时间的值（如 monitor.Now()）不产生偏移，
// 防止「过度修正」(例如误加 .UTC())。
func TestLocalTimeMarshalKeepsLocalWallClock(t *testing.T) {
	orig := time.Local
	time.Local = time.FixedZone("CST", 8*60*60)
	defer func() { time.Local = orig }()

	local := time.Date(2026, 6, 25, 16, 25, 52, 0, time.Local)

	b, err := json.Marshal(LocalTime(local))
	if err != nil {
		t.Fatalf("marshal LocalTime: %v", err)
	}
	got := strings.Trim(string(b), `"`)

	const want = "2026-06-25T16:25:52"
	if got != want {
		t.Fatalf("本地时间被改动: got %q, want %q", got, want)
	}
}
