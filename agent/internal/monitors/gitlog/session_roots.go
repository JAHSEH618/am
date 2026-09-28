// 由 AI 会话快照推断 Git 仓库根目录，合并进 gitlog 扫描（map 按键去重，无需员工配置默认根目录）。
//
// gz
package gitlog

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"sync"
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

	// topLevelTTL / maxTopLevelCache：cwd → 仓库根的进程内缓存。每个 tick 窗口内（48h）的全部会话都会
	// 走到这里，不缓存就是每会话每 tick fork 一次 git rev-parse（活跃节奏 15s 一轮）。仓库根几乎不变，
	// 1h 足够跟上"新 clone / git init"；上限防止 cwd 极多时常驻增长。
	topLevelTTL      = time.Hour
	maxTopLevelCache = 1024
)

type sessionRootsDisk struct {
	// abs path -> last_seen RFC3339 (UTC)。只写到"天"（当日 00:00:00Z）：同一天重复出现不改值，
	// 文件只在新增仓库或跨天时重写；旧版本写的秒级时间戳照常解析。
	Repos map[string]string `json:"repos"`
}

// sessionRootsMu 串行化 gitlog_session_roots.json 的读-改-写：上报 tick（RecordSessionRepoRoots）
// 与 gitlog 扫描（loadPersistedSessionRootDirs 的 prune）在不同 goroutine，且共用同一个 .tmp 路径。
var sessionRootsMu sync.Mutex

type topLevelEntry struct {
	root string
	at   time.Time
}

// topLevelCache 记忆 cwd → gitinfo.TopLevel 结果（含"不在仓库内"的空结果），按 ttl 过期。
type topLevelCache struct {
	mu     sync.Mutex
	m      map[string]topLevelEntry
	ttl    time.Duration
	max    int
	lookup func(string) string
}

func newTopLevelCache(ttl time.Duration, max int, lookup func(string) string) *topLevelCache {
	return &topLevelCache{m: make(map[string]topLevelEntry), ttl: ttl, max: max, lookup: lookup}
}

var sessionTopLevels = newTopLevelCache(topLevelTTL, maxTopLevelCache, gitinfo.TopLevel)

func (c *topLevelCache) get(cwd string, now time.Time) string {
	c.mu.Lock()
	e, ok := c.m[cwd]
	c.mu.Unlock()
	if ok && now.Sub(e.at) < c.ttl {
		return e.root
	}
	// fork 在锁外做：调用方（上报 tick）是单 goroutine，偶发重复查询无害。
	root := c.lookup(cwd)
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, exists := c.m[cwd]; !exists && len(c.m) >= c.max {
		for k, v := range c.m {
			if now.Sub(v.at) >= c.ttl {
				delete(c.m, k)
			}
		}
		if len(c.m) >= c.max {
			c.m = make(map[string]topLevelEntry, c.max)
		}
	}
	c.m[cwd] = topLevelEntry{root: root, at: now}
	return root
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
	recordSessionRepoRoots(path, monitors, time.Now(), sessionTopLevels)
}

// recordSessionRepoRoots 返回本次是否重写了文件（仅新增仓库根、某根跨天、或 prune 删掉条目时才写）。
func recordSessionRepoRoots(path string, monitors []monitor.Snapshot, now time.Time, tl *topLevelCache) bool {
	roots := make(map[string]struct{})
	seenCwd := make(map[string]struct{})
	for _, snap := range monitors {
		for _, sess := range snap.Sessions {
			if sess.Cwd == "" {
				continue
			}
			if _, ok := seenCwd[sess.Cwd]; ok {
				continue
			}
			seenCwd[sess.Cwd] = struct{}{}
			if root := tl.get(sess.Cwd, now); root != "" {
				roots[root] = struct{}{}
			}
		}
	}

	sessionRootsMu.Lock()
	defer sessionRootsMu.Unlock()
	store := loadSessionRootsFile(path)
	if store.Repos == nil {
		store.Repos = make(map[string]string)
	}
	day := now.UTC().Truncate(24 * time.Hour)
	changed := false
	for root := range roots {
		if prev, ok := store.Repos[root]; ok {
			if ts, err := time.Parse(time.RFC3339, prev); err == nil && ts.UTC().Truncate(24*time.Hour).Equal(day) {
				continue
			}
		}
		store.Repos[root] = day.Format(time.RFC3339)
		changed = true
	}
	nBefore := len(store.Repos)
	pruneSessionRootsMap(store.Repos, now)
	if !changed && len(store.Repos) == nBefore {
		return false
	}
	saveSessionRootsFile(path, store)
	return true
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
	sessionRootsMu.Lock()
	defer sessionRootsMu.Unlock()
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
