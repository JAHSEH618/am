// Cursor Task/subagent composer 归并：多个子 composer 合并到父 chat 再上报。
//
// gz
package cursor

import (
	"sort"
	"strings"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const mergedSubagentOrderBase = 50000

func mergeSubagentSessions(sessions []*parsedSession) ([]*parsedSession, []string) {
	if len(sessions) == 0 {
		return sessions, nil
	}
	enrichSubagentLinks(sessions)

	beforeIDs := make(map[string]struct{}, len(sessions))
	for _, ps := range sessions {
		beforeIDs[ps.SessionID] = struct{}{}
	}

	merged := common.CollapseChildSessions(sessions, common.SessionChildLinks[*parsedSession]{
		ID:       func(ps *parsedSession) string { return ps.SessionID },
		ParentID: func(ps *parsedSession) string { return ps.ParentComposerID },
		MergeChildren: func(parent *parsedSession, children []*parsedSession) {
			parentStarted := parent.StartedAt
			spliceSubagentMessages(parent, children)
			stats := statsFromParsed(parent)
			for _, child := range children {
				common.MergeSessionStats(stats, statsFromParsed(child))
			}
			stats.RecentTools = common.SortAndCapTools(stats.RecentTools, maxRecentTools)
			stats.RecentMessages = capMessagesFromStart(
				common.SortAndCapMessages(stats.RecentMessages, maxRecentMessages*4),
				maxRecentMessages,
			)
			common.RecountRoleStats(stats)
			if !parentStarted.IsZero() {
				stats.StartedAt = parentStarted
			}
			applyStatsToParsed(parent, stats)
		},
	}, common.CollapseChildOptions{})

	suppressedSet := make(map[string]struct{})
	filtered := make([]*parsedSession, 0, len(merged))
	for _, ps := range merged {
		if ps.ParentComposerID != "" {
			suppressedSet[ps.SessionID] = struct{}{}
			continue
		}
		filtered = append(filtered, ps)
	}
	merged = filtered

	kept := make(map[string]struct{}, len(merged))
	for _, ps := range merged {
		kept[ps.SessionID] = struct{}{}
	}
	for id := range beforeIDs {
		if _, ok := kept[id]; !ok {
			suppressedSet[id] = struct{}{}
		}
	}
	for _, ps := range merged {
		for _, cid := range ps.SubagentComposerIDs {
			cid = strings.TrimSpace(cid)
			if cid == "" || cid == ps.SessionID {
				continue
			}
			if _, ok := kept[cid]; !ok {
				suppressedSet[cid] = struct{}{}
			}
		}
	}
	suppressed := make([]string, 0, len(suppressedSet))
	for id := range suppressedSet {
		suppressed = append(suppressed, id)
	}
	sort.Strings(suppressed)
	return merged, suppressed
}

func enrichSubagentLinks(sessions []*parsedSession) {
	childToParent := make(map[string]string)
	for _, ps := range sessions {
		for _, cid := range ps.SubagentComposerIDs {
			if cid != "" {
				childToParent[cid] = ps.SessionID
			}
		}
	}
	for _, ps := range sessions {
		if ps.ParentComposerID != "" {
			continue
		}
		if parent, ok := childToParent[ps.SessionID]; ok {
			ps.ParentComposerID = parent
		}
	}
}

func statsFromParsed(ps *parsedSession) *common.MergeableSessionStats {
	return &common.MergeableSessionStats{
		UserMessages:      ps.UserMessages,
		AssistantMessages: ps.AssistantMessages,
		InputTokens:       ps.InputTokens,
		OutputTokens:      ps.OutputTokens,
		StartedAt:         ps.StartedAt,
		LastActivity:      ps.LastActivity,
		CurrentTool:       ps.CurrentTool,
		BubbleStatus:      ps.BubbleStatus,
		RecentTools:       append([]monitor.Tool(nil), ps.RecentTools...),
		RecentMessages:    append([]monitor.Message(nil), ps.RecentMessages...),
		ActivityDeltas:    append([]monitor.ActivityDelta(nil), ps.ActivityDeltas...),
	}
}

func applyStatsToParsed(ps *parsedSession, stats *common.MergeableSessionStats) {
	ps.UserMessages = stats.UserMessages
	ps.AssistantMessages = stats.AssistantMessages
	ps.InputTokens = stats.InputTokens
	ps.OutputTokens = stats.OutputTokens
	ps.StartedAt = stats.StartedAt
	ps.LastActivity = stats.LastActivity
	ps.CurrentTool = stats.CurrentTool
	ps.BubbleStatus = stats.BubbleStatus
	ps.RecentTools = stats.RecentTools
	ps.RecentMessages = stats.RecentMessages
	ps.ActivityDeltas = stats.ActivityDeltas
}

// spliceSubagentMessages 把 Task 子 composer 消息插到父会话对应 Agent 工具调用之后，并重排时间轴。
func spliceSubagentMessages(parent *parsedSession, children []*parsedSession) {
	if len(children) == 0 {
		renumberConversationTimeline(parent.RecentMessages, parent.StartedAt)
		return
	}

	orderedChildren := orderChildrenForParent(parent, children)
	anchor := parent.StartedAt

	if len(parent.AgentToolOrders) == 0 {
		for i, child := range orderedChildren {
			parent.RecentMessages = append(parent.RecentMessages, offsetChildMessages(child.RecentMessages, mergedSubagentOrderBase+i*10000)...)
		}
		renumberConversationTimeline(parent.RecentMessages, anchor)
		return
	}

	childByAnchor := make(map[int]*parsedSession, len(orderedChildren))
	for i, child := range orderedChildren {
		if i >= len(parent.AgentToolOrders) {
			break
		}
		childByAnchor[parent.AgentToolOrders[i]] = child
	}

	inserted := make(map[string]struct{}, len(orderedChildren))
	var out []monitor.Message
	for _, pm := range parent.RecentMessages {
		out = append(out, pm)
		if pm.Role != "tool" || pm.ToolName != "Agent" {
			continue
		}
		child := childByAnchor[pm.ConversationOrder]
		if child == nil || child.SessionID == "" {
			continue
		}
		if _, ok := inserted[child.SessionID]; ok {
			continue
		}
		out = append(out, child.RecentMessages...)
		inserted[child.SessionID] = struct{}{}
	}
	for _, child := range orderedChildren {
		if _, ok := inserted[child.SessionID]; !ok {
			out = append(out, child.RecentMessages...)
		}
	}
	parent.RecentMessages = out
	renumberConversationTimeline(parent.RecentMessages, anchor)
}

func orderChildrenForParent(parent *parsedSession, children []*parsedSession) []*parsedSession {
	byID := make(map[string]*parsedSession, len(children))
	for _, c := range children {
		byID[c.SessionID] = c
	}
	out := make([]*parsedSession, 0, len(children))
	seen := make(map[string]struct{}, len(children))
	for _, cid := range parent.SubagentComposerIDs {
		cid = strings.TrimSpace(cid)
		if cid == "" {
			continue
		}
		if c, ok := byID[cid]; ok {
			out = append(out, c)
			seen[cid] = struct{}{}
		}
	}
	for _, c := range children {
		if _, ok := seen[c.SessionID]; !ok {
			out = append(out, c)
		}
	}
	return out
}

func offsetChildMessages(msgs []monitor.Message, base int) []monitor.Message {
	if base <= 0 || len(msgs) == 0 {
		return msgs
	}
	out := append([]monitor.Message(nil), msgs...)
	for i := range out {
		co := out[i].ConversationOrder
		if co <= 0 {
			co = i + 1
		}
		out[i].ConversationOrder = base + co
	}
	return out
}

// capMessagesFromStart 超长会话保留对话开头（配合 reporter 游标从前往后回填）。
func capMessagesFromStart(msgs []monitor.Message, max int) []monitor.Message {
	if max <= 0 || len(msgs) <= max {
		return msgs
	}
	return append([]monitor.Message(nil), msgs[:max]...)
}
