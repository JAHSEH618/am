// Package hermes 实现 Nous Research Hermes Agent 的本地会话采集。
//
// 数据源：~/.hermes/state.db（SQLite，含 sessions + messages 表 + FTS5 索引）
//
//	sessions(id, source, user_id, model, started_at REAL, ended_at REAL,
//	         message_count, tool_call_count, input_tokens, output_tokens,
//	         cache_read_tokens, cache_write_tokens, reasoning_tokens, title, ...)
//	messages(id, session_id, role, content, tool_calls, tool_name,
//	         timestamp REAL, token_count, finish_reason, ...)
//
// 注：Hermes sessions 表里还有 estimated_cost_usd / actual_cost_usd 列，AIWatch v2.0 起
// 不再使用成本视角（仅记录 token），这两列在 SELECT 中已剔除。
//
// 跨平台：Hermes 官方明确"Native Windows is not supported (use WSL2)"，
// 因此三平台都退回到 os.UserHomeDir 下的 ~/.hermes 即可——WSL2 内 $HOME 也是 unix 风格。
//
// 实现思路与 Cursor Provider 一致：read-only 打开 SQLite，按 (last_activity > cutoff) 过滤。
// gz
package hermes

import (
	"os"
	"path/filepath"
)

// stateDBPath 返回 ~/.hermes/state.db。
//
// 优先级：
//
//	$AM_HERMES_DIR    开发/测试覆盖（点到 fixture 目录，期望下面有 state.db）
//	$HOME/.hermes     默认
//
// 返回空串表示当前环境检测不到 Hermes 安装目录，Provider 应跳过本次采集。
func stateDBPath() string {
	root := installRoot()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "state.db")
}

// installRoot 返回 Hermes 配置/数据目录（不带尾分隔符）。供 IsInstalled / 路径推断共用。
func installRoot() string {
	if d := os.Getenv("AM_HERMES_DIR"); d != "" {
		return d
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".hermes")
}

// checkpointsDir 返回 ~/.hermes/checkpoints。Hermes 把每个任务的 worktree 快照存在这里
// （短 hash 子目录 + 类 git 文件），其中可能写有 HERMES_WORKDIR 文件指向当时的 cwd。
func checkpointsDir() string {
	root := installRoot()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "checkpoints")
}
