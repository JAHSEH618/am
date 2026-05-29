// Package openharness Provider，把 ~/.openharness/data/sessions/<userhash>/session-*.json 折算成 monitor.Snapshot。
// gz
package openharness

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

const TypeCode = "openharness"

type Provider struct {
	watchDir string

	gitMu    sync.Mutex
	gitCache map[string]gitinfo.Info
	// lookback 决定扫描多久之前的 session 文件（按 mtime）。默认 monitor.DefaultLookback (48h)；
	// reporter 在 bootstrap 模式下会通过 SetLookback 切到 monitor.BootstrapLookback。
	lookback time.Duration
}

func New(watchDir string) *Provider {
	return &Provider{
		watchDir: watchDir,
		gitCache: make(map[string]gitinfo.Info),
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

// IsInstalled 以 ~/.openharness/settings.json 是否存在判断。
func (p *Provider) IsInstalled() bool {
	path := settingsPath()
	if path == "" {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func (p *Provider) TargetVersion() string {
	return "openharness-json"
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx
	if !p.IsInstalled() {
		return p.empty(), nil
	}
	root := sessionsRoot()
	if root == "" {
		return p.empty(), nil
	}
	if _, err := os.Stat(root); err != nil {
		return p.empty(), nil
	}

	now := time.Now()
	cutoff := now.Add(-p.lookback)
	parsed := p.scan(root, cutoff)

	sessions := make([]monitor.Session, 0, len(parsed))
	for _, ps := range parsed {
		sessions = append(sessions, p.toMonitor(ps, now))
	}
	return monitor.Snapshot{
		Type:          TypeCode,
		TargetVersion: p.TargetVersion(),
		CapturedAt:    monitor.Now(),
		Sessions:      sessions,
	}, nil
}

// scan 遍历 sessions/<userhash>/*.json，仅解析 mtime > cutoff 的文件。
//
// 不做增量缓存：每个文件本身体积很小（通常 < 100 KB），48h 窗口内文件数也有限；
// 全量重读比维护 FileCache 简单且不易出错。如果未来文件量明显增长再优化。
func (p *Provider) scan(root string, cutoff time.Time) []*parsedSession {
	var out []*parsedSession
	userDirs, err := os.ReadDir(root)
	if err != nil {
		return nil
	}
	for _, ud := range userDirs {
		if !ud.IsDir() {
			continue
		}
		userHash := ud.Name()
		userRoot := filepath.Join(root, userHash)
		entries, err := os.ReadDir(userRoot)
		if err != nil {
			continue
		}
		for _, e := range entries {
			if e.IsDir() || !isSessionFile(e.Name()) {
				continue
			}
			path := filepath.Join(userRoot, e.Name())
			info, err := e.Info()
			if err != nil {
				continue
			}
			if info.ModTime().Before(cutoff) {
				continue
			}
			ps, err := parseFile(path, info, userHash)
			if err != nil || ps == nil {
				continue
			}
			out = append(out, ps)
		}
	}
	return out
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
	cwd := ps.Cwd
	if cwd == "" {
		cwd = p.watchDir
	}
	gi := p.lookupGit(cwd)

	projectName := gi.ProjectName
	if projectName == "" && cwd != "" {
		projectName = filepath.Base(cwd)
	}
	if projectName == "" && ps.Summary != "" {
		projectName = ps.Summary
	}

	status := common.ResolveActivity(common.SessionLike{
		LastActivity: ps.LastActivity,
		BubbleStatus: common.BubbleUnknown,
		RecentTools:  ps.RecentTools,
		CurrentTool:  ps.CurrentTool,
	}, now)

	// 工具名只在工具相关状态下保留，与 hermes / claude 等其它 provider 行为对齐
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
		Model:             ps.Model,
		Status:            status,
		CurrentTool:       currentTool,
		StartedAt:         monitor.LocalTime(ps.StartedAt),
		LastActivity:      monitor.LocalTime(ps.LastActivity),
		UserMessages:      ps.UserMessages,
		AssistantMessages: ps.AssistantMessages,
		InputTokens:       ps.InputTokens,
		OutputTokens:      ps.OutputTokens,
		RecentTools:       ps.RecentTools,
		RecentMessages:    ps.RecentMessages,
		ActivityDeltas:    common.TailActivityDeltas(ps.ActivityDeltas, maxRecentMessages),
	}
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
