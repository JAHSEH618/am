// Package opencode 实现 sst/opencode（2026 初更名 anomalyco/opencode，TS/Bun）的本地会话采集。
//
// 数据源（当前世代 = SQLite，~v1.1.53 起为事实来源）：
//
//	<root>/opencode.db   Bun-sqlite + Drizzle，WAL 模式（伴生 .db-wal / .db-shm），只读打开
//	  session(id, project_id, parent_id, directory, title, version, model(JSON),
//	          cost, tokens_input/output/reasoning/cache_read/cache_write,
//	          time_created/time_updated/time_archived  —— 均为 epoch 毫秒)
//	  message(id, session_id, time_created, data(JSON 整条消息))
//	  part(id, message_id, session_id, data(JSON：text/reasoning/tool/file/step-finish))
//	  project(id, worktree, vcs)
//
// 跨平台路径策略：opencode 走 XDG（npm xdg-basedir），**三平台一律 ~/.local/share/opencode**，
// macOS 不用 ~/Library、Windows 不用 %APPDATA%。env 覆盖：$AM_OPENCODE_DIR（数据根）/ $OPENCODE_DB（db 文件）。
//
// 历史世代（迁移 JSON / 旧 v1 JSON，存于 <root>/storage/）暂不解析：v1 只打 SQLite，
// 老装机若仅有 storage/*.json 则本 Provider 返回空快照（graceful），后续再补 JSON 兜底。
//
// 实现思路与 hermes / cursor Provider 一致：只读打开 SQLite + PRAGMA data_version 快路径。
// gz
package opencode

import (
	"os"
	"path/filepath"
)

// TypeCode 必须与服务端 monitor_target.type_code 对齐。
const TypeCode = "opencode"

// installRoot 返回 opencode 数据根目录（不带尾分隔符）。
//
// 优先级：
//
//	$AM_OPENCODE_DIR   开发/测试覆盖（点到 fixture 目录，期望下面有 opencode.db）
//	$XDG_DATA_HOME/opencode
//	$HOME/.local/share/opencode   默认（macOS / Linux / Windows 同此 XDG 路径）
func installRoot() string {
	if d := os.Getenv("AM_OPENCODE_DIR"); d != "" {
		return d
	}
	if d := os.Getenv("XDG_DATA_HOME"); d != "" {
		return filepath.Join(d, "opencode")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".local", "share", "opencode")
}

// dbPath 返回 <root>/opencode.db。$OPENCODE_DB 是 opencode 原生的 db 路径覆盖，优先级最高。
//
// 返回空串表示当前环境检测不到 opencode 数据目录，Provider 应跳过本次采集。
func dbPath() string {
	if p := os.Getenv("OPENCODE_DB"); p != "" {
		return p
	}
	root := installRoot()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "opencode.db")
}
