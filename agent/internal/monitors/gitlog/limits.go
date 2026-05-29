// gitlog 采集上限默认值与 config 解析。
//
// gz
package gitlog

import "github.com/am/aiwatch-agent/internal/config"

const (
	DefaultMaxFilesPerCommit      = 1000
	DefaultMaxPatchBytesPerFile   = 1 << 20  // 1 MiB
	DefaultMaxPatchBytesPerCommit = 10 << 20 // 10 MiB
	DefaultPatchContextLines      = 999999
)

// Limits 控制明细与 patch 采集体量。
type Limits struct {
	MaxFilesPerCommit      int
	MaxPatchBytesPerFile   int
	MaxPatchBytesPerCommit int
	PatchContextLines      int
	CollectPatch           bool
}

// DefaultLimits 返回产品默认上限。
func DefaultLimits() Limits {
	return Limits{
		MaxFilesPerCommit:      DefaultMaxFilesPerCommit,
		MaxPatchBytesPerFile:   DefaultMaxPatchBytesPerFile,
		MaxPatchBytesPerCommit: DefaultMaxPatchBytesPerCommit,
		PatchContextLines:      DefaultPatchContextLines,
		CollectPatch:           true,
	}
}

// LimitsFromConfig 从 config.json 解析；0 或未配置字段使用 DefaultLimits。
func LimitsFromConfig(cfg *config.Config) Limits {
	lim := DefaultLimits()
	if cfg == nil {
		return lim
	}
	if cfg.GitLogMaxFilesPerCommit > 0 {
		lim.MaxFilesPerCommit = cfg.GitLogMaxFilesPerCommit
	}
	if cfg.GitLogMaxPatchBytesPerFile > 0 {
		lim.MaxPatchBytesPerFile = cfg.GitLogMaxPatchBytesPerFile
	}
	if cfg.GitLogMaxPatchBytesPerCommit > 0 {
		lim.MaxPatchBytesPerCommit = cfg.GitLogMaxPatchBytesPerCommit
	}
	if cfg.GitLogPatchContextLines > 0 {
		lim.PatchContextLines = cfg.GitLogPatchContextLines
	}
	if cfg.GitLogCollectPatch != nil {
		lim.CollectPatch = *cfg.GitLogCollectPatch
	}
	return lim
}
