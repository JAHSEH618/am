// Package kimicode Provider（monitor.Snapshot），扫描 Kimi Code wire.jsonl 会话。
// gz
package kimicode

import (
	"context"
	"io/fs"
	"path/filepath"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/gitinfo"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// Provider 实现 monitor.Provider，扫描 Kimi Code 本地 wire.jsonl。
type Provider struct {
	cache   *common.FileCache[*parsedSession]
	wtMu    sync.Mutex
	wtCache map[string]struct {
		isWorktree bool
		mainRepo   string
	}
	gitMu    sync.Mutex
	gitCache map[string]gitinfo.Info
	watchDir string
	lookback time.Duration
}

// New 创建 Kimi Code Provider。watchDir 用作 Git 探测兜底。
func New(watchDir string) *Provider {
	return &Provider{
		cache: common.NewFileCache[*parsedSession](),
		wtCache: make(map[string]struct {
			isWorktree bool
			mainRepo   string
		}),
		gitCache: make(map[string]gitinfo.Info),
		watchDir: watchDir,
		lookback: monitor.DefaultLookback,
	}
}

// SetLookback 切换扫描时间窗。详见 monitor.LookbackSetter。
func (p *Provider) SetLookback(d time.Duration) {
	if d <= 0 {
		d = monitor.DefaultLookback
	}
	p.lookback = d
}

func (p *Provider) Type() string { return TypeCode }

// IsInstalled 以任一会话根目录存在判断 Kimi Code 是否安装。
func (p *Provider) IsInstalled() bool {
	return anyRootExists()
}

func (p *Provider) TargetVersion() string {
	return "kimi-code"
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx
	roots := sessionRoots()
	now := time.Now()

	cutoff := now.Add(-p.lookback)
	jobs, hits, seen := p.collectJobs(roots, cutoff)
	results := common.RunParallelParse(jobs, p.parseOne)

	files := make([]*parsedSession, 0, len(hits)+len(results))
	files = append(files, hits...)
	for _, r := range results {
		if r.Value == nil {
			continue
		}
		p.cache.Put(r.Path, r.MTime, r.NewOffset, r.Value)
		files = append(files, r.Value)
	}
	p.cache.Prune(seen)

	merged := mergeBySession(files)
	workdirs := loadWorkdirs(sessionIndexPaths())

	sessions := make([]monitor.Session, 0, len(merged))
	for _, ps := range merged {
		if ps == nil || ps.SessionID == "" {
			continue
		}
		if !ps.LastActivity.IsZero() && ps.LastActivity.Before(cutoff) {
			continue
		}
		if ps.CWD == "" {
			ps.CWD = workdirs[ps.SessionID]
		}
		if ps.Title == "" && ps.WirePath != "" {
			ps.Title = loadTitle(stateJSONPath(ps.WirePath))
		}
		sessions = append(sessions, p.toMonitor(ps, now))
	}

	return monitor.Snapshot{
		Type:          TypeCode,
		TargetVersion: p.TargetVersion(),
		CapturedAt:    monitor.Now(),
		Sessions:      sessions,
	}, nil
}

// collectJobs 在所有会话根下递归找 wire.jsonl（忽略 context.jsonl / session_index.jsonl）。
// 同一会话的 main + subagent wire.jsonl 按整组判定是否在 cutoff 窗口内（见 common.FilterFreshGroups），
// 窗口外的会话不解析、不缓存。
func (p *Provider) collectJobs(roots []string, cutoff time.Time) ([]common.ParseJob[*parsedSession], []*parsedSession, map[string]struct{}) {
	seen := make(map[string]struct{})
	var jobs []common.ParseJob[*parsedSession]
	var hits []*parsedSession

	var paths []string
	for _, root := range roots {
		_ = common.WalkJSONL(root, func(path string, _ fs.DirEntry) bool {
			if filepath.Base(path) == "wire.jsonl" {
				paths = append(paths, path)
			}
			return true
		})
	}
	for _, path := range common.FilterFreshGroups(paths, wireGroupKey, cutoff) {
		seen[path] = struct{}{}
		cached, offset, mtime := p.cache.GetIncremental(path)
		if cached != nil && offset == 0 {
			hits = append(hits, cached)
			continue
		}
		jobs = append(jobs, common.ParseJob[*parsedSession]{
			Path:   path,
			Cached: cached,
			Offset: offset,
			MTime:  mtime,
		})
	}
	return jobs, hits, seen
}

// wireGroupKey 把 <sessionId>/agents/<agent>/wire.jsonl 归到 <sessionId> 目录，其它布局按所在目录分组。
func wireGroupKey(path string) string {
	agentDir := filepath.Dir(path)
	if filepath.Base(filepath.Dir(agentDir)) == "agents" {
		return filepath.Dir(filepath.Dir(agentDir))
	}
	return agentDir
}

func (p *Provider) parseOne(path string, offset int64, cached *parsedSession) (*parsedSession, int64, error) {
	if cached != nil && offset > 0 {
		return parseFileIncremental(path, offset, cached)
	}
	return parseFile(path)
}

// mergeBySession 把同 SessionID 的多份 wire.jsonl（main + 各 subagent）归并为一个会话；
// main 提供身份字段，其余 token/消息/工具累加。
func mergeBySession(files []*parsedSession) []*parsedSession {
	groups := make(map[string]*parsedSession)
	// owned 标记 groups 里的对象是否已是本次归并私有的副本。files 里的指针来自 FileCache、
	// 跨 tick 复用：直接 mergeFrom 会让缓存对象每个 tick 再累加一遍子文件的 token/消息/增量。
	owned := make(map[string]bool)
	order := make([]string, 0, len(files))
	for _, f := range files {
		if f == nil || f.SessionID == "" {
			continue
		}
		base, ok := groups[f.SessionID]
		if !ok {
			groups[f.SessionID] = f
			order = append(order, f.SessionID)
			continue
		}
		if !owned[f.SessionID] {
			base = base.clone()
			owned[f.SessionID] = true
		}
		// main 优先作为身份基底；若先来的是 subagent、后来的是 main，则换基底。
		if base.AgentName != "main" && f.AgentName == "main" {
			main := f.clone()
			main.mergeFrom(base)
			base = main
		} else {
			base.mergeFrom(f)
		}
		groups[f.SessionID] = base
	}
	out := make([]*parsedSession, 0, len(order))
	for _, sid := range order {
		ps := groups[sid]
		ps.RecentTools = common.SortAndCapTools(ps.RecentTools, maxRecentTools)
		ps.RecentMessages = common.SortAndCapMessages(ps.RecentMessages, maxRecentMessages)
		out = append(out, ps)
	}
	return out
}

func (p *Provider) toMonitor(ps *parsedSession, now time.Time) monitor.Session {
	cwd := ps.CWD
	if cwd == "" {
		cwd = p.watchDir
	}
	gi := p.lookupGit(cwd)
	projectName := gi.ProjectName
	if projectName == "" && cwd != "" {
		projectName = filepath.Base(cwd)
	}
	if projectName == "" && ps.Title != "" {
		projectName = ps.Title
	}
	isWT, mainRepo := p.lookupWorktree(cwd)

	status := common.ResolveActivity(common.SessionLike{
		LastActivity: ps.LastActivity,
		BubbleStatus: ps.BubbleStatus,
		RecentTools:  ps.RecentTools,
		CurrentTool:  ps.CurrentTool,
	}, now)

	currentTool := ""
	switch status {
	case common.StatusReading, common.StatusWriting, common.StatusRunning,
		common.StatusSearching, common.StatusBrowsing, common.StatusSpawning,
		common.StatusCompacting:
		currentTool = ps.CurrentTool
	}

	return monitor.Session{
		SessionID:         ps.SessionID,
		Cwd:               cwd,
		CwdHash:           gi.ProjectPathHash,
		GitBranch:         gi.BranchName,
		RepoURL:           gi.RepoURL,
		ProjectName:       projectName,
		IsWorktree:        isWT,
		MainRepo:          mainRepo,
		Model:             ps.Model,
		Status:            status,
		CurrentTool:       currentTool,
		StartedAt:         monitor.LocalTime(ps.StartedAt),
		LastActivity:      monitor.LocalTime(ps.LastActivity),
		UserMessages:      ps.UserMessages,
		AssistantMessages: ps.AssistantMessages,
		InputTokens:       ps.InputTokens,
		OutputTokens:      ps.OutputTokens,
		CacheReadTokens:   ps.CacheRead,
		RecentTools:       ps.RecentTools,
		RecentMessages:    ps.RecentMessages,
		ActivityDeltas:    common.TailActivityDeltas(ps.ActivityDeltas, maxRecentMessages),
	}
}

func (p *Provider) lookupWorktree(cwd string) (bool, string) {
	if cwd == "" {
		return false, ""
	}
	p.wtMu.Lock()
	defer p.wtMu.Unlock()
	if v, ok := p.wtCache[cwd]; ok {
		return v.isWorktree, v.mainRepo
	}
	isWT, mainRepo := common.DetectWorktree(cwd)
	p.wtCache[cwd] = struct {
		isWorktree bool
		mainRepo   string
	}{isWT, mainRepo}
	return isWT, mainRepo
}

func (p *Provider) lookupGit(cwd string) gitinfo.Info {
	if cwd == "" {
		return gitinfo.Info{}
	}
	p.gitMu.Lock()
	defer p.gitMu.Unlock()
	if v, ok := p.gitCache[cwd]; ok {
		return v
	}
	gi := gitinfo.Detect(cwd)
	p.gitCache[cwd] = gi
	return gi
}
