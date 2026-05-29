// Package openclaw Provider，把 ~/.openclaw/agents/<agent>/sessions/<uuid>.jsonl 折算成 monitor.Snapshot。
//
// 设计与 cursor / claude / codex 一致：
//
//	增量    复用 common.FileCache + common.RunParallelParse + common.ScanJSONL
//	状态    交给 common.ResolveActivity，与 Claude/Codex 共享同一套 10 态映射
//	跨平台  os.UserHomeDir 自动适配 mac / linux / windows，路径都是 ~/.openclaw
//
// 历史 task_runs.sqlite 路线已废弃：
//
//	该表是 OpenClaw 任务调度的瞬时表（task 完成 + cleanup_after 到期会被删）；
//	真正可回溯的对话流水在 agents/<agent>/sessions/<uuid>.jsonl，与 Claude Code 协议同源。
// gz
package openclaw

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

// TypeCode 与 monitor_target.type_code / 服务端 OpenClawIngestService 对齐。
const TypeCode = "openclaw"

type Provider struct {
	cache    *common.FileCache[*parsedSession]
	wtMu     sync.Mutex
	wtCache  map[string]wtInfo
	gitMu    sync.Mutex
	gitCache map[string]gitinfo.Info
	watchDir string
	// lookback 决定扫描多久之前的 jsonl 会话。默认 monitor.DefaultLookback (48h)；
	// reporter 在 bootstrap 模式下会通过 SetLookback 切到 monitor.BootstrapLookback。
	lookback time.Duration
}

type wtInfo struct {
	isWorktree bool
	mainRepo   string
}

// New 创建 OpenClaw Provider。watchDir 在 jsonl 没记录 cwd 时用作 git 探测的兜底目录。
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

func (p *Provider) Type() string { return TypeCode }

// IsInstalled 以 ~/.openclaw/openclaw.json 是否存在判断。
//
// 不依赖 sessions 目录：用户可能"装了但还没和 OpenClaw 对话过"，此时只有 openclaw.json，
// 仍应被视为已安装（Snapshot 拿到 0 条 session 是正常的）。
func (p *Provider) IsInstalled() bool {
	cfg := configPath()
	if cfg == "" {
		return false
	}
	info, err := os.Stat(cfg)
	return err == nil && !info.IsDir()
}

func (p *Provider) TargetVersion() string {
	return "openclaw-jsonl"
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx
	if !p.IsInstalled() {
		return p.empty(), nil
	}

	root := agentsRoot()
	if root == "" {
		return p.empty(), nil
	}
	if _, err := os.Stat(root); err != nil {
		// 装了 OpenClaw 但还没有任何 agent 目录 → 返空 snapshot
		return p.empty(), nil
	}

	jobs, hits, seen := p.collectJobs(root)
	cfg := loadConfig()
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

	now := time.Now()
	cutoff := now.Add(-p.lookback)
	sessions := make([]monitor.Session, 0, len(out))
	for _, ps := range out {
		if ps == nil || ps.SessionID == "" {
			continue
		}
		if !ps.LastActivity.IsZero() && ps.LastActivity.Before(cutoff) {
			continue
		}
		sessions = append(sessions, p.toMonitor(ps, cfg, now))
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

// collectJobs 扫 ~/.openclaw/agents/<agent>/sessions/*.jsonl（排除 *.jsonl.reset.<iso> 历史归档）。
//
// 返回三组：
//
//	jobs   缓存 miss / 文件长大了 → 需要本次重新解析
//	hits   完全命中 → 直接复用缓存
//	seen   本次见到的所有 jsonl 路径 → 给 cache.Prune 用
func (p *Provider) collectJobs(root string) ([]common.ParseJob[*parsedSession], []*parsedSession, map[string]struct{}) {
	seen := make(map[string]struct{})
	var jobs []common.ParseJob[*parsedSession]
	var hits []*parsedSession

	agents, err := os.ReadDir(root)
	if err != nil {
		return jobs, hits, seen
	}
	for _, ag := range agents {
		if !ag.IsDir() {
			continue
		}
		sessionsDir := filepath.Join(root, ag.Name(), "sessions")
		entries, err := os.ReadDir(sessionsDir)
		if err != nil {
			continue
		}
		for _, e := range entries {
			if e.IsDir() || !isLiveSessionFile(e.Name()) {
				continue
			}
			path := filepath.Join(sessionsDir, e.Name())
			seen[path] = struct{}{}
			cached, offset, mtime := p.cache.GetIncremental(path)
			if cached != nil && offset == 0 {
				hits = append(hits, cached)
				continue
			}
			// 把 agentName 通过 cached 隧道传到 parseOne：cached 为 nil 时 parseOne 会用 path 推断
			jobs = append(jobs, common.ParseJob[*parsedSession]{
				Path:   path,
				Cached: cached,
				Offset: offset,
				MTime:  mtime,
			})
		}
	}
	return jobs, hits, seen
}

// parseOne 是 RunParallelParse 的单文件解析入口。
//
// agentName 从路径回推：~/.openclaw/agents/<agentName>/sessions/<uuid>.jsonl
func (p *Provider) parseOne(path string, offset int64, cached *parsedSession) (*parsedSession, int64, error) {
	if cached != nil && offset > 0 {
		return parseFileIncremental(path, offset, cached)
	}
	return parseFile(path, agentNameFromPath(path))
}

// agentNameFromPath 取 sessions 目录的父目录名，如 ~/.openclaw/agents/main/sessions/<uuid>.jsonl → "main"。
func agentNameFromPath(path string) string {
	dir := filepath.Dir(path)         // .../sessions
	parent := filepath.Dir(dir)       // .../<agent>
	return filepath.Base(parent)
}

func (p *Provider) toMonitor(ps *parsedSession, cfg configSnapshot, now time.Time) monitor.Session {
	cwd := ps.CWD
	if cwd == "" {
		cwd = cfg.Workspace
	}
	if cwd == "" {
		cwd = defaultWorkspace()
	}
	if cwd == "" {
		cwd = p.watchDir
	}

	gi := p.lookupGit(cwd)
	projectName := gi.ProjectName
	if projectName == "" && cwd != "" {
		projectName = filepath.Base(cwd)
	}
	isWT, mainRepo := p.lookupWorktree(cwd)

	model := ps.Model
	if model == "" {
		model = cfg.PrimaryModel
	}

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
		GitBranch:         gi.BranchName,
		RepoURL:           gi.RepoURL,
		ProjectName:       projectName,
		IsWorktree:        isWT,
		MainRepo:          mainRepo,
		Model:             model,
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

