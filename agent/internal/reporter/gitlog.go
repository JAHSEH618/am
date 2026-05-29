// Git 提交独立上报：GitLogReporter 与游标文件，调用 /api/v1/agent/report-commits。
//
// gz
package reporter

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/am/aiwatch-agent/internal/apiclient"
	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/logger"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/gitlog"
)

// GitLogReporter 周期性扫描本机 git repo 并上报新提交（Phase 3）。
//
// 与 Reporter（会话快照上报）解耦：默认 5 分钟扫一次，独立 endpoint
// /api/v1/agent/report-commits；游标 macOS 一般为 ~/Library/Caches/aiwatchd/gitlog/cursor.json。
type GitLogReporter struct {
	cfg          *config.Config
	provider     *gitlog.Provider
	client       *apiclient.Client
	agentVersion string
}

// NewGitLog 构造一个 GitLogReporter。
func NewGitLog(cfg *config.Config, agentVersion string) *GitLogReporter {
	return &GitLogReporter{
		cfg:          cfg,
		provider:     gitlog.New(cfg.GitLogRoots, cfg.GitLogBlacklist, cfg.GitAuthorEmails, gitlog.LimitsFromConfig(cfg)),
		client:       apiclient.New(cfg.ServerURL, cfg.ReportTimeout()),
		agentVersion: agentVersion,
	}
}

// commitReportRequest 对齐服务端 GitCommitReportRequest。
type commitReportRequest struct {
	AgentID                string            `json:"agent_id"`
	AgentVersion           string            `json:"agent_version"`
	CapturedAt             monitor.LocalTime `json:"captured_at"`
	ReportedIdentityEmails []string          `json:"reported_identity_emails,omitempty"`
	Commits                []gitlog.Commit   `json:"commits"`
}

// Run 阻塞执行 gitlog 上报循环。
func (g *GitLogReporter) Run(ctx context.Context) error {
	interval := time.Duration(g.cfg.GitLogIntervalMs) * time.Millisecond
	if interval <= 0 {
		interval = time.Duration(config.DefaultGitLogIntervalMs) * time.Millisecond
	}
	logger.Infof("gitlog reporter started: interval=%s optional_cfg_roots=%v blacklist=%v (scan dirs = cfg_roots + session-inferred git roots)",
		interval, g.cfg.GitLogRoots, g.cfg.GitLogBlacklist)

	// 立刻落盘一次（含空的 head_by_repo），删 cursor.json 后不必再等首轮定时器。
	g.provider.PersistCursorFile()

	// 启动后等 30 秒再首扫，避免开机峰值竞争 IO；之后按 interval 跑。
	timer := time.NewTimer(30 * time.Second)
	defer timer.Stop()

	for {
		select {
		case <-ctx.Done():
			logger.Infof("gitlog reporter stopping: %v", ctx.Err())
			return nil
		case <-timer.C:
			if err := g.scanAndReport(ctx); err != nil {
				logger.Warnf("gitlog report failed: %v", err)
			}
			timer.Reset(interval)
		}
	}
}

func (g *GitLogReporter) scanAndReport(ctx context.Context) error {
	defer g.provider.PersistCursorFile()

	res := g.provider.Scan()
	if len(res.Commits) == 0 {
		if len(res.HeadByRepo) > 0 {
			g.provider.Commit(res.HeadByRepo)
			logger.Debugf("gitlog scan: cursor advanced only (no own commits in incremental window)")
		} else {
			logger.Debugf("gitlog scan: no new commits")
		}
		return nil
	}

	body, err := json.Marshal(commitReportRequest{
		AgentID:                g.cfg.AgentID,
		AgentVersion:           g.agentVersion,
		CapturedAt:             monitor.Now(),
		ReportedIdentityEmails: res.ReportedIdentityEmails,
		Commits:                res.Commits,
	})
	if err != nil {
		return fmt.Errorf("marshal commits: %w", err)
	}

	summary, err := g.client.ReportCommits(ctx, g.cfg.AgentID, g.cfg.AgentSecret, body)
	if err != nil {
		return err
	}
	logger.Infof("gitlog report ok: scanned=%d inserted=%d duplicates=%d details_updated=%d ignored_identity_mismatch=%d",
		len(res.Commits), summary.Inserted, summary.Duplicates, summary.DetailsUpdated, summary.IgnoredIdentityMismatch)

	// 仅在上报成功后推进 cursor，失败的提交下次重扫
	g.provider.Commit(res.HeadByRepo)
	return nil
}
