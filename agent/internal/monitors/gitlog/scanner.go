// 扫描本地 Git 仓库并枚举新提交（gitlog 子模块）。
//
// gz
package gitlog

import (
	"bufio"
	"errors"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// NormalizeAuthorEmail 将 Git author_email 规范化（trim + 全小写），与文档 AllowedEmails 判定一致。
func NormalizeAuthorEmail(s string) string {
	return strings.TrimSpace(strings.ToLower(s))
}

// effectiveGitEmail 读取仓库解析后的 user.email（不设 --global，由 Git 按 local/global 规则解析）。
func effectiveGitEmail(repoDir string) string {
	out, err := exec.Command("git", "-C", repoDir, "config", "user.email").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// 单次 git log 最多回溯多少条；防止初次扫极老仓库时一口气拉几万条。
const maxCommitsPerScan = 200

// 单次 git log 最多回溯多久（重启后避免回溯无限远）。
const lookbackDuration = 30 * 24 * time.Hour

// 单条提交最多保留多少条 path 明细（防爆 payload）；超出部分仍计入汇总行数。
// 默认与 DefaultMaxFilesPerCommit 一致；实际以 scanRepo 传入的 Limits 为准。
const maxPathStatsPerCommit = DefaultMaxFilesPerCommit

// 单路径最大字节长度（截断后入库 / 上报）。
const maxPathStatPathLen = 512

// scanRepo 在一个 repo 目录上跑 git log 并解析提交。
// sinceHash 不为空时只取 sinceHash..HEAD 的新提交（增量）；否则取过去 lookbackDuration 的提交。
//
// 数据格式约定：使用 "%x1f" (US/0x1f) 作字段分隔，"%x1e" (RS/0x1e) 作记录结束，
// 配合 --numstat 单独解析行数；这样字段里包含换行 / 引号 / 分号都不影响。
func scanRepo(repoDir, repoURL, sinceHash string, lim Limits) ([]Commit, string, error) {
	args := []string{
		"-C", repoDir,
		"-c", "core.quotepath=false",
		"log",
		"--no-merges",
		"--date=iso-strict",
		"--pretty=format:%H%x1f%cI%x1f%an%x1f%ae%x1f%s%x1e",
		"--numstat",
		"-n", strconv.Itoa(maxCommitsPerScan),
	}
	if sinceHash != "" {
		args = append(args, sinceHash+"..HEAD")
	} else {
		since := time.Now().Add(-lookbackDuration).Format("2006-01-02")
		args = append(args, "--since="+since)
	}

	cmd := exec.Command("git", args...)
	out, err := cmd.Output()
	if err != nil {
		// sinceHash 不在历史里（rebase / 重写）会导致 fatal: bad revision；
		// 这种情况降级为"无 since 全量回溯 lookbackDuration"。
		var ee *exec.ExitError
		if sinceHash != "" && errors.As(err, &ee) {
			return scanRepo(repoDir, repoURL, "", lim)
		}
		return nil, "", err
	}

	maxPaths := lim.MaxFilesPerCommit
	if maxPaths <= 0 {
		maxPaths = DefaultMaxFilesPerCommit
	}

	branch := currentBranch(repoDir)
	commits := parseLog(string(out), repoURL, branch, maxPaths)
	for i := range commits {
		enrichCommit(repoDir, &commits[i], lim)
	}
	var head string
	if len(commits) > 0 {
		head = commits[0].CommitHash
	}
	return commits, head, nil
}

func parseLog(blob, repoURL, branch string, maxPaths int) []Commit {
	if maxPaths <= 0 {
		maxPaths = DefaultMaxFilesPerCommit
	}
	if blob == "" {
		return nil
	}
	// git 实际输出为：一行表头（%H%x1f…%s%x1e），下一行起为 --numstat，直到下一条表头。
	// 旧实现用 \x1e 分段后只在「同一段」里用首个 \n 切 numstat；表头行本身不含换行，导致 tail 恒为空，行数全为 0。
	var out []Commit
	var cur *Commit
	flush := func() {
		if cur != nil {
			out = append(out, *cur)
			cur = nil
		}
	}
	sc := bufio.NewScanner(strings.NewReader(blob))
	for sc.Scan() {
		line := strings.TrimRight(sc.Text(), "\r")
		if line == "" {
			continue
		}
		if strings.Count(line, "\x1f") >= 4 {
			flush()
			line = strings.TrimSuffix(line, "\x1e")
			parts := strings.SplitN(line, "\x1f", 5)
			if len(parts) < 5 {
				continue
			}
			ts, err := time.Parse(time.RFC3339, parts[1])
			if err != nil {
				continue
			}
			cur = &Commit{
				RepoURL:        repoURL,
				CommitHash:     parts[0],
				CommitTime:     monitor.LocalTime(ts),
				AuthorName:     parts[2],
				AuthorEmail:    parts[3],
				MessageSubject: truncate(parts[4], 256),
				BranchName:     branch,
			}
			continue
		}
		if cur == nil {
			continue
		}
		cols := strings.SplitN(line, "\t", 3)
		if len(cols) < 3 {
			continue
		}
		added, _ := strconv.Atoi(cols[0]) // "-" → 0（二进制文件）
		deleted, _ := strconv.Atoi(cols[1])
		cur.LinesAdded += added
		cur.LinesDeleted += deleted
		cur.FilesChanged++
		if len(cur.PathStats) < maxPaths {
			cur.PathStats = append(cur.PathStats, PathStat{
				Path:         truncatePath(cols[2], maxPathStatPathLen),
				LinesAdded:   added,
				LinesDeleted: deleted,
			})
		}
	}
	flush()
	return out
}

func currentBranch(repoDir string) string {
	out, err := exec.Command("git", "-C", repoDir, "rev-parse", "--abbrev-ref", "HEAD").Output()
	if err != nil {
		return ""
	}
	b := strings.TrimSpace(string(out))
	if b == "HEAD" {
		return "" // detached
	}
	return b
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

func truncatePath(s string, n int) string {
	s = normalizeGitPath(s)
	return truncate(s, n)
}
