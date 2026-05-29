// Git 路径规范化：core.quotepath 开启时 numstat 会输出 C 风格转义（\345…），入库前还原为 UTF-8。
//
// gz
package gitlog

import (
	"strconv"
	"strings"
)

// normalizeGitPath 将 git log --numstat 的路径列还原为人类可读形式。
// 典型输入："\"docs/\\344\\270\\255\\346\\226\\207.md\"" → docs/中文.md
func normalizeGitPath(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return s
	}
	if unq, err := strconv.Unquote(s); err == nil {
		return unq
	}
	// 仅首尾引号、内部未完全合法时再包一层尝试
	if len(s) >= 2 && s[0] == '"' && s[len(s)-1] == '"' {
		if unq, err := strconv.Unquote(s); err == nil {
			return unq
		}
	}
	return s
}
