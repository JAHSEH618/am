// Package claude 实现 Claude Code CLI 的会话采集。
//
// 数据源：~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl
//
//	encoded-cwd  Claude 把工作目录里的 / . 都替换成 -，例如 /Users/gz/projects/am → -Users-gz-projects-am
//	sessionId    UUID，与 jsonl 文件名一致
//
// 跨平台路径：仅以 os.UserHomeDir 为根，三平台都是 ~/.claude/projects；
// Windows 上 Claude Code 也使用 %USERPROFILE%/.claude，无需平台分支。
//
// 解析协议（节选自真实 jsonl 行，2026-05）：
//
//	{"type":"user","message":{"role":"user","content":[{"type":"text","text":"..."}]},
//	 "uuid":"...","sessionId":"...","cwd":"...","gitBranch":"...","timestamp":"...","version":"1.0.0"}
//	{"type":"assistant","message":{"role":"assistant","model":"claude-opus-4-7",
//	 "content":[{"type":"text","text":"..."},{"type":"tool_use","name":"Edit","input":{...}}],
//	 "usage":{"input_tokens":..,"output_tokens":..,"cache_creation_input_tokens":..,"cache_read_input_tokens":..}},
//	 "timestamp":"..."}
//	{"type":"summary",...} 或 {"type":"system","subtype":"compact_boundary",...}
//
// 实现思路与 lazyagent claude/jsonl.go（MIT）保持等价；这里精简掉 lazyagent 的 TUI 专用字段。
// gz
package claude

import (
	"os"
	"path/filepath"
)

// projectsDir 返回 ~/.claude/projects 目录（三平台一致）。
//
// 优先级：
//
//	$AM_CLAUDE_DIR  开发/测试覆盖（点到 fixture 目录）
//	$CLAUDE_CONFIG_DIR  Claude 自家的覆盖变量（与官方 CLI 行为对齐）
//	$HOME/.claude  默认
//
// 返回空串表示当前环境没有 Claude 安装目录，Provider 应当跳过本次采集。
func projectsDir() string {
	if d := os.Getenv("AM_CLAUDE_DIR"); d != "" {
		return filepath.Join(d, "projects")
	}
	if d := os.Getenv("CLAUDE_CONFIG_DIR"); d != "" {
		return filepath.Join(d, "projects")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".claude", "projects")
}
