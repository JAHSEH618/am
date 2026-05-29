// 子 agent / 子 thread 会话归并：把带 parent id 的子会话折叠到根父 chat 再上报。
//
// gz
package common

import (
	"sort"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// CollapseChildOptions 控制父会话不在当前 batch 内时的行为。
type CollapseChildOptions struct {
	// PromoteOrphanChildren 为 true 时，子会话引用的 parent 虽不在 batch 内，
	// 仍归并到 synthetic 父会话（Claude 仅 subagents/ 目录场景）。
	PromoteOrphanChildren bool
}

// SessionChildLinks 描述如何把 provider 的 parsedSession 接入归并器。
type SessionChildLinks[PS any] struct {
	ID                 func(PS) string
	ParentID           func(PS) string
	MergeChildren      func(parent PS, children []PS)
	NewSyntheticParent func(rootID string, template PS) PS
}

// CollapseChildSessions 把带 parent id 的子会话归并到根父会话，只返回应上报的根会话。
func CollapseChildSessions[PS any](sessions []PS, links SessionChildLinks[PS], opts CollapseChildOptions) []PS {
	if len(sessions) == 0 {
		return sessions
	}

	byID := make(map[string]PS, len(sessions))
	for _, ps := range sessions {
		byID[links.ID(ps)] = ps
	}

	resolveRoot := func(id string) (root string, isChild bool) {
		cur := id
		seen := map[string]bool{}
		for {
			ps, ok := byID[cur]
			if !ok {
				return id, false
			}
			parent := links.ParentID(ps)
			if parent == "" {
				return cur, cur != id
			}
			if seen[parent] {
				return id, false
			}
			seen[parent] = true
			if _, ok := byID[parent]; !ok {
				if opts.PromoteOrphanChildren {
					return parent, true
				}
				return id, false
			}
			cur = parent
		}
	}

	childrenByRoot := make(map[string][]PS)
	rootIDs := make(map[string]struct{})

	for _, ps := range sessions {
		root, isChild := resolveRoot(links.ID(ps))
		rootIDs[root] = struct{}{}
		if isChild {
			childrenByRoot[root] = append(childrenByRoot[root], ps)
		}
	}

	out := make([]PS, 0, len(rootIDs))
	for rootID := range rootIDs {
		parent, hasParent := byID[rootID]
		children := childrenByRoot[rootID]
		if !hasParent {
			if len(children) == 0 || links.NewSyntheticParent == nil {
				continue
			}
			parent = links.NewSyntheticParent(rootID, children[0])
		}
		if len(children) == 0 {
			out = append(out, parent)
			continue
		}
		links.MergeChildren(parent, children)
		out = append(out, parent)
	}
	return out
}

// MergeableSessionStats 是各 provider parsedSession 归并时的公共字段集。
type MergeableSessionStats struct {
	UserMessages      int
	AssistantMessages int
	InputTokens       int64
	OutputTokens      int64
	CacheCreate       int64
	CacheRead         int64
	StartedAt         time.Time
	LastActivity      time.Time
	CurrentTool       string
	BubbleStatus      string
	RecentTools       []monitor.Tool
	RecentMessages    []monitor.Message
	ActivityDeltas    []monitor.ActivityDelta
}

// MergeSessionStats 把 child 的 token/时间/tool 轨迹 fold 进 parent。
// RecentMessages 与 user/assistant 计数请由调用方在 splice 后 RecountRoleStats，勿在此累加 child 消息。
func MergeSessionStats(parent, child *MergeableSessionStats) {
	parent.InputTokens += child.InputTokens
	parent.OutputTokens += child.OutputTokens
	parent.CacheCreate += child.CacheCreate
	parent.CacheRead += child.CacheRead

	if !child.StartedAt.IsZero() && (parent.StartedAt.IsZero() || child.StartedAt.Before(parent.StartedAt)) {
		parent.StartedAt = child.StartedAt
	}
	if child.LastActivity.After(parent.LastActivity) {
		parent.LastActivity = child.LastActivity
		if child.CurrentTool != "" {
			parent.CurrentTool = child.CurrentTool
		}
		if child.BubbleStatus != "" {
			parent.BubbleStatus = child.BubbleStatus
		}
	}

	parent.RecentTools = append(parent.RecentTools, child.RecentTools...)
	if len(child.ActivityDeltas) > 0 {
		parent.ActivityDeltas = append(parent.ActivityDeltas, child.ActivityDeltas...)
	}
}

// RecountRoleStats 按最终 RecentMessages 重算 user/assistant 计数（与实际上送 payload 对齐）。
func RecountRoleStats(stats *MergeableSessionStats) {
	if stats == nil {
		return
	}
	user, assistant := 0, 0
	for _, m := range stats.RecentMessages {
		switch m.Role {
		case "user":
			user++
		case "assistant":
			assistant++
		}
	}
	stats.UserMessages = user
	stats.AssistantMessages = assistant
}

// SortAndCapTools 按时间排序并截断 tool 轨迹。
func SortAndCapTools(tools []monitor.Tool, max int) []monitor.Tool {
	sort.Slice(tools, func(i, j int) bool {
		return tools[i].Timestamp.Time().Before(tools[j].Timestamp.Time())
	})
	if len(tools) > max {
		return tools[len(tools)-max:]
	}
	return tools
}

// SortAndCapMessages 按对话顺序排序并截断消息列表。
func SortAndCapMessages(msgs []monitor.Message, max int) []monitor.Message {
	sort.Slice(msgs, func(i, j int) bool {
		oi := msgs[i].ConversationOrder
		oj := msgs[j].ConversationOrder
		if oi > 0 && oj > 0 && oi != oj {
			return oi < oj
		}
		if oi > 0 && oj == 0 {
			return true
		}
		if oi == 0 && oj > 0 {
			return false
		}
		ti := msgs[i].Timestamp.Time()
		tj := msgs[j].Timestamp.Time()
		if ti.Equal(tj) {
			return msgs[i].ExternalMessageID < msgs[j].ExternalMessageID
		}
		if ti.IsZero() {
			return false
		}
		if tj.IsZero() {
			return true
		}
		return ti.Before(tj)
	})
	if len(msgs) > max {
		return msgs[len(msgs)-max:]
	}
	return msgs
}
