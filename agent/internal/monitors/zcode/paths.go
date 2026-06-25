// Package zcode 实现 "Z Code"（z.ai 的 GLM 编码 Agent，Electron 应用，User-Agent: ZCode/x.y.z）
// 的本地会话采集。
//
// 数据源（事实来源 = SQLite，与 opencode 同源的 session/message/part 三表派生）：
//
//	~/.zcode/cli/db/db.sqlite   WAL 模式（伴生 .db-wal / .db-shm），只读打开
//	  session(id="sess_<uuid>", project_id, parent_id, directory(=cwd), title, version,
//	          time_created/time_updated/time_compacting/time_archived  —— 均为 epoch 毫秒；
//	          注意：与 opencode 不同，session 表【没有】token / model 列)
//	  message(id, session_id, time_created, data(JSON 整条消息))
//	  part(id, message_id, session_id, data(JSON：text / step-start / tool …))
//
// 与 opencode 的关键差异（决定本包实现）：
//   - token 不在 session 列里，而是逐 assistant message 的 data.tokens 里（input/output/
//     reasoning/cache.{read,write}，真实值），按会话累加得到总量。zcode 另有 model_usage /
//     turn_usage 两张逐 turn 的真实记账表，本版暂不用（留作后续 cost / 时延等富指标）。
//   - model 同样不在 session 列里，取自 assistant message.data.modelID（如 "GLM-5.2"）。
//
// 跨平台路径：zcode 是 Electron 应用，三平台一律落在 ~/.zcode（macOS 不用 ~/Library、
// Windows 不用 %APPDATA%）。env 覆盖：$AM_ZCODE_DIR（指向 .zcode 根目录）。
// gz
package zcode

import (
	"os"
	"path/filepath"
)

// TypeCode 必须与服务端 monitor_target.type_code 对齐。
const TypeCode = "zcode"

// installRoot 返回 zcode 数据根目录（.zcode，不带尾分隔符）。
//
// 优先级：
//
//	$AM_ZCODE_DIR   开发/测试覆盖（点到 fixture 根，期望其下有 cli/db/db.sqlite）
//	$HOME/.zcode    默认（macOS / Linux / Windows 同此）
func installRoot() string {
	if d := os.Getenv("AM_ZCODE_DIR"); d != "" {
		return d
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".zcode")
}

// dbPath 返回 <root>/cli/db/db.sqlite。
//
// 返回空串表示当前环境检测不到 zcode 数据目录，Provider 应跳过本次采集。
func dbPath() string {
	root := installRoot()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "cli", "db", "db.sqlite")
}
