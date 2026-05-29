// Package openharness 实现 HKUDS OpenHarness 的本地会话采集。
//
// 数据源：
//
//	~/.openharness/settings.json                      主配置（model / profile）—— IsInstalled 指纹
//	~/.openharness/data/sessions/<userhash>/session-<hex>.json   每个会话一个 JSON
//	~/.openharness/data/sessions/<userhash>/latest.json          指向最新 session 的拷贝（应跳过）
//
// 跨平台：OpenHarness 是 CLI 工具（HKUDS Python 项目），三平台都用 ~/.openharness。
// gz
package openharness

import (
	"os"
	"path/filepath"
)

const sessionFilePrefix = "session-"

// installRoot 返回 ~/.openharness（不带尾分隔符），未配置时返回空。
func installRoot() string {
	if d := os.Getenv("AM_OPENHARNESS_DIR"); d != "" {
		return d
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".openharness")
}

// settingsPath 返回 ~/.openharness/settings.json，存在该文件即视为已安装。
func settingsPath() string {
	root := installRoot()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "settings.json")
}

// sessionsRoot 返回 ~/.openharness/data/sessions（按 userhash 划分子目录）。
func sessionsRoot() string {
	root := installRoot()
	if root == "" {
		return ""
	}
	return filepath.Join(root, "data", "sessions")
}

// isSessionFile 判断给定文件名是否是单个会话存档（排除 latest.json 这类指针）。
func isSessionFile(name string) bool {
	if filepath.Ext(name) != ".json" {
		return false
	}
	if len(name) <= len(sessionFilePrefix) {
		return false
	}
	return name[:len(sessionFilePrefix)] == sessionFilePrefix
}
