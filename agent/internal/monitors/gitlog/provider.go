// Git 仓库扫描 Provider 门面（roots、黑名单、游标、ScanResult）。
//
// gz
package gitlog

import (
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/am/aiwatch-agent/internal/gitinfo"
	"github.com/am/aiwatch-agent/internal/logger"
)

// Provider 是 gitlog 模块的对外门面：管理 cursor、扫描 roots 下的 git repo、
// 应用 blacklist、产出 ScanResult 给 reporter。
type Provider struct {
	roots           []string
	blacklist       []string
	gitAuthorEmails []string
	limits          Limits
	cursor          *cursorState
}

// New 构造一个 Provider。
//
//	cfgRoots 仅含 config.json 显式配置的 gitlog_roots（可为空）。
//	实际扫描目录 = cfgRoots + 会话快照推断的 Git 仓库根（去重）；无内置「默认代码目录」。
//	gitAuthorEmails config.json 可选别名邮箱族，与每仓库 EffectiveGitEmail 取并集后过滤 author_email。
//	blacklist repo_url 子串黑名单（命中即跳过该仓库）。
func New(roots, blacklist, gitAuthorEmails []string, limits Limits) *Provider {
	if limits.MaxFilesPerCommit <= 0 {
		limits = DefaultLimits()
	}
	return &Provider{
		roots:           roots,
		blacklist:       blacklist,
		gitAuthorEmails: gitAuthorEmails,
		limits:          limits,
		cursor:          loadCursor(),
	}
}

// Scan 扫描所有 roots 下的 git repo，返回所有 since-cursor 之后的新提交。
func (p *Provider) Scan() ScanResult {
	repos := discoverRepos(mergeDiscoveryRoots(p.roots))
	out := ScanResult{HeadByRepo: map[string]string{}, ReposDiscovered: len(repos)}
	identitySet := make(map[string]struct{})
	for _, a := range p.gitAuthorEmails {
		if n := NormalizeAuthorEmail(a); n != "" {
			identitySet[n] = struct{}{}
		}
	}

	for _, dir := range repos {
		repoURL := gitinfo.Detect(dir).RepoURL
		if repoURL == "" {
			continue
		}
		if isBlacklisted(repoURL, p.blacklist) {
			continue
		}
		key := cursorKey(repoURL, dir)

		if eff := effectiveGitEmail(dir); eff != "" {
			if n := NormalizeAuthorEmail(eff); n != "" {
				identitySet[n] = struct{}{}
			}
		}

		since := p.cursor.get(key)
		commits, head, err := scanRepo(dir, repoURL, since, p.limits)
		if err != nil || len(commits) == 0 {
			continue
		}

		allowed := allowedEmailsForRepo(dir, p.gitAuthorEmails)
		if len(allowed) == 0 {
			out.ReposSkippedNoIdentity++
			logger.Warnf("gitlog: skipping %d commits from %s (no git config user.email and no git_author_emails); configure Git identity to sync commits",
				len(commits), repoURL)
			continue
		}

		filtered := filterCommitsByAllowedEmails(commits, allowed)
		out.CommitsFilteredByEmail += len(commits) - len(filtered)
		if len(filtered) == 0 {
			if head != "" {
				out.HeadByRepo[key] = head
			}
			continue
		}

		out.Commits = append(out.Commits, filtered...)
		if head != "" {
			out.HeadByRepo[key] = head
		}
	}

	out.ReportedIdentityEmails = sortedStringKeys(identitySet)
	return out
}

func allowedEmailsForRepo(repoDir string, cfgAliases []string) map[string]struct{} {
	allowed := make(map[string]struct{})
	for _, a := range cfgAliases {
		if n := NormalizeAuthorEmail(a); n != "" {
			allowed[n] = struct{}{}
		}
	}
	if eff := effectiveGitEmail(repoDir); eff != "" {
		if n := NormalizeAuthorEmail(eff); n != "" {
			allowed[n] = struct{}{}
		}
	}
	return allowed
}

func filterCommitsByAllowedEmails(commits []Commit, allowed map[string]struct{}) []Commit {
	out := make([]Commit, 0, len(commits))
	for _, c := range commits {
		ae := NormalizeAuthorEmail(c.AuthorEmail)
		if ae == "" {
			continue
		}
		if _, ok := allowed[ae]; ok {
			out = append(out, c)
		}
	}
	return out
}

func sortedStringKeys(m map[string]struct{}) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// Commit 上报成功后调用，把 cursor 推进到 head 并持久化。
func (p *Provider) Commit(headByCursorKey map[string]string) {
	for key, head := range headByCursorKey {
		p.cursor.put(key, head)
	}
	p.cursor.save()
}

// PersistCursorFile 将当前游标落盘（若无条目则写入空的 head_by_repo）。
// 用于删除 cursor.json 后仍能尽快重建文件，并保证每轮 scan 周期末文件存在。
func (p *Provider) PersistCursorFile() {
	p.cursor.save()
}

// cursorKey 是游标的键：repo_url + 工作副本绝对路径。
//
// 只用 repo_url 会让同一个远端的两个 clone / worktree 共用一格游标：A 落盘自己的 HEAD 后，
// 轮到 B 时 sinceHash 不在 B 的历史里，git 报 bad revision，scanRepo 降级成 30 天全量重扫，
// 再把 B 的 HEAD 覆盖回去——两个副本互相踢，每轮都整窗重发，服务端只能靠去重兜着。
func cursorKey(repoURL, repoDir string) string {
	abs, err := filepath.Abs(repoDir)
	if err != nil {
		abs = repoDir
	}
	return repoURL + "#" + filepath.Clean(abs)
}

// discoverRepos：每个 root 若自身是 Git 仓库则收入；否则在其下有限深度递归查找 .git。
func discoverRepos(roots []string) []string {
	var out []string
	seen := map[string]bool{}
	for _, root := range roots {
		root = filepath.Clean(root)
		if _, err := os.Stat(root); err != nil {
			continue
		}
		// 会话推断的路径通常是仓库 toplevel 本身，需显式接受 root/.git。
		if st, err := os.Stat(filepath.Join(root, ".git")); err == nil {
			_ = st
			if !seen[root] {
				out = append(out, root)
				seen[root] = true
			}
		}
		// cfgRoots 仍可能是「父目录」：在其下递归查找嵌套仓库。
		_ = walkLimited(root, 0, 6, func(p string) {
			gitDir := filepath.Join(p, ".git")
			if st, err := os.Stat(gitDir); err == nil {
				// .git 可能是文件（worktree 引用）或目录，都接受
				_ = st
				if !seen[p] {
					out = append(out, p)
					seen[p] = true
				}
			}
		})
	}
	return out
}

// walkLimited 限定深度的目录遍历。命中第一层 .git 之后不再下钻该子树。
func walkLimited(dir string, depth, maxDepth int, onDir func(string)) error {
	if depth > maxDepth {
		return nil
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		name := e.Name()
		if strings.HasPrefix(name, ".") || name == "node_modules" || name == "vendor" {
			continue
		}
		full := filepath.Join(dir, name)
		if _, err := os.Stat(filepath.Join(full, ".git")); err == nil {
			onDir(full)
			continue // .git 已找到则不再下钻该 repo 内部，节省时间
		}
		_ = walkLimited(full, depth+1, maxDepth, onDir)
	}
	return nil
}

func isBlacklisted(repoURL string, blacklist []string) bool {
	if len(blacklist) == 0 {
		return false
	}
	for _, b := range blacklist {
		if b == "" {
			continue
		}
		if strings.Contains(repoURL, b) {
			return true
		}
	}
	return false
}
