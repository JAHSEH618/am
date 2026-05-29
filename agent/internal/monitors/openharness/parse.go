// 把 session-*.json 文件读成 parsedSession。
// gz
package openharness

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const (
	// maxRecentMessages v2.7 起 10 → 1000，让员工首次安装时能上送完整历史。
	// 详见 codex/parsed.go 同名常量的注释。
	maxRecentMessages = 1000
	maxRecentTools = 10
)

// parseFile 读取并解析单个 session-XXX.json。
//
// last_activity 取文件 mtime（精度高于 messages 内嵌时间戳，且 OpenHarness 每次写出都会刷新 mtime）。
func parseFile(path string, info os.FileInfo, userHash string) (*parsedSession, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var raw rawSession
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}
	ps := &parsedSession{
		SessionID:    raw.SessionID,
		UserHash:     userHash,
		Cwd:          raw.Cwd,
		Model:        raw.Model,
		Summary:      strings.TrimSpace(raw.Summary),
		InputTokens:  raw.Usage.InputTokens,
		OutputTokens: raw.Usage.OutputTokens,
	}
	if raw.CreatedAt > 0 {
		sec := int64(raw.CreatedAt)
		nsec := int64((raw.CreatedAt - float64(sec)) * 1e9)
		ps.StartedAt = time.Unix(sec, nsec)
	}
	ps.LastActivity = info.ModTime()
	if ps.LastActivity.IsZero() {
		ps.LastActivity = ps.StartedAt
	}
	if ps.SessionID == "" {
		// 兜底：用文件名（去掉 session- 前缀和扩展名）当 session_id，确保能上报。
		ps.SessionID = strings.TrimSuffix(strings.TrimPrefix(filepath.Base(path), sessionFilePrefix), ".json")
	}

	// OpenHarness 把工具调用嵌在 assistant 消息的 content 数组里（type=tool_use），
	// 这里顺带把它们提出来作为 RecentTools，并维护 user/assistant/tool 三种消息计数。
	//
	// session-*.json 是 append-only 的（每次更新整文件重写但消息相对位置不变），
	// 因此用数组下标 idx 作为 ExternalMessageID 的 discriminator 是稳定的——
	// 这是为了避免服务端去重失效：raw.messages 没有 message 级原生 id 字段，
	// 不合成 id 的话每个 reporter tick 都会把"最近 N 条"重复 INSERT。
	tools := make([]monitor.Tool, 0, 4)
	msgs := make([]monitor.Message, 0, len(raw.Messages))
	for _, m := range raw.Messages {
		switch m.Role {
		case "user":
			ps.UserMessages++
		case "assistant":
			ps.AssistantMessages++
		}
	}
	if ps.UserMessages == 0 && ps.AssistantMessages == 0 && raw.MessageCount > 0 {
		ps.UserMessages = (raw.MessageCount + 1) / 2
		ps.AssistantMessages = raw.MessageCount / 2
	}
	msgTimes := common.InterpolateTimes(ps.StartedAt, ps.LastActivity, len(raw.Messages))
	inPerUser := int64(0)
	outPerAssist := int64(0)
	if ps.UserMessages > 0 && ps.InputTokens > 0 {
		inPerUser = ps.InputTokens / int64(ps.UserMessages)
	}
	if ps.AssistantMessages > 0 && ps.OutputTokens > 0 {
		outPerAssist = ps.OutputTokens / int64(ps.AssistantMessages)
	}
	for idx, m := range raw.Messages {
		eventTime := ps.LastActivity
		if idx < len(msgTimes) {
			eventTime = msgTimes[idx]
		}
		msgLT := monitor.LocalTime(eventTime)
		for _, c := range m.Content {
			if c.Type == "tool_use" && c.Name != "" {
				tools = append(tools, monitor.Tool{
					Name:      common.NormalizeToolName(c.Name),
					Timestamp: msgLT,
				})
			}
		}
		parts := extractMessageParts(m.Role, m.Content)
		if len(parts) == 0 {
			continue
		}
		flat := monitor.FlattenParts(parts)
		extID := common.SyntheticMessageID(ps.SessionID, strconv.Itoa(idx), m.Role, flat)
		msg := monitor.Message{
			ExternalMessageID: extID,
			Role:              m.Role,
			ContentParts:      parts,
			Timestamp:         msgLT,
		}
		monitor.FinalizeMessage(&msg)
		msgs = append(msgs, msg)
		switch m.Role {
		case "user":
			common.AppendActivityDelta(&ps.ActivityDeltas, eventTime, extID+":user",
				common.ActivitySourceOpenHarnessAlloc, inPerUser, 0, 1)
		case "assistant":
			common.AppendActivityDelta(&ps.ActivityDeltas, eventTime, extID,
				common.ActivitySourceOpenHarnessAlloc, 0, outPerAssist, 1)
		}
	}
	if len(tools) > maxRecentTools {
		tools = tools[len(tools)-maxRecentTools:]
	}
	ps.RecentTools = tools
	if n := len(tools); n > 0 {
		ps.CurrentTool = tools[n-1].Name
	}
	if len(msgs) > maxRecentMessages {
		msgs = msgs[len(msgs)-maxRecentMessages:]
	}
	ps.RecentMessages = msgs

	return ps, nil
}

