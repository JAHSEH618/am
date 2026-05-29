// state.go 解析本机 aiwatchd state 目录路径（与 config 同级的 state/ 子目录），
// 用于游标 / outbox / 安装回溯标记等持久化数据。
//
// gz
package reporter

import "github.com/am/aiwatch-agent/internal/config"

// stateDir 返回本机 aiwatchd state 目录路径，自动创建。
//
// 路径规则（与 config.DefaultPath 同祖父，外加 state/ 子目录）：
//
//	macOS    ~/Library/Application Support/aiwatchd/state/
//	Windows  %ProgramData%\aiwatchd\state\
//	Linux    ~/.config/aiwatchd/state/
func stateDir() (string, error) {
	return config.StateDir()
}
