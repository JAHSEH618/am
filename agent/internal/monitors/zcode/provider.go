// Package zcode Provider，把 ~/.zcode/cli/db/db.sqlite 的活跃 session 折算成 monitor.Snapshot。
// gz
package zcode

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/gitinfo"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// Provider 实现 monitor.Provider，从 zcode 本地 SQLite 抽取活跃会话。
type Provider struct {
	watchDir string

	gitMu    sync.Mutex
	gitCache map[string]gitinfo.Info

	// lookback 决定 SQL WHERE time_updated >= ? 的下限。默认 monitor.DefaultLookback (48h)；
	// reporter 在 bootstrap 模式下通过 SetLookback 切到 monitor.BootstrapLookback。
	lookback time.Duration

	// data_version 快路径缓存（与 opencode / hermes 同款）：未写库时整段跳过三表扫描。
	sigMu     sync.Mutex
	cachedDV  int64
	cachedRes []*parsedSession

	// 持久化 PRAGMA 连接，避免每 tick 重复 open。
	pragmaMu sync.Mutex
	pragmaDB *sql.DB

	// 最近一次扫描看到的 session.version，供 TargetVersion 回报（如 "0.14.9"）。
	verMu   sync.Mutex
	version string
}

// New 创建 zcode Provider。watchDir 在 session 没有自带 directory 时作为 git/project 推断兜底。
func New(watchDir string) *Provider {
	return &Provider{
		watchDir: watchDir,
		gitCache: make(map[string]gitinfo.Info),
		lookback: monitor.DefaultLookback,
	}
}

// SetLookback 切换扫描时间窗。由 reporter 在 bootstrap 状态切换时调用，详见 monitor.LookbackSetter。
func (p *Provider) SetLookback(d time.Duration) {
	if d <= 0 {
		d = monitor.DefaultLookback
	}
	p.lookback = d
}

func (p *Provider) Type() string { return TypeCode }

// IsInstalled 以 db.sqlite 文件存在判断 zcode 是否安装。
func (p *Provider) IsInstalled() bool {
	path := dbPath()
	if path == "" {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

// TargetVersion 回报最近一次扫描到的 session.version；尚未扫描时回退为 TypeCode 作占位标签。
func (p *Provider) TargetVersion() string {
	p.verMu.Lock()
	defer p.verMu.Unlock()
	if p.version == "" {
		return TypeCode
	}
	return p.version
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx
	path := dbPath()
	if path == "" {
		return p.empty(), nil
	}
	if _, err := os.Stat(path); err != nil {
		return p.empty(), nil
	}

	now := time.Now()

	// fast path：data_version 没变 → 复用上次解析结果，跳过 session+message+part 扫表。
	curDV := p.readDataVersionFast(path)
	p.sigMu.Lock()
	if curDV > 0 && p.cachedDV > 0 && p.cachedDV == curDV && len(p.cachedRes) > 0 {
		parsed := p.cachedRes
		p.sigMu.Unlock()
		return p.snapshotFrom(parsed, now), nil
	}
	p.sigMu.Unlock()

	db, err := openDBRO(path)
	if err != nil {
		return p.empty(), nil
	}
	defer func() { _ = db.Close() }()

	cutoff := now.Add(-p.lookback)
	parsed, err := querySessions(db, cutoff)
	if err != nil {
		return p.empty(), nil
	}

	p.sigMu.Lock()
	p.cachedDV = curDV
	p.cachedRes = parsed
	p.sigMu.Unlock()

	return p.snapshotFrom(parsed, now), nil
}

func (p *Provider) snapshotFrom(parsed []*parsedSession, now time.Time) monitor.Snapshot {
	p.rememberVersion(parsed)
	sessions := make([]monitor.Session, 0, len(parsed))
	for _, ps := range parsed {
		if ps == nil || ps.SessionID == "" {
			continue
		}
		sessions = append(sessions, p.toMonitor(ps, now))
	}
	return monitor.Snapshot{
		Type:          TypeCode,
		TargetVersion: p.TargetVersion(),
		CapturedAt:    monitor.Now(),
		Sessions:      sessions,
	}
}

// rememberVersion 记下最近一批 session 中首个非空 version（parsed 已按 time_updated DESC 排序，
// 故首个即最新）。供 TargetVersion 在没有伴随 Snapshot 调用时也能回报真实版本。
func (p *Provider) rememberVersion(parsed []*parsedSession) {
	for _, ps := range parsed {
		if ps != nil && ps.Version != "" {
			p.verMu.Lock()
			p.version = ps.Version
			p.verMu.Unlock()
			return
		}
	}
}

func (p *Provider) empty() monitor.Snapshot {
	return monitor.Snapshot{
		Type:          TypeCode,
		TargetVersion: p.TargetVersion(),
		CapturedAt:    monitor.Now(),
		Sessions:      nil,
	}
}

func (p *Provider) toMonitor(ps *parsedSession, now time.Time) monitor.Session {
	cwd := ps.Directory
	if cwd == "" {
		cwd = p.watchDir
	}
	gi := p.lookupGit(cwd)

	projectName := gi.ProjectName
	if projectName == "" && cwd != "" {
		projectName = filepath.Base(cwd)
	}
	if projectName == "" {
		projectName = strings.TrimSpace(ps.Title)
	}

	status := common.ResolveActivity(common.SessionLike{
		LastActivity: ps.LastActivity,
		BubbleStatus: common.BubbleUnknown,
		RecentTools:  ps.RecentTools,
		CurrentTool:  ps.CurrentTool,
	}, now)

	return monitor.Session{
		SessionID:         ps.SessionID,
		Cwd:               cwd,
		CwdHash:           gi.ProjectPathHash,
		GitBranch:         gi.BranchName,
		RepoURL:           gi.RepoURL,
		ProjectName:       projectName,
		Model:             ps.Model,
		Status:            status,
		CurrentTool:       currentToolFor(status, ps.CurrentTool),
		StartedAt:         monitor.LocalTime(ps.StartedAt),
		LastActivity:      monitor.LocalTime(ps.LastActivity),
		UserMessages:      ps.UserMessages,
		AssistantMessages: ps.AssistantMessages,
		InputTokens:       ps.InputTokens,
		OutputTokens:      ps.OutputTokens,
		CacheCreateTokens: ps.CacheCreate,
		CacheReadTokens:   ps.CacheRead,
		RecentTools:       ps.RecentTools,
		RecentMessages:    ps.RecentMessages,
		ActivityDeltas:    common.TailActivityDeltas(ps.ActivityDeltas, maxRecentMessages),
	}
}

func currentToolFor(status, tool string) string {
	switch status {
	case common.StatusReading, common.StatusWriting, common.StatusRunning,
		common.StatusSearching, common.StatusBrowsing, common.StatusSpawning,
		common.StatusCompacting:
		return tool
	}
	return ""
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
