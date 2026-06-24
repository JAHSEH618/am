// OpenCode message.data / part.data 的 JSON 解析与 monitor.ContentPart 组装。
//
// message.data（按 role 区分）：
//
//	assistant：{role, providerID, modelID, cost,
//	            tokens:{input,output,reasoning,cache:{read,write}},
//	            time:{created,completed}}        —— 时间为 epoch 毫秒
//	user：     {role, ...}（model 嵌在 model:{providerID,modelID}）
//
// part.data（discriminated on type）：text / reasoning / tool / file / step-finish
//
//	{type:"text", text:"..."}
//	{type:"reasoning", text:"..."}
//	{type:"tool", tool:"<name>", state:{...}}
//
// gz
package opencode

import (
	"encoding/json"
	"strings"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// modelField 解析 session.model（JSON {id, providerID, variant}）为展示用模型名。
type modelField struct {
	ID         string `json:"id"`
	ProviderID string `json:"providerID"`
	Variant    string `json:"variant"`
}

func parseModel(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	// 兼容历史世代可能直接存裸字符串而非 JSON。
	if raw[0] != '{' {
		return raw
	}
	var m modelField
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return ""
	}
	switch {
	case m.ProviderID != "" && m.ID != "":
		return m.ProviderID + "/" + m.ID
	case m.ID != "":
		return m.ID
	default:
		return ""
	}
}

// messageData 是 message.data 的最小投影（best-effort，未知字段忽略）。
type messageData struct {
	Role    string `json:"role"`
	ModelID string `json:"modelID"`
	Tokens  struct {
		Input     int64 `json:"input"`
		Output    int64 `json:"output"`
		Reasoning int64 `json:"reasoning"`
		Cache     struct {
			Read  int64 `json:"read"`
			Write int64 `json:"write"`
		} `json:"cache"`
	} `json:"tokens"`
	Time struct {
		Created   int64 `json:"created"`
		Completed int64 `json:"completed"`
	} `json:"time"`
}

func parseMessageData(raw []byte) messageData {
	var d messageData
	_ = json.Unmarshal(raw, &d)
	return d
}

// partData 是 part.data 的最小投影。
type partData struct {
	Type string `json:"type"`
	Text string `json:"text"`
	Tool string `json:"tool"`
}

// buildParts 把一条消息归属的 part.data 列表组装为 ContentParts，并返回其中出现的工具名（按出现序）。
func buildParts(rawParts [][]byte) (parts []monitor.ContentPart, tools []string) {
	order := 0
	for _, raw := range rawParts {
		var pd partData
		if json.Unmarshal(raw, &pd) != nil {
			continue
		}
		switch pd.Type {
		case "text":
			if strings.TrimSpace(pd.Text) != "" {
				parts = append(parts, monitor.ContentPart{
					Type: "text", Text: common.Truncate(pd.Text, maxMessageText), SortOrder: order,
				})
				order++
			}
		case "reasoning":
			if strings.TrimSpace(pd.Text) != "" {
				parts = append(parts, monitor.ContentPart{
					Type: "thinking", Text: common.Truncate(pd.Text, maxMessageText), SortOrder: order,
				})
				order++
			}
		case "tool":
			name := common.NormalizeToolName(pd.Tool)
			if name != "" {
				tools = append(tools, name)
				parts = append(parts, monitor.ContentPart{
					Type: "tool_call", ToolName: name, SortOrder: order,
				})
				order++
			}
		}
	}
	return parts, tools
}
