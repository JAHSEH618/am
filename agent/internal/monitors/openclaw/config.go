// 解析 ~/.openclaw/openclaw.json，提取我们关心的全局元数据（model、workspace）。
// gz
package openclaw

import (
	"encoding/json"
	"os"
)

// configSnapshot 是 openclaw.json 抽出来的全局元数据，所有 session 共享同一份。
//
// 用作 jsonl 没记录到时的兜底：
//
//	PrimaryModel  jsonl session 还没出现 assistant 行 / model-snapshot 时的模型兜底
//	Workspace     jsonl session 行 cwd="/"（如桌面 UI 启动）时的 cwd 兜底
type configSnapshot struct {
	PrimaryModel string
	Workspace    string
}

// loadConfig 读取 openclaw.json 并提取主模型 / workspace。读不到就返回零值。
//
// JSON 形态（节选）：
//
//	{
//	  "agents": {
//	    "defaults": {
//	      "workspace": "/Users/gz/.openclaw/workspace",
//	      "model": { "primary": "openai/gpt-4" }
//	    }
//	  }
//	}
func loadConfig() configSnapshot {
	path := configPath()
	if path == "" {
		return configSnapshot{}
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return configSnapshot{}
	}
	var raw struct {
		Agents struct {
			Defaults struct {
				Workspace string `json:"workspace"`
				Model     struct {
					Primary string `json:"primary"`
				} `json:"model"`
			} `json:"defaults"`
		} `json:"agents"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return configSnapshot{}
	}
	return configSnapshot{
		PrimaryModel: raw.Agents.Defaults.Model.Primary,
		Workspace:    raw.Agents.Defaults.Workspace,
	}
}
