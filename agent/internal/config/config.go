// Package config 负责 aiwatchd 本地配置的读取与保存。
//
// 配置文件路径（v2.0 起，AIWatch 设计文档 §18.2）：
//
//	macOS   ~/Library/Application Support/aiwatchd/config.json
//	Windows %ProgramData%\aiwatchd\config.json
//	Linux   ~/.config/aiwatchd/config.json
//
// v1.x 老路径（ai-work-agent/）首次启动时由 LoadFrom/DefaultPath 自动迁移到新路径，
// 员工无感升级 —— 老路径若仍存在，复制到新路径并保留老文件作为兜底。
//
// 配置项区分两类：
//
//	UserCode / ServerURL — 安装时由公司统一注入
//	AgentID / AgentSecret — /register 后由 Agent 写入
//
// gz
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"time"
)

const (
	// DefaultReportIntervalMs 默认上报间隔（毫秒）。推广期默认 2min（空闲基线）。
	DefaultReportIntervalMs = 120000
	// DefaultActiveReportIntervalMs 活跃时段的快速上报间隔（毫秒），默认 15s。
	// 自适应上报：服务端上报响应 active=true（有非 idle 且近 5min 活动的会话）时，reporter
	// 切到这个更短的间隔，让大盘 / 实时页近实时；空闲（active=false）时回落 DefaultReportIntervalMs，
	// 避免全员 24/7 高频上报。仅影响 tick 节奏，不影响状态判定窗口（仍按基线缩放）。
	DefaultActiveReportIntervalMs = 15000
	// DefaultTimestampWindowMs 服务端默认时间戳容忍窗口。
	DefaultTimestampWindowMs = 300000
	// DefaultGitLogIntervalMs gitlog Provider 默认扫描周期（5 分钟）。
	DefaultGitLogIntervalMs = 300000
	// DefaultReportTimeoutMs /report HTTP 客户端超时（毫秒），默认 15 分钟。
	DefaultReportTimeoutMs = 15 * 60 * 1000

	// dirCurrent 是 v2.0 起的配置子目录名（aiwatchd）。
	dirCurrent = "aiwatchd"
	// dirLegacy 是 v1.x 历史路径（ai-work-agent），用于一次性迁移。
	dirLegacy = "ai-work-agent"
)

// Config 是 aiwatchd 本地持久化的配置结构。
type Config struct {
	ServerURL string `json:"server_url"`
	UserCode  string `json:"user_code"`
	// v2.3 员工自助注册：安装时由命令行 --user-name / --department 写入；
	// 服务端 /register 在 employee 表无此 user_code 时按这两个字段创建 ACTIVE 记录。
	// 已存在员工不会被覆盖（HR 已录入的姓名 / 部门是事实来源）。
	UserName          string `json:"user_name,omitempty"`
	Department        string `json:"department,omitempty"`
	AgentID           string `json:"agent_id,omitempty"`
	AgentSecret       string `json:"agent_secret,omitempty"`
	ReportIntervalMs  int64  `json:"report_interval_ms,omitempty"`
	// ActiveReportIntervalMs：活跃时段的快速上报间隔（毫秒）；0 或未配置时用 DefaultActiveReportIntervalMs。
	// 服务端 /register 下发，reporter.Run 据 active 信号在它与 ReportIntervalMs 之间切换 cadence。
	ActiveReportIntervalMs int64 `json:"active_report_interval_ms,omitempty"`
	// ReportTimeoutMs：单次 /api/v1/agent/report HTTP 超时（毫秒）；0 或未配置时用 DefaultReportTimeoutMs。
	ReportTimeoutMs   int64  `json:"report_timeout_ms,omitempty"`
	TimestampWindowMs int64  `json:"timestamp_window_ms,omitempty"`

	// v2.2 Phase 3 起：gitlog Provider 配置
	//
	// GitLogRoots：企业可选的额外扫描根目录（父路径下递归发现嵌套 .git）；默认可不配，仅靠会话推断的仓库根。
	// GitLogBlacklist：repo_url 子串黑名单（命中即跳过该仓库）。
	// GitLogIntervalMs：gitlog 扫描周期，默认 5 分钟。
	GitLogRoots      []string `json:"gitlog_roots,omitempty"`
	GitLogBlacklist  []string `json:"gitlog_blacklist,omitempty"`
	GitLogIntervalMs int64    `json:"gitlog_interval_ms,omitempty"`
	// GitAuthorEmails：可选；Git author 别名 / noreply / 历史邮箱。与每仓库 git config user.email 并集后过滤上报提交。
	GitAuthorEmails []string `json:"git_author_emails,omitempty"`
	// GitLogMaxFilesPerCommit：单 commit 最多保留的文件明细条数，默认 1000。
	GitLogMaxFilesPerCommit int `json:"gitlog_max_files_per_commit,omitempty"`
	// GitLogMaxPatchBytesPerFile：单文件 patch 原始字节上限，默认 1MiB。
	GitLogMaxPatchBytesPerFile int `json:"gitlog_max_patch_bytes_per_file,omitempty"`
	// GitLogMaxPatchBytesPerCommit：单 commit 全部 patch 原始字节上限，默认 10MiB。
	GitLogMaxPatchBytesPerCommit int `json:"gitlog_max_patch_bytes_per_commit,omitempty"`
	// GitLogPatchContextLines：git show -U 行数，默认 999999（相邻 hunk 合并，尽量采全文件 diff）。
	GitLogPatchContextLines int `json:"gitlog_patch_context_lines,omitempty"`
	// GitLogCollectPatch：是否采集 unified diff；false 时仅上报文件元数据。
	GitLogCollectPatch *bool `json:"gitlog_collect_patch,omitempty"`

	// MonitorPolicy 服务端下发的会话类 Provider 采集白名单；nil 表示尚未收到策略（与老服务端兼容，视为全开）。
	MonitorPolicy *MonitorPolicy `json:"monitor_policy,omitempty"`
}

// MonitorPolicy 与服务端 MonitorAgentPolicyDto 对齐（snake_case JSON）。
type MonitorPolicy struct {
	EnabledMonitorTypes []string `json:"enabled_monitor_types,omitempty"`
	Version             int64    `json:"version,omitempty"`
	TtlMs               int64    `json:"ttl_ms,omitempty"`
}

// IsRegistered 返回是否已经从服务端拿到 AgentID + AgentSecret。
func (c *Config) IsRegistered() bool {
	return c.AgentID != "" && c.AgentSecret != ""
}

// ReportTimeout 返回上报 HTTP 客户端超时。config.json 的 report_timeout_ms 优先。
func (c *Config) ReportTimeout() time.Duration {
	if c != nil && c.ReportTimeoutMs > 0 {
		return time.Duration(c.ReportTimeoutMs) * time.Millisecond
	}
	return time.Duration(DefaultReportTimeoutMs) * time.Millisecond
}

// Load 读取配置文件。文件不存在则返回 (zero-value, ErrNotInstalled)。
//
// 包含一次性迁移逻辑：如果新路径不存在但老路径（v1.x）存在，自动把老文件复制过来。
func Load() (*Config, error) {
	path, err := DefaultPath()
	if err != nil {
		return nil, err
	}
	migrateLegacyIfNeeded(path)
	cfg, err := LoadFrom(path)
	if err != nil {
		return nil, err
	}
	// 存量 config 无 report_timeout_ms 时补默认并落盘，便于运维可见、可改。
	if cfg.ReportTimeoutMs <= 0 {
		cfg.ReportTimeoutMs = DefaultReportTimeoutMs
		if saveErr := Save(cfg); saveErr != nil {
			_ = saveErr
		}
	}
	return cfg, nil
}

// LoadFrom 从指定路径读取。
func LoadFrom(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return &Config{}, ErrNotInstalled
		}
		return nil, err
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("parse %s: %w", path, err)
	}
	return &cfg, nil
}

// Save 把配置写到默认路径，使用原子替换（写临时文件 + rename）避免半写。
func Save(cfg *Config) error {
	path, err := DefaultPath()
	if err != nil {
		return err
	}
	return SaveTo(cfg, path)
}

// SaveTo 把配置写到指定路径。
func SaveTo(cfg *Config, path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// StateDir 返回与 config.json 同目录祖级的 state/ 路径（不存在则创建）。
// 用于 session 游标、outbox、gitlog 会话目录索引等落盘数据。
//
//	macOS    ~/Library/Application Support/aiwatchd/state/
//	Windows  %ProgramData%\aiwatchd\state\
//	Linux    ~/.config/aiwatchd/state/
func StateDir() (string, error) {
	cfgPath, err := DefaultPath()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(filepath.Dir(cfgPath), "state")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	return dir, nil
}

// DefaultPath 返回当前平台的默认配置文件路径（v2.0 新路径）。
func DefaultPath() (string, error) {
	return resolvePath(dirCurrent)
}

// LegacyPath 返回 v1.x 老路径（ai-work-agent），仅用于一次性迁移与故障排查。
func LegacyPath() (string, error) {
	return resolvePath(dirLegacy)
}

func resolvePath(dirName string) (string, error) {
	switch runtime.GOOS {
	case "darwin":
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		return filepath.Join(home, "Library", "Application Support", dirName, "config.json"), nil
	case "windows":
		base := os.Getenv("ProgramData")
		if base == "" {
			base = `C:\ProgramData`
		}
		return filepath.Join(base, dirName, "config.json"), nil
	default:
		base, err := os.UserConfigDir()
		if err != nil {
			return "", err
		}
		return filepath.Join(base, dirName, "config.json"), nil
	}
}

// migrateLegacyIfNeeded 在新路径不存在 / 老路径存在时，把 v1.x ai-work-agent 的配置
// 复制到 v2.0 aiwatchd。复制而非移动，老路径保留以便回滚；任何步骤失败都静默跳过，
// 不阻塞 Load 主流程（最坏退化为"未注册"，由调用方走正常 init 流程处理）。
func migrateLegacyIfNeeded(newPath string) {
	if _, err := os.Stat(newPath); err == nil {
		return
	}
	legacy, err := LegacyPath()
	if err != nil {
		return
	}
	data, err := os.ReadFile(legacy)
	if err != nil {
		return
	}
	if err := os.MkdirAll(filepath.Dir(newPath), 0o755); err != nil {
		return
	}
	tmp := newPath + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return
	}
	_ = os.Rename(tmp, newPath)
}

// ErrNotInstalled 表示尚未安装 / 尚未注册（配置文件不存在）。
var ErrNotInstalled = errors.New("aiwatchd config not found, run `aiwatchd init` first")
