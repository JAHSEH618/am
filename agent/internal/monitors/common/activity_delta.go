// activity_delta.go：把源日志里带时间戳的 token/消息增量整理为上报结构。
//
// gz
package common

import (
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

const (
	ActivitySourceAssistantTurn = "assistant_turn"
	ActivitySourceUserTurn      = "user_turn"
	ActivitySourceTokenCount    = "token_count"
	ActivitySourceBubbleEst     = "bubble_estimate"
)

// AppendActivityDelta 追加一条带原始 event_time 的增量（去重由 reporter 游标 + server source_ref 兜底）。
func AppendActivityDelta(ps *[]monitor.ActivityDelta, eventTime time.Time, sourceRef, source string,
	inputDelta, outputDelta int64, messagesDelta int) {
	if ps == nil || eventTime.IsZero() {
		return
	}
	if inputDelta == 0 && outputDelta == 0 && messagesDelta == 0 {
		return
	}
	ref := sourceRef
	if ref == "" {
		ref = source + ":" + eventTime.Format(time.RFC3339Nano)
	}
	*ps = append(*ps, monitor.ActivityDelta{
		EventTime:         monitor.LocalTime(eventTime),
		InputTokensDelta:  inputDelta,
		OutputTokensDelta: outputDelta,
		MessagesDelta:     messagesDelta,
		Source:            source,
		SourceRef:         ref,
	})
}

// TailActivityDeltas 与 recent_messages 同样做尾部保留，供 reporter 游标增量上报。
func TailActivityDeltas(d []monitor.ActivityDelta, max int) []monitor.ActivityDelta {
	if max <= 0 || len(d) <= max {
		return d
	}
	return d[len(d)-max:]
}

const ActivitySourceOpenHarnessAlloc = "openharness_alloc"

// InterpolateTimes 在 [start, end] 上为 n 条消息生成单调不减的时间戳（OpenHarness 等源无逐条时间时使用）。
func InterpolateTimes(start, end time.Time, n int) []time.Time {
	if n <= 0 {
		return nil
	}
	if end.IsZero() {
		end = start
	}
	if start.IsZero() {
		start = end
	}
	if n == 1 {
		return []time.Time{end}
	}
	if !end.After(start) {
		out := make([]time.Time, n)
		for i := range out {
			out[i] = end
		}
		return out
	}
	out := make([]time.Time, n)
	step := end.Sub(start)
	for i := 0; i < n; i++ {
		frac := float64(i) / float64(n-1)
		out[i] = start.Add(time.Duration(frac * float64(step)))
	}
	return out
}
