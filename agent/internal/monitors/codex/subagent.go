// Codex subagent thread 归并。
//
// gz
package codex

import (
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

func mergeSubagentSessions(sessions []*parsedSession) []*parsedSession {
	return common.CollapseChildSessions(sessions, common.SessionChildLinks[*parsedSession]{
		ID:       func(ps *parsedSession) string { return ps.SessionID },
		ParentID: func(ps *parsedSession) string { return ps.ParentSessionID },
		MergeChildren: func(parent *parsedSession, children []*parsedSession) {
			stats := statsFromParsed(parent)
			for _, child := range children {
				cs := statsFromParsed(child)
				common.MergeSessionStats(stats, cs)
				stats.RecentMessages = append(stats.RecentMessages, cs.RecentMessages...)
			}
			stats.RecentTools = common.SortAndCapTools(stats.RecentTools, maxRecentTools)
			stats.RecentMessages = common.SortAndCapMessages(stats.RecentMessages, maxRecentMessages)
			common.RecountRoleStats(stats)
			applyStatsToParsed(parent, stats)
		},
	}, common.CollapseChildOptions{})
}

func statsFromParsed(ps *parsedSession) *common.MergeableSessionStats {
	return &common.MergeableSessionStats{
		UserMessages:      ps.UserMessages,
		AssistantMessages: ps.AssistantMessages,
		InputTokens:       ps.InputTokens,
		OutputTokens:      ps.OutputTokens,
		CacheRead:         ps.CacheRead,
		StartedAt:         ps.StartedAt,
		LastActivity:      ps.LastActivity,
		CurrentTool:       ps.CurrentTool,
		BubbleStatus:      ps.BubbleStatus,
		RecentTools:       append([]monitor.Tool(nil), ps.RecentTools...),
		RecentMessages:    append([]monitor.Message(nil), ps.RecentMessages...),
	}
}

func applyStatsToParsed(ps *parsedSession, stats *common.MergeableSessionStats) {
	ps.UserMessages = stats.UserMessages
	ps.AssistantMessages = stats.AssistantMessages
	ps.InputTokens = stats.InputTokens
	ps.OutputTokens = stats.OutputTokens
	ps.CacheRead = stats.CacheRead
	ps.StartedAt = stats.StartedAt
	ps.LastActivity = stats.LastActivity
	ps.CurrentTool = stats.CurrentTool
	ps.BubbleStatus = stats.BubbleStatus
	ps.RecentTools = stats.RecentTools
	ps.RecentMessages = stats.RecentMessages
}
