// Claude Code Provider（monitor.Snapshot）。
//
// gz
package claude

import (
	"context"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/gitinfo"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// TypeCode 与 monitor_target.type_code / 服务端 ClaudeIngestService 对齐。
const TypeCode = "claude"

// Provider 实现 monitor.Provider，扫描 Claude Code JSONL 会话并产出 monitor.Snapshot。
//
// 设计要点：
//
//	跨平台：路径只走 os.UserHomeDir + ~/.claude/projects；Windows 下原生 CLI 也写到这里。
//	增量：复用 common.FileCache 按 mtime/offset 缓存解析进度，性能与 lazyagent 相当。
//	worktree：调 common.DetectWorktree 复用 cursor 同款逻辑，monorepo / git worktree 都能正确归类。
type Provider struct {
	cache      *common.FileCache[*parsedSession]
	wtMu       sync.Mutex
	wtCache    map[string]wtInfo
	gitMu      sync.Mutex
	gitCache   map[string]gitinfo.Info
	watchDir   string
	cliVersion string
	// lookback 决定扫描多久之前的 jsonl 会话。默认 monitor.DefaultLookback (48h)；
	// reporter 在 bootstrap 模式下会通过 SetLookback 切到 monitor.BootstrapLookback。
	lookback time.Duration
}

type wtInfo struct {
	isWorktree bool
	mainRepo   string
}

// New 创建 Claude Provider。watchDir 在 JSONL 没记录 cwd 时用作 git 探测的兜底目录。
func New(watchDir string) *Provider {
	return &Provider{
		cache:    common.NewFileCache[*parsedSession](),
		wtCache:  make(map[string]wtInfo),
		gitCache: make(map[string]gitinfo.Info),
		watchDir: watchDir,
		lookback: monitor.DefaultLookback,
	}
}

// SetLookback 切换扫描时间窗。由 reporter 在 bootstrap 状态切换时调用，详见
// monitor.LookbackSetter 接口注释。
func (p *Provider) SetLookback(d time.Duration) {
	if d <= 0 {
		d = monitor.DefaultLookback
	}
	p.lookback = d
}

// WatchHints 暴露 claude 会话目录根（~/.claude/projects），供 reporter 文件级监听做 mtime 轮询
// 加速冷启动（见 monitor.WatchHints）。watcher 会按有界深度递归取该目录下最新 mtime。
func (p *Provider) WatchHints() []string {
	root := projectsDir()
	if root == "" {
		return nil
	}
	return []string{root}
}

func (p *Provider) Type() string { return TypeCode }

// IsInstalled 以 ~/.claude/projects 目录是否存在判断 Claude Code 是否安装。
//
// 三平台路径一致（README §7.1）；目录不存在则视为未安装，Snapshot 会立刻短路返空。
func (p *Provider) IsInstalled() bool {
	root := projectsDir()
	if root == "" {
		return false
	}
	info, err := os.Stat(root)
	return err == nil && info.IsDir()
}

// TargetVersion 取最近一次解析到的 Claude CLI 版本（jsonl 行的 version 字段）。
func (p *Provider) TargetVersion() string {
	if p.cliVersion != "" {
		return p.cliVersion
	}
	return "claude-code"
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx

	root := projectsDir()
	now := time.Now()
	if root == "" {
		return p.empty(now), nil
	}
	if _, err := os.Stat(root); err != nil {
		return p.empty(now), nil
	}

	jobs, hits, seen := p.collectJobs(root)
	results := common.RunParallelParse(jobs, p.parseOne)

	out := make([]*parsedSession, 0, len(hits)+len(results))
	out = append(out, hits...)
	for _, r := range results {
		if r.Value == nil {
			continue
		}
		p.cache.Put(r.Path, r.MTime, r.NewOffset, r.Value)
		out = append(out, r.Value)
	}
	p.cache.Prune(seen)

	out = mergeSubagentSessions(out)

	cutoff := now.Add(-p.lookback)
	sessions := make([]monitor.Session, 0, len(out))
	for _, ps := range out {
		if ps == nil || ps.SessionID == "" {
			continue
		}
		if !ps.LastActivity.IsZero() && ps.LastActivity.Before(cutoff) {
			continue
		}
		if ps.Version != "" {
			p.cliVersion = ps.Version
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

func (p *Provider) empty(now time.Time) monitor.Snapshot {
	_ = now
	return monitor.Snapshot{
		Type:          TypeCode,
		TargetVersion: p.TargetVersion(),
		CapturedAt:    monitor.Now(),
		Sessions:      nil,
	}
}

// collectJobs 扫 ~/.claude/projects/<encoded>/*.jsonl 以及 <encoded>/<session>/subagents/*.jsonl。
//
// 返回三组数据：
//
//	jobs   缓存 miss / 文件长大了 → 需要本次重新解析
//	hits   完全命中（mtime 没变）→ 直接复用缓存里的 parsedSession
//	seen   本次见到的所有 jsonl 路径 → 用于 cache.Prune
func (p *Provider) collectJobs(root string) ([]common.ParseJob[*parsedSession], []*parsedSession, map[string]struct{}) {
	seen := make(map[string]struct{})
	var jobs []common.ParseJob[*parsedSession]
	var hits []*parsedSession

	entries, err := os.ReadDir(root)
	if err != nil {
		return jobs, hits, seen
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		dir := filepath.Join(root, e.Name())
		p.collectJSONLDir(dir, &jobs, &hits, seen)

		subdirs, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, sd := range subdirs {
			if !sd.IsDir() {
				continue
			}
			sessionDir := filepath.Join(dir, sd.Name())
			p.collectJSONLDir(sessionDir, &jobs, &hits, seen)
			subFiles, err := filepath.Glob(filepath.Join(sessionDir, "subagents", "*.jsonl"))
			if err != nil {
				continue
			}
			for _, f := range subFiles {
				p.collectJSONLFile(f, &jobs, &hits, seen)
			}
		}
	}
	return jobs, hits, seen
}

func (p *Provider) collectJSONLDir(dir string, jobs *[]common.ParseJob[*parsedSession], hits *[]*parsedSession, seen map[string]struct{}) {
	files, err := filepath.Glob(filepath.Join(dir, "*.jsonl"))
	if err != nil {
		return
	}
	for _, f := range files {
		p.collectJSONLFile(f, jobs, hits, seen)
	}
}

func (p *Provider) collectJSONLFile(path string, jobs *[]common.ParseJob[*parsedSession], hits *[]*parsedSession, seen map[string]struct{}) {
	seen[path] = struct{}{}
	cached, offset, mtime := p.cache.GetIncremental(path)
	if cached != nil && offset == 0 {
		*hits = append(*hits, cached)
		return
	}
	*jobs = append(*jobs, common.ParseJob[*parsedSession]{
		Path:   path,
		Cached: cached,
		Offset: offset,
		MTime:  mtime,
	})
}

func (p *Provider) parseOne(path string, offset int64, cached *parsedSession) (*parsedSession, int64, error) {
	if cached != nil && offset > 0 {
		return parseFileIncremental(path, offset, cached)
	}
	return parseFile(path)
}

// toMonitor 把 parsedSession 折算成上报 DTO。
//
// 步骤：
//
//   1. 状态：调 common.ResolveActivity 得到平台 10 态
//   2. CWD：parsedSession.CWD 为空时退回到 watchDir
//   3. Git：根据 CWD detect repo / branch / hash（带 TTL 内的简易缓存）
//   4. worktree：仍用 git rev-parse --git-dir，多调用合并到 wtCache
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

	isWT, mainRepo := p.lookupWorktree(cwd)

	status := common.ResolveActivity(common.SessionLike{
		LastActivity:  ps.LastActivity,
		BubbleStatus:  ps.BubbleStatus,
		RecentTools:   ps.RecentTools,
		CurrentTool:   ps.CurrentTool,
		LastSummaryAt: ps.LastSummaryAt,
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
		GitBranch:         coalesce(ps.GitBranch, gi.BranchName),
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
		CacheCreateTokens: ps.CacheCreate,
		CacheReadTokens:   ps.CacheRead,
		RecentTools:       tailTools(ps.RecentTools, 10),
		// v2.7：不再做 tail(10) 截断——reporter 端按 cursors.json 游标切片做"自上次以来增量"，
		// 配合磁盘 outbox 在离线 / 网络故障时缓存补发，保证消息流完整连续不漏不重。
		RecentMessages:  ps.RecentMessages,
		ActivityDeltas:  common.TailActivityDeltas(ps.ActivityDeltas, maxRecentMessages),
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
	p.wtCache[cwd] = wtInfo{isWorktree: isWT, mainRepo: mainRepo}
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

func tailTools(in []monitor.Tool, n int) []monitor.Tool {
	if len(in) <= n {
		out := make([]monitor.Tool, len(in))
		copy(out, in)
		return out
	}
	out := make([]monitor.Tool, n)
	copy(out, in[len(in)-n:])
	return out
}

func coalesce(a, b string) string {
	if a != "" {
		return a
	}
	return b
}
