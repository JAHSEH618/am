// Cursor 工具调用记录格式化。
//
// gz
package cursor

import "strings"

// normalizeToolName 将 Cursor 原始工具名归一化（对齐设计文档 v1.3 §1.3 / lazyagent）。
//
// 真实采样自 state.vscdb（2026-05）的工具名包括：
//
//	run_terminal_command_v2 / read_file_v2 / edit_file_v2 / write_file_v2 / list_dir_v2
//	codebase_search / grep / glob_file_search / web_search / web_view
func normalizeToolName(name string) string {
	switch strings.ToLower(name) {
	case "shell", "bash", "run_terminal_command", "run_terminal_command_v2":
		return "Bash"
	case "read", "read_file", "read_file_v2":
		return "Read"
	case "edit", "edit_file", "edit_file_v2", "apply_patch":
		return "Edit"
	case "write", "write_to_file", "write_file", "write_to_file_v2", "write_file_v2":
		return "Write"
	case "glob", "glob_file_search", "list_dir", "list_dir_v2":
		return "Glob"
	case "grep", "grep_search", "codebase_search", "ripgrep_raw_search", "ripgrep":
		return "Grep"
	case "web_search", "web_view", "websearch":
		return "WebSearch"
	case "web_fetch", "webfetch":
		return "WebFetch"
	case "agent", "subagent", "task", "task_v2":
		return "Agent"
	}
	if name == "" {
		return ""
	}
	return strings.ToUpper(name[:1]) + name[1:]
}
