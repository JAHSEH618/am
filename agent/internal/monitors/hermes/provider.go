// Package hermes Provider，把 ~/.hermes/state.db 的活跃 session 折算成 monitor.Snapshot。
// gz
package hermes

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

// TypeCode 必须与服务端 monitor_target.type_code 对齐。
const TypeCode = "hermes"

// Provider 实现 monitor.Provider，从 Hermes 本地 SQLite 抽取活跃会话。
type Provider struct {
	watchDir string

	gitMu    sync.Mutex
	gitCache map[string]gitinfo.Info
	// lookback 决定 SQL WHERE last_msg_ts >= ? 的下限。默认 monitor.DefaultLookback (48h)；
	// reporter 在 bootstrap 模式下会通过 SetLookback 切到 monitor.BootstrapLookback——
	// hermes 的 SQL 查询会因此退化为"扫全表"，仅 bootstrap 一次性使用，稳态会切回 48h。
	lookback time.Duration

	// v2.8 fast path：与 cursor 同款 PRAGMA data_version 短路。
	// hermes 写入时 data_version 自增，未写时 readDataVersion ≈ 1ms 即可判断；
	// 比每 tick "open + sessions+messages 双扫表 + N 次 fillRecent" 省 30-300ms。
	// 缓存粒度比 cursor 粗：直接缓存整批 parsedSession（hermes 一台机会话数远小于 cursor）。
	sigMu     sync.Mutex
	cachedDV  int64
	cachedRes []*parsedSession

	// 持久化 PRAGMA 连接（与 cursor.Provider.pragmaDB 同款）：
	// hermes 库通常较小，单次 open 比 cursor 快很多，但仍能省下重复 mmap / wal-index 重建。
	pragmaMu sync.Mutex
	pragmaDB *sql.DB
}

// New 创建 Hermes Provider。watchDir 在 hermes session 没有自带 cwd 信息时作为 git/project 推断兜底。
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

// IsInstalled 以 ~/.hermes/state.db 文件存在判断 Hermes Agent 是否安装。
func (p *Provider) IsInstalled() bool {
	dbPath := stateDBPath()
	if dbPath == "" {
		return false
	}
	info, err := os.Stat(dbPath)
	return err == nil && !info.IsDir()
}

// TargetVersion 当前从 hermes-agent 子目录的 hermes_constants.py 之类提取意义不大；
// 退回到固定字符串，与 codex 风格一致。
func (p *Provider) TargetVersion() string {
	return "hermes-state"
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx
	dbPath := stateDBPath()
	if dbPath == "" {
		return p.empty(), nil
	}
	if _, err := os.Stat(dbPath); err != nil {
		return p.empty(), nil
	}

	now := time.Now()

	// v2.8 fast path：data_version 没变 → 直接复用上次解析结果，跳过 sessions+messages 扫表
	curDV := p.readDataVersionFast(dbPath)
	p.sigMu.Lock()
	if curDV > 0 && p.cachedDV > 0 && p.cachedDV == curDV && len(p.cachedRes) > 0 {
		parsed := p.cachedRes
		p.sigMu.Unlock()
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
	p.sigMu.Unlock()

	db, err := openHermesDBRO(dbPath)
	if err != nil {
		return p.empty(), nil
	}
	defer func() { _ = db.Close() }()

	cutoff := now.Add(-p.lookback)
	parsed, err := querySessions(db, cutoff)
	if err != nil {
		return p.empty(), nil
	}

	// 写回缓存——下次 tick 在 data_version 没变时整段跳过 SQL
	p.sigMu.Lock()
	p.cachedDV = curDV
	p.cachedRes = parsed
	p.sigMu.Unlock()

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

func (p *Provider) empty() monitor.Snapshot {
	return monitor.Snapshot{
		Type:          TypeCode,
		TargetVersion: p.TargetVersion(),
		CapturedAt:    monitor.Now(),
		Sessions:      nil,
	}
}

func (p *Provider) toMonitor(ps *parsedSession, now time.Time) monitor.Session {
	cwd := p.lookupCwd(ps.SessionID)
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

// lookupCwd 尝试从 ~/.hermes/checkpoints/<short>/HERMES_WORKDIR 解出当时的 cwd。
//
// Hermes 会为每个任务在 checkpoints 下建一个类 git 的小仓库；HERMES_WORKDIR 文本文件
// 直接记录了 launch 时的工作目录。命中即返回，否则空串。
//
// 注意：checkpoint 短 hash 与 session_id 不一定对应；这里采用"取该 session 启动时间最近的
// checkpoint"启发式——但 checkpoint 也没显式时间戳，因此简化为：扫所有 checkpoint，
// 命中第一个就用（适合单用户单任务的常见情况）。
func (p *Provider) lookupCwd(sessionID string) string {
	root := checkpointsDir()
	if root == "" {
		return ""
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		return ""
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		path := filepath.Join(root, e.Name(), "HERMES_WORKDIR")
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		cwd := strings.TrimSpace(string(data))
		if cwd != "" {
			return cwd
		}
	}
	return ""
}
