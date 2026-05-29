// Package registrar 负责 Agent 首次启动的 /api/v1/agent/register 流程。
//
// 流程（设计文档 v1.3 §4.0）：
//
//  1. 从 config.json 读 server_url + user_code
//  2. 采集 device.Info（hostname / os_type / host_hash）
//  3. POST /api/v1/agent/register
//  4. 把 agent_id + agent_secret + report_interval_ms 写回 config.json
//
// 注册接口本身是幂等的——同 (user_code, host_hash) 重复调用会复用旧 agent_id，
// 因此重启 Agent 时也可以无脑再调一次以拉取最新策略，但通常只在 secret 缺失时调用。
// gz
package registrar

import (
	"context"
	"errors"
	"fmt"

	"github.com/am/aiwatch-agent/internal/apiclient"
	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/device"
	"github.com/am/aiwatch-agent/internal/logger"
	"github.com/am/aiwatch-agent/internal/monitorpolicy"
)

// EnsureRegistered 在配置尚未包含 agent_id / agent_secret 时主动调一次 /register。
// 已注册的 Agent 直接返回原 cfg（非空字段不会被覆盖）。
func EnsureRegistered(ctx context.Context, cfg *config.Config, agentVersion, binaryHash string) (*config.Config, error) {
	if cfg.IsRegistered() {
		return cfg, nil
	}
	if cfg.UserCode == "" {
		return nil, errors.New("config.user_code is empty, cannot register")
	}
	if cfg.ServerURL == "" {
		return nil, errors.New("config.server_url is empty, cannot register")
	}

	info, err := device.Collect(cfg.UserCode)
	if err != nil {
		return nil, fmt.Errorf("collect device info: %w", err)
	}

	client := apiclient.New(cfg.ServerURL, cfg.ReportTimeout())
	resp, err := client.Register(ctx, apiclient.RegisterRequest{
		UserCode:     cfg.UserCode,
		UserName:     cfg.UserName,
		Department:   cfg.Department,
		HostHash:     info.HostHash,
		Hostname:     info.Hostname,
		OSType:       info.OSType,
		AgentVersion: agentVersion,
		BinaryHash:   binaryHash,
		LocalIP:      info.LocalIP,
		GitUserName:  info.GitUserName,
		GitUserEmail: info.GitUserEmail,
	})
	if err != nil {
		return nil, fmt.Errorf("register: %w", err)
	}

	cfg.AgentID = resp.AgentID
	cfg.AgentSecret = resp.AgentSecret
	cfg.ReportIntervalMs = resp.ReportIntervalMs
	cfg.TimestampWindowMs = resp.TimestampWindowMs
	if cfg.ReportTimeoutMs <= 0 {
		cfg.ReportTimeoutMs = config.DefaultReportTimeoutMs
	}
	if resp.MonitorPolicy != nil && monitorpolicy.MergeFromServer(cfg, resp.MonitorPolicy) {
		logger.Infof("monitor policy merged at register: version=%d types=%v",
			cfg.MonitorPolicy.Version, cfg.MonitorPolicy.EnabledMonitorTypes)
	}
	if err := config.Save(cfg); err != nil {
		return nil, fmt.Errorf("persist config: %w", err)
	}
	logger.Infof("registered: agent_id=%s host_hash=%s os=%s", resp.AgentID, info.HostHash, info.OSType)
	return cfg, nil
}
