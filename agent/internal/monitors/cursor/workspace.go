// Cursor 工作区路径与元数据。
//
// gz
package cursor

import (
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// detectWorktree 是 common.DetectWorktree 的薄包装，保留以减少调用点改动。
func detectWorktree(path string) (bool, string) {
	return common.DetectWorktree(path)
}

// 路径抽取正则。
//
// fileURIRe   匹配 `file:///...`（标准 URI）
// posixPathRe 匹配裸 POSIX 绝对路径，至少两层（如 /Users/gz/...、/home/foo/bar）
// winPathRe   匹配裸 Windows 绝对路径（如 C:\Users\foo\bar）
var (
	fileURIRe   = regexp.MustCompile(`file:///[^\s"\\]+`)
	posixPathRe = regexp.MustCompile(`/(?:Users|home|opt|var|srv|root|workspace|workspaces)/[A-Za-z0-9._\-/+%]+`)
	winPathRe   = regexp.MustCompile(`[A-Za-z]:\\[A-Za-z0-9._\-\\ +%]+`)
)

// collectPathHints 从 raw JSON 文本中提取所有可能的绝对路径，去重写入 *out。
//
// 上限避免单条 bubble 文本爆炸（read_file 的 result 可能很长）。
func collectPathHints(raw string, out *[]string) {
	if raw == "" {
		return
	}
	const perBubbleLimit = 30
	collected := 0
	addRaw := func(p string) {
		if collected >= perBubbleLimit {
			return
		}
		decoded, err := url.PathUnescape(strings.TrimPrefix(p, "file://"))
		if err != nil {
			decoded = p
		}
		*out = append(*out, decoded)
		collected++
	}
	for _, m := range fileURIRe.FindAllString(raw, perBubbleLimit) {
		addRaw(m)
	}
	for _, m := range posixPathRe.FindAllString(raw, perBubbleLimit) {
		addRaw(m)
	}
	for _, m := range winPathRe.FindAllString(raw, perBubbleLimit) {
		addRaw(m)
	}
}

// pathBlacklist 用户/系统全局路径，不能用作项目根候选。
var pathBlacklist = []string{
	"/Users/", // 仅用户根本身（带尾斜杠精确匹配）
	"/home/",
}

// inferWorkspace 推断项目根目录：对每条候选路径向上找 marker，出现频率最高的目录视为项目根。
//
// 两轮策略：
//  1. 优先投 `.git`（强信号，monorepo 顶层 git；忽略子模块 build 目录的弱 marker）
//  2. 没有 `.git` 才退化到 build.gradle / go.mod / package.json 等子项目 marker
//
// 这样既能正确识别 monorepo 顶层（如 am 同时含 server/build.gradle 与 agent/go.mod，
// 但顶层 .git 才是项目根），又能在没有 git 的目录里找到 IDEA / 子模块根。
func inferWorkspace(paths []string) string {
	if len(paths) == 0 {
		return ""
	}
	if root := voteProjectRoot(paths, []string{".git"}); root != "" {
		return root
	}
	return voteProjectRoot(paths, []string{
		"package.json", "go.mod", "Cargo.toml", "pyproject.toml",
		"build.gradle", "build.gradle.kts", "pom.xml",
	})
}

func voteProjectRoot(paths, markers []string) string {
	votes := make(map[string]int)
	for _, p := range paths {
		if root := findProjectRootByMarkers(p, markers); root != "" {
			votes[root]++
		}
	}
	if len(votes) == 0 {
		return ""
	}
	var bestRoot string
	bestVotes := -1
	for root, n := range votes {
		if n > bestVotes || (n == bestVotes && len(root) > len(bestRoot)) {
			bestRoot = root
			bestVotes = n
		}
	}
	return bestRoot
}

// findProjectRootByMarkers 从 path 起向上寻找含指定 marker 的最近目录。
//
// 不会跨过用户主目录（/Users/<name> 或 /home/<name>），避免错误地把整个用户根当项目。
func findProjectRootByMarkers(path string, markers []string) string {
	if path == "" {
		return ""
	}
	dir := path
	if fi, err := os.Stat(dir); err == nil && !fi.IsDir() {
		dir = filepath.Dir(dir)
	} else if err != nil {
		// 文件不存在也按 dirname 走（路径来自 Cursor 历史，可能已删除）
		dir = filepath.Dir(dir)
	}
	for {
		if isUserRoot(dir) {
			return ""
		}
		for _, marker := range markers {
			if _, err := os.Stat(filepath.Join(dir, marker)); err == nil {
				return dir
			}
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return ""
		}
		dir = parent
	}
}

// isUserRoot 防止把 /Users/gz 这种用户主目录当成项目根。
func isUserRoot(dir string) bool {
	d := strings.TrimSuffix(filepath.ToSlash(dir), "/")
	for _, prefix := range pathBlacklist {
		base := strings.TrimSuffix(prefix, "/")
		if d == base {
			return true
		}
		// 用户主目录形式 /Users/<name> 或 /home/<name>，在两层就停
		if strings.HasPrefix(d+"/", prefix) {
			rest := strings.TrimPrefix(d+"/", prefix)
			rest = strings.TrimSuffix(rest, "/")
			if rest != "" && !strings.Contains(rest, "/") {
				return true
			}
		}
	}
	return false
}
