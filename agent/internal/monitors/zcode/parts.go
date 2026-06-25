// zcode message.data / part.data 的 JSON 解析与 monitor.ContentPart 组装。
//
// message.data（按 role 区分，best-effort，未知字段忽略）：
//
//	assistant：{role:"assistant", modelID:"GLM-5.2", providerID, cost, mode,
//	            tokens:{input,output,reasoning,cache:{read,write}},
//	            time:{created,completed}}            —— 时间为 epoch 毫秒，token 为真实值
//	user：     {role:"user", model:{modelID,providerID}, contextSnapshot:{envInfo:{…}},
//	            tools:{…}, time:{created}}            —— user 的 model 嵌在 model.modelID
//
// part.data（discriminated on type，真实样本已确认）：
//
//	{type:"text", text:"…", time:{start,end}}
//	{type:"step-start"}                              —— 占位，忽略
//	{type:"reasoning", text:"…", time:{…}}           —— 思考，归为 ContentPart "thinking"
//	{type:"step-finish", cost, reason, tokens:{…}}   —— 每步 token 小结，忽略（token 取自 message.data）
//	{type:"tool", tool:"<Bash|Read|WebSearch|…>",    —— 工具调用：名字在 tool 字段（已确认，大小写规范）
//	   callID:"…", state:{input,output,status,title,metadata,time}}   —— state 等嵌套字段我们不读
//
// gz
package zcode

import (
	"encoding/json"
	"strings"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// messageData 是 message.data 的最小投影。与 opencode.messageData 同形：assistant 消息
// 的 modelID / tokens / time 字段在 zcode 中一致（GLM 适配层沿用 opencode 的消息模型）。
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
