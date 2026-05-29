// Cursor 会话时间轴：composer.createdAt 锚点 + header 顺序偏移。
//
// gz
package cursor

import (
	"encoding/json"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// ParseComposerCreatedAtMs 解析 composerData.createdAt（毫秒 epoch）。
func ParseComposerCreatedAtMs(raw json.RawMessage) time.Time {
	if len(raw) == 0 {
		return time.Time{}
	}
	var ms int64
	if err := json.Unmarshal(raw, &ms); err == nil && ms > 0 {
		return time.UnixMilli(ms).In(time.Local)
	}
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		return parseISO(s)
	}
	return time.Time{}
}

// ResolveHeaderMessageTime 在 composer 创建时间与 bubble.createdAt 之间选取展示时间。
func ResolveHeaderMessageTime(sessionAnchor time.Time, order int, bubbleTS time.Time, last *time.Time) time.Time {
	ordered := time.Time{}
	if !sessionAnchor.IsZero() && order > 0 {
		ordered = sessionAnchor.Add(time.Duration(order-1) * time.Second)
	}

	ts := bubbleTS
	if sessionAnchor.IsZero() {
		if ts.IsZero() {
			return time.Time{}
		}
	} else if ts.IsZero() || ts.Before(sessionAnchor) {
		ts = ordered
	} else if order > 0 && order <= 64 && ts.Sub(sessionAnchor) > 12*time.Hour {
		// 早期 header 却被标成续聊时刻 —— 典型批量改写
		ts = ordered
	} else if last != nil && !last.IsZero() && !ts.After(*last) {
		if !ordered.IsZero() {
			ts = ordered
		} else {
			ts = last.Add(time.Second)
		}
	}

	if ts.IsZero() && !ordered.IsZero() {
		ts = ordered
	}
	if last != nil && !last.IsZero() && !ts.IsZero() && !ts.After(*last) {
		ts = last.Add(time.Second)
	}
	if last != nil && !ts.IsZero() {
		*last = ts
	}
	return ts
}

// renumberConversationTimeline 归并后按最终顺序重赋 conversation_order 与单调时间。
func renumberConversationTimeline(msgs []monitor.Message, sessionAnchor time.Time) {
	var last time.Time
	for i := range msgs {
		order := i + 1
		msgs[i].ConversationOrder = order
		bubbleTS := msgs[i].Timestamp.Time()
		ts := ResolveHeaderMessageTime(sessionAnchor, order, bubbleTS, &last)
		if !ts.IsZero() {
			msgs[i].Timestamp = monitor.LocalTime(ts)
		}
	}
}
