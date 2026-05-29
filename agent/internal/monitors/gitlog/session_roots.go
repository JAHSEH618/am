// 由 AI 会话快照推断 Git 仓库根目录，合并进 gitlog 扫描（map 按键去重，无需员工配置默认根目录）。
//
// gz
package gitlog

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/gitinfo"
	"github.com/am/aiwatch-agent/internal/logger"
	"github.com/am/aiwatch-agent/internal/monitor"
)

const (
	sessionRootsJSON = "gitlog_session_roots.json"
	maxSessionRoots  = 256
	// sessionRootTTL 超过该时间未再出现在会话快照中的仓库根路径会被丢弃。
	sessionRootTTL = 180 * 24 * time.Hour
)

type sessionRootsDisk struct {
	Repos map[string]string `json:"repos"` // abs path -> last_seen RFC3339 (UTC)
}

func sessionRootsPath() (string, error) {
	dir, err := config.StateDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, sessionRootsJSON), nil
}

// RecordSessionRepoRoots 从本轮会话快照提取 Git 仓库根目录并落盘，供 gitlog 下一轮 discover 使用。
// 与会话上报解耦：调用失败静默（下次 tick 再试）。
func RecordSessionRepoRoots(monitors []monitor.Snapshot) {
	path, err := sessionRootsPath()
	if err != nil {
		return
	}
	store := loadSessionRootsFile(path)
	if store.Repos == nil {
		store.Repos = make(map[string]string)
	}
	nowStr := time.Now().UTC().Format(time.RFC3339)
	for _, snap := range monitors {
		for _, sess := range snap.Sessions {
			if sess.Cwd == "" {
				continue
			}
			root := gitinfo.TopLevel(sess.Cwd)
			if root == "" {
				continue
			}
			store.Repos[root] = nowStr
		}
	}
	pruneSessionRootsMap(store.Repos, time.Now())
	saveSessionRootsFile(path, store)
}

func mergeDiscoveryRoots(cfgRoots []string) []string {
	session := loadPersistedSessionRootDirs()
	merged := make([]string, 0, len(cfgRoots)+len(session))
	seen := make(map[string]struct{})
	for _, r := range cfgRoots {
		r = filepath.Clean(r)
		if r == "" {
			continue
		}
		if _, ok := seen[r]; ok {
			continue
		}
		seen[r] = struct{}{}
		merged = append(merged, r)
	}
	for _, r := range session {
		r = filepath.Clean(r)
		if r == "" {
			continue
		}
		if _, ok := seen[r]; ok {
			continue
		}
		seen[r] = struct{}{}
		merged = append(merged, r)
	}
	return merged
}

func loadPersistedSessionRootDirs() []string {
	path, err := sessionRootsPath()
	if err != nil {
		return nil
	}
	store := loadSessionRootsFile(path)
	if len(store.Repos) == 0 {
		return nil
	}
	nBefore := len(store.Repos)
	pruneSessionRootsMap(store.Repos, time.Now())
	if len(store.Repos) != nBefore {
		saveSessionRootsFile(path, store)
	}
	out := make([]string, 0, len(store.Repos))
	for p := range store.Repos {
		out = append(out, p)
	}
	sort.Strings(out)
	return out
}

func loadSessionRootsFile(path string) sessionRootsDisk {
	var st sessionRootsDisk
	data, err := os.ReadFile(path)
	if err != nil {
		return st
	}
	if err := json.Unmarshal(data, &st); err != nil {
		logger.Warnf("gitlog session_roots: parse %s: %v", path, err)
		return sessionRootsDisk{Repos: map[string]string{}}
	}
	if st.Repos == nil {
		st.Repos = map[string]string{}
	}
	return st
}

func saveSessionRootsFile(path string, st sessionRootsDisk) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		logger.Warnf("gitlog session_roots: mkdir: %v", err)
		return
	}
	data, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return
	}
	if prev, err := os.ReadFile(path); err == nil && string(prev) == string(data) {
		return
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		logger.Warnf("gitlog session_roots: write tmp: %v", err)
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		logger.Warnf("gitlog session_roots: rename: %v", err)
	}
}

func pruneSessionRootsMap(repos map[string]string, now time.Time) {
	if len(repos) == 0 {
		return
	}
	cutoff := now.Add(-sessionRootTTL)
	for k, v := range repos {
		ts, err := time.Parse(time.RFC3339, v)
		if err != nil || ts.Before(cutoff) {
			delete(repos, k)
		}
	}
	if len(repos) <= maxSessionRoots {
		return
	}
	type kv struct {
		path string
		t    time.Time
	}
	list := make([]kv, 0, len(repos))
	for k, v := range repos {
		ts, _ := time.Parse(time.RFC3339, v)
		list = append(list, kv{k, ts})
	}
	sort.Slice(list, func(i, j int) bool { return list[i].t.Before(list[j].t) })
	overflow := len(list) - maxSessionRoots
	for i := 0; i < overflow; i++ {
		delete(repos, list[i].path)
	}
}
