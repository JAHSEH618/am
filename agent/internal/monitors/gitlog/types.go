// Package gitlog 是 aiwatchd 的 git 提交流水 Provider（Phase 3）。
//
// 与其他 monitors/ 下的 Provider 不同，gitlog 不参与"实时会话快照"上报，
// 而是周期性地（默认 5 分钟）扫描本机配置的 repo 目录列表，把新提交的
// 元信息（不含 diff）通过独立 endpoint /api/v1/agent/report-commits 上报到
// 服务端。服务端基于"提交时间附近 ±30min 内同 user 在该 repo 是否有 active
// ai_session"推断 ai_assisted。
//
// 隐私：默认上报 hash / 作者 / 时间 / 行数 / 分支 / 标题、逐文件 numstat，以及
// 经 gzip 的 unified diff（受 gitlog_max_patch_* 等配置限制）；可通过 gitlog_blacklist 屏蔽指定 repo。
// author_email 在上报前按 AllowedEmails（每仓库 git config user.email ∪ config.git_author_emails）过滤。
// gz
package gitlog

import "github.com/am/aiwatch-agent/internal/monitor"

// PathStat 单文件 numstat 明细（与 git --numstat 一致，不含内容）。
type PathStat struct {
	Path         string `json:"path"`
	LinesAdded   int    `json:"lines_added"`
	LinesDeleted int    `json:"lines_deleted"`
}

// CommitFile 单文件变更明细（含可选 gzip+base64 patch）。
type CommitFile struct {
	Path             string `json:"path"`
	OldPath          string `json:"old_path,omitempty"`
	ChangeType       string `json:"change_type,omitempty"`
	LinesAdded       int    `json:"lines_added"`
	LinesDeleted     int    `json:"lines_deleted"`
	IsBinary         bool   `json:"is_binary,omitempty"`
	HasPatch         bool   `json:"has_patch,omitempty"`
	PatchGzipB64     string `json:"patch_gzip_base64,omitempty"`
	PatchBytes       int    `json:"patch_bytes,omitempty"`
	PatchTruncated   bool   `json:"patch_truncated,omitempty"`
	TruncateReason   string `json:"truncate_reason,omitempty"`
	SortOrder        int    `json:"sort_order,omitempty"`
}

// Commit 是一条上报到服务端的提交记录。字段命名采用 snake_case JSON
// 与服务端 GitCommitDto / GitCommit entity 对齐。
type Commit struct {
	RepoURL        string            `json:"repo_url"`
	CommitHash     string            `json:"commit_hash"`
	CommitTime     monitor.LocalTime `json:"commit_time"`
	AuthorName     string            `json:"author_name,omitempty"`
	AuthorEmail    string            `json:"author_email,omitempty"`
	MessageSubject string            `json:"message_subject,omitempty"`
	MessageBody    string            `json:"message_body,omitempty"`
	BranchName     string            `json:"branch_name,omitempty"`
	ParentHashes   []string          `json:"parent_hashes,omitempty"`
	IsMerge        bool              `json:"is_merge,omitempty"`
	FilesChanged   int               `json:"files_changed"`
	LinesAdded     int               `json:"lines_added"`
	LinesDeleted   int               `json:"lines_deleted"`
	PathStats      []PathStat        `json:"path_stats,omitempty"`
	Files          []CommitFile      `json:"files,omitempty"`
	DetailStatus   string            `json:"detail_status,omitempty"`
	DetailSkipReason string          `json:"detail_skip_reason,omitempty"`
}

// ScanResult 是单次 Scan 的产物。
type ScanResult struct {
	Commits []Commit
	// 每个 repo 的最新 commit_hash，给上层写回 cursor。key = repo_url
	HeadByRepo map[string]string
	// ReportedIdentityEmails 已规范化的邮箱列表（config.git_author_emails ∪ 各仓库 git config user.email），供服务端与 agent_device.git_user_email 对齐兜底。
	ReportedIdentityEmails []string
}
