// Package common 抽离了所有 monitors/* Provider 的共性能力：
//
//   - worktree 探测（Git 子工作区识别，跨 mac/windows/linux）
//   - JSONL 流式解析 + 文件 mtime/offset 缓存
//   - Token 启发式估算（chars/4）
//   - 文本截断 / 工具名归一化 / 10 态状态机
//
// 任何新的 AI 编码 Agent 接入（claude / codex / cline / amp / opencode 等）
// 都应优先复用本包，只把"读哪个文件、JSON 结构怎么解析"这部分写在 Provider 自己的目录里。
// gz
package common

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// DetectWorktree 判断 path 是否为 git worktree，并在确认时返回主仓库根目录。
//
// 跨平台：依赖系统 PATH 中的 git 可执行文件，三平台行为一致。
//
// 算法：
//  1. git -C <path> rev-parse --git-dir 拿到 .git 目录
//  2. 若 base 是 ".git" 或不是绝对路径 → 普通仓库
//  3. 路径含 ".git/worktrees/<name>" → worktree，向上回溯到主仓库根
//
// 实现参考 lazyagent claude.IsWorktree（MIT），保持行为一致便于复用上游测试用例。
func DetectWorktree(path string) (isWorktree bool, mainRepo string) {
	if path == "" {
		return false, ""
	}
	out, err := exec.Command("git", "-C", path, "rev-parse", "--git-dir").Output()
	if err != nil {
		return false, ""
	}
	gitDir := strings.TrimSpace(string(out))
	if filepath.Base(gitDir) == ".git" || !filepath.IsAbs(gitDir) {
		return false, ""
	}
	parts := strings.Split(gitDir, string(os.PathSeparator))
	for i, p := range parts {
		if p == ".git" && i+1 < len(parts) && parts[i+1] == "worktrees" {
			return true, filepath.Join(parts[:i]...)
		}
	}
	return true, ""
}
