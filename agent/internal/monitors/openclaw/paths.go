// Package openclaw 实现 OpenClaw（基于 Hermes Agent 的桌面/CLI Agent）的本地会话采集。
//
// 数据源：
//
//	~/.openclaw/openclaw.json                                 主配置（model、workspace、profiles）—— IsInstalled 指纹
//	~/.openclaw/agents/<agent>/sessions/<sessionId>.jsonl     真实对话流水（jsonl，每行一个事件）
//	~/.openclaw/agents/<agent>/sessions/sessions.json         "agent 当前活跃 session" 索引（v3 形式）
//	~/.openclaw/workspace/                                    默认 cwd（openclaw.json 的 agents.defaults.workspace）
//
// 注意 ~/.openclaw/tasks/runs.sqlite 不是会话数据，是 task 调度的瞬时表（task 完成 + cleanup_after 到期就会被删除），
// 不能作为长期回溯依据；老实现走 SQLite 在 mac 本地始终是 0 行就是这个原因。
//
// 跨平台：CLI 同时支持 macOS / Linux / Windows，三平台都是 ~/.openclaw（Win 下即 %USERPROFILE%\.openclaw），
// Go 通过 os.UserHomeDir + filepath.Join 自动处理分隔符差异。
// gz
package openclaw

import (
	"os"
	"path/filepath"
)

// installRoot 返回 ~/.openclaw（不带尾分隔符），未配置时返回空。
//
// 优先级：$AM_OPENCLAW_DIR > $HOME/.openclaw
func installRoot() string {
	if d := os.Getenv("AM_OPENCLAW_DIR"); d != "" {
		return d
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".openclaw")
}

// configPath 返回 ~/.openclaw/openclaw.json，存在该文件即视为已安装 OpenClaw。
func configPath() string {
	root := installRoot()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "openclaw.json")
}

// agentsRoot 返回 ~/.openclaw/agents，下面是 <agent>/sessions/<uuid>.jsonl 的两层布局。
func agentsRoot() string {
	root := installRoot()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "agents")
}

// defaultWorkspace 返回 ~/.openclaw/workspace（OpenClaw 默认 cwd，session 行里 cwd 为 "/" 时的兜底）。
func defaultWorkspace() string {
	root := installRoot()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "workspace")
}

// isLiveSessionFile 判断文件名是否是 OpenClaw 当前活跃 session 文件。
//
// 排除：
//
//	*.jsonl.reset.<iso> 历史归档（OpenClaw 在 /reset 时把旧 jsonl 重命名加 .reset.<iso> 后缀）
//	非 .jsonl 后缀（避免误读 sessions.json 索引文件）
//
// 注意 filepath.Ext("foo.jsonl.reset.2026-05-07T03-26-17.924Z") 返回 ".924Z"，
// 所以严格 == ".jsonl" 即可正确滤掉 reset 文件。
func isLiveSessionFile(name string) bool {
	return filepath.Ext(name) == ".jsonl"
}
