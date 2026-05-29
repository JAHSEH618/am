// Codex Provider（monitor.Snapshot）。
//
// gz
package codex

import (
	"context"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/gitinfo"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const TypeCode = "codex"

// Provider 实现 monitor.Provider，扫描 Codex CLI JSONL 会话。
type Provider struct {
	cache   *common.FileCache[*parsedSession]
	wtMu    sync.Mutex
	wtCache map[string]struct {
		isWorktree bool
		mainRepo   string
	}
	gitMu      sync.Mutex
	gitCache   map[string]gitinfo.Info
	watchDir   string
	cliVersion string
	// lookback 决定扫描多久之前的 jsonl 会话。默认 monitor.DefaultLookback (48h)；
	// reporter 在 bootstrap 模式下会通过 SetLookback 切到 monitor.BootstrapLookback。
	lookback time.Duration
}

// New 创建 Codex Provider。watchDir 用作 Git 探测兜底。
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

// SetLookback 切换扫描时间窗。由 reporter 在 bootstrap 状态切换时调用，详见
// monitor.LookbackSetter 接口注释。
func (p *Provider) SetLookback(d time.Duration) {
	if d <= 0 {
		d = monitor.DefaultLookback
	}
	p.lookback = d
}

func (p *Provider) Type() string { return TypeCode }

// IsInstalled 以 ~/.codex/sessions 目录是否存在判断 Codex CLI 是否安装。
func (p *Provider) IsInstalled() bool {
	root := sessionsDir()
	if root == "" {
		return false
	}
	info, err := os.Stat(root)
	return err == nil && info.IsDir()
}

func (p *Provider) TargetVersion() string {
	if p.cliVersion != "" {
		return p.cliVersion
	}
	return "codex-cli"
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx
	root := sessionsDir()
	now := time.Now()
	if root == "" {
		return p.empty(), nil
	}
	if _, err := os.Stat(root); err != nil {
		return p.empty(), nil
	}

	jobs, hits, seen := p.collectJobs(root)
	results := common.RunParallelParse(jobs, p.parseOne)
	names := loadSessionNames(sessionIndexPath())

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
		if name, ok := names[ps.SessionID]; ok && name != "" {
			ps.Name = name
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

func (p *Provider) empty() monitor.Snapshot {
	return monitor.Snapshot{
		Type:          TypeCode,
		TargetVersion: p.TargetVersion(),
		CapturedAt:    monitor.Now(),
		Sessions:      nil,
	}
}

func (p *Provider) collectJobs(root string) ([]common.ParseJob[*parsedSession], []*parsedSession, map[string]struct{}) {
	seen := make(map[string]struct{})
	var jobs []common.ParseJob[*parsedSession]
	var hits []*parsedSession

	_ = common.WalkJSONL(root, func(path string, _ fs.DirEntry) bool {
		seen[path] = struct{}{}
		cached, offset, mtime := p.cache.GetIncremental(path)
		if cached != nil && offset == 0 {
			hits = append(hits, cached)
			return true
		}
		jobs = append(jobs, common.ParseJob[*parsedSession]{
			Path:   path,
			Cached: cached,
			Offset: offset,
			MTime:  mtime,
		})
		return true
	})
	return jobs, hits, seen
}

func (p *Provider) parseOne(path string, offset int64, cached *parsedSession) (*parsedSession, int64, error) {
	if cached != nil && offset > 0 {
		return parseFileIncremental(path, offset, cached)
	}
	return parseFile(path)
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
	if projectName == "" && ps.Name != "" {
		projectName = ps.Name
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
