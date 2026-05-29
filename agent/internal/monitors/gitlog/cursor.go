// git 上报游标持久化（按 repo_url 记录 last_commit_hash）。
//
// gz
package gitlog

import (
	"encoding/json"
	"maps"
	"os"
	"path/filepath"
	"runtime"
	"sync"

	"github.com/am/aiwatch-agent/internal/logger"
)

// cursorState 把每个 repo 的 last_commit_hash 持久化到本地缓存文件，
// 让 aiwatchd 重启后增量扫描不丢点。
type cursorState struct {
	mu   sync.Mutex
	path string
	// repo_url -> last commit hash already reported
	HeadByRepo map[string]string `json:"head_by_repo"`
}

func loadCursor() *cursorState {
	cs := &cursorState{HeadByRepo: map[string]string{}}
	cs.path = cursorPath()
	if cs.path == "" {
		return cs
	}
	data, err := os.ReadFile(cs.path)
	if err != nil {
		return cs
	}
	_ = json.Unmarshal(data, cs)
	if cs.HeadByRepo == nil {
		cs.HeadByRepo = map[string]string{}
	}
	return cs
}

func (c *cursorState) get(repoURL string) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.HeadByRepo[repoURL]
}

func (c *cursorState) put(repoURL, hash string) {
	if hash == "" {
		return
	}
	c.mu.Lock()
	c.HeadByRepo[repoURL] = hash
	c.mu.Unlock()
}

// cursorPayload 落盘 JSON 形态（仅 head_by_repo，避免 Marshal 整个 cursorState）。
type cursorPayload struct {
	HeadByRepo map[string]string `json:"head_by_repo"`
}

// save 原子写。失败打 WARN（便于排查删文件后不重生等问题）。
func (c *cursorState) save() {
	c.mu.Lock()
	path := c.path
	snapshot := maps.Clone(c.HeadByRepo)
	if snapshot == nil {
		snapshot = map[string]string{}
	}
	c.mu.Unlock()

	if path == "" {
		logger.Warnf("gitlog cursor: skip save (cursor path empty)")
		return
	}
	payload := cursorPayload{HeadByRepo: snapshot}
	data, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		logger.Warnf("gitlog cursor: marshal: %v", err)
		return
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		logger.Warnf("gitlog cursor: mkdir %s: %v", filepath.Dir(path), err)
		return
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		logger.Warnf("gitlog cursor: write %s: %v", tmp, err)
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		logger.Warnf("gitlog cursor: rename %s -> %s: %v", tmp, path, err)
	}
}

// cursorPath 返回用户缓存目录下 aiwatchd/gitlog/cursor.json：
//   - macOS：一般为 ~/Library/Caches/aiwatchd/gitlog/cursor.json（UserCacheDir）
//   - Linux：XDG_CACHE_HOME 或 ~/.cache/...
//   - Windows：%LOCALAPPDATA%/aiwatchd/gitlog/cursor.json
func cursorPath() string {
	switch runtime.GOOS {
	case "windows":
		base := os.Getenv("LOCALAPPDATA")
		if base == "" {
			home, err := os.UserHomeDir()
			if err != nil {
				return ""
			}
			base = filepath.Join(home, "AppData", "Local")
		}
		return filepath.Join(base, "aiwatchd", "gitlog", "cursor.json")
	default:
		base, err := os.UserCacheDir()
		if err != nil {
			home, err := os.UserHomeDir()
			if err != nil {
				return ""
			}
			base = filepath.Join(home, ".cache")
		}
		return filepath.Join(base, "aiwatchd", "gitlog", "cursor.json")
	}
}
