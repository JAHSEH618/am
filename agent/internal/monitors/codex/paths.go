// Package codex 实现 OpenAI Codex CLI 的会话采集。
//
// 数据源：~/.codex/sessions/<YYYY>/<MM>/<DD>/rollout-<UUID>.jsonl
//
// 跨平台路径策略：仅以 os.UserHomeDir 为根，三平台路径都是 ~/.codex/sessions；
// Windows Codex CLI 同样写在用户主目录下，不需要单独判断 GOOS。
//
// 解析协议（节选自真实 jsonl，2026-05）：
//
//	{"timestamp":"...","type":"session_meta","payload":{"id":"...","cwd":"...","cli_version":"...","agent_nickname":"..."}}
//	{"timestamp":"...","type":"turn_context","payload":{"cwd":"...","model":"gpt-5","git":{"branch":"main"}}}
//	{"timestamp":"...","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"..."}]}}
//	{"timestamp":"...","type":"response_item","payload":{"type":"function_call","name":"apply_patch","arguments":"..."}}
//	{"timestamp":"...","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":..,"output_tokens":..,...}}}}
//
// 实现思路与 lazyagent codex/process.go（MIT）等价。
// gz
package codex

import (
	"os"
	"path/filepath"
)

func sessionsDir() string {
	if d := os.Getenv("AM_CODEX_DIR"); d != "" {
		return filepath.Join(d, "sessions")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".codex", "sessions")
}

func sessionIndexPath() string {
	if d := os.Getenv("AM_CODEX_DIR"); d != "" {
		return filepath.Join(d, "session_index.jsonl")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".codex", "session_index.jsonl")
}
