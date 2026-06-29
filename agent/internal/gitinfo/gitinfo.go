// Package gitinfo 负责读取指定目录的 Git remote 与 branch 信息。
//
// 对应设计文档 §8.2。读取方式：
//
//	git remote get-url origin
//	git branch --show-current
//	git rev-parse --show-toplevel
//
// 若未配置 origin（常见本地 clone / git init 实验仓），RepoURL 退回为基于仓库
// toplevel 路径的稳定伪地址（local:<sha256 前缀>），以便 gitlog 上报与 ai_session
// 的 repo 关联仍可用同一锚点。
//
// 如果目录不在 Git 仓库下，所有字段返回零值，不返回错误。
// gz
package gitinfo

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/procutil"
)

// Info 描述 Git 项目识别结果。
type Info struct {
	ProjectName     string
	RepoURL         string
	BranchName      string
	ProjectPathHash string
}

// Detect 尝试在 dir 下读取 Git 信息。dir 为空时使用当前工作目录。
func Detect(dir string) Info {
	out := Info{}
	if dir != "" {
		out.ProjectPathHash = hashPath(dir)
	}
	root := runGit(dir, "rev-parse", "--show-toplevel")
	if root == "" {
		return out
	}
	out.ProjectName = filepath.Base(root)
	origin := runGit(dir, "remote", "get-url", "origin")
	if origin != "" {
		out.RepoURL = origin
	} else {
		out.RepoURL = pseudoLocalRepoURL(root)
	}
	out.BranchName = runGit(dir, "branch", "--show-current")
	if out.ProjectPathHash == "" {
		out.ProjectPathHash = hashPath(root)
	}
	return out
}

// TopLevel 返回 dir 所在 Git 仓库根目录的绝对路径；不在仓库内则返回空字符串。
func TopLevel(dir string) string {
	if dir == "" {
		return ""
	}
	root := runGit(dir, "rev-parse", "--show-toplevel")
	if root == "" {
		return ""
	}
	abs, err := filepath.Abs(root)
	if err != nil {
		return filepath.Clean(root)
	}
	return abs
}

func runGit(dir string, args ...string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	cmd := procutil.Hidden(exec.CommandContext(ctx, "git", args...))
	if dir != "" {
		cmd.Dir = dir
	}
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func hashPath(p string) string {
	abs, err := filepath.Abs(p)
	if err != nil {
		abs = p
	}
	sum := sha256.Sum256([]byte(abs))
	return "sha256-" + hex.EncodeToString(sum[:8])
}

// pseudoLocalRepoURL 为无 origin 的仓库生成稳定、可复现的 repo 锚点（跨会话与 gitlog 一致）。
func pseudoLocalRepoURL(gitTopLevel string) string {
	abs, err := filepath.Abs(gitTopLevel)
	if err != nil {
		abs = gitTopLevel
	}
	sum := sha256.Sum256([]byte(strings.ToLower(abs)))
	return "local:" + hex.EncodeToString(sum[:16])
}
