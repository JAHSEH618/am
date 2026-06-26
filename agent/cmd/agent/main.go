// Package main 是 aiwatchd（AIWatch 客户端）的 CLI 入口。
//
// 子命令（对应 AIWatch 设计文档 §5.2）：
//
//	init       根据环境变量创建本地 config.json
//	register   主动调一次 /api/v1/agent/register（init 完成后会自动执行；之后通常不需要手动）
//	start      启动上报循环
//	status     打印当前配置、注册状态与已检测到的 AI Agent
//	update     在线升级到服务端最新版本（无需重新安装；v2.5 起）
//	uninstall  完全卸载（停服务、注销自启、删本机数据与默认安装路径二进制；需 --yes）
//	version    打印版本号
//	help       帮助
//
// 员工日常不需要手动执行子命令，由公司统一安装后后台运行；版本升级时直接执行
// `aiwatchd update` 即可——updater 会停 service、替换二进制、再启 service，员工
// 无需重新跑安装脚本。
// gz
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/logger"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/claude"
	"github.com/am/aiwatch-agent/internal/monitors/codex"
	"github.com/am/aiwatch-agent/internal/monitors/cursor"
	"github.com/am/aiwatch-agent/internal/monitors/hermes"
	"github.com/am/aiwatch-agent/internal/monitors/kimicode"
	"github.com/am/aiwatch-agent/internal/monitors/openclaw"
	"github.com/am/aiwatch-agent/internal/monitors/opencode"
	"github.com/am/aiwatch-agent/internal/monitors/openharness"
	"github.com/am/aiwatch-agent/internal/monitors/zcode"
	"github.com/am/aiwatch-agent/internal/registrar"
	"github.com/am/aiwatch-agent/internal/reporter"
	"github.com/am/aiwatch-agent/internal/uninstall"
	"github.com/am/aiwatch-agent/internal/updater"
)

// Version 由编译器 -ldflags 注入，未注入时为 dev。
var Version = "dev"

// BinaryHash 由打包脚本 -ldflags 注入，未注入时为空。
var BinaryHash = ""

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(2)
	}

	cmd := os.Args[1]

	// v2.9：长时常驻类子命令统一接文件日志（mac/Linux 已有 launchd/systemd 兜底，
	// Windows ScheduledTask / HKCU Run 启动的进程没人接 stderr，是排障盲区）。
	//
	// 仅对 start / init / register / update 这类"会产生事件"的命令开启；
	// status / version / help 仍走纯 stderr，避免短命令污染日志。
	switch cmd {
	case "start", "init", "register", "update":
		if path, err := logger.Init(); err != nil {
			// 失败仅一行 stderr 提示，不阻断流程：daemon 仍能在仅 stderr 模式下跑。
			fmt.Fprintf(os.Stderr, "[aiwatchd] WARN  file log init failed (continue stderr-only): %v\n", err)
		} else {
			logger.Infof("aiwatchd boot: cmd=%s version=%s pid=%d log=%s", cmd, Version, os.Getpid(), path)
		}
		// 同时把 main goroutine 的 panic 写到日志再退出（默认 Go runtime 只 print 到 stderr，
		// Windows hidden 进程下又会丢）。仅在长跑命令开启，避免吞掉单测期望的 panic。
		defer func() {
			if r := recover(); r != nil {
				logger.Errorf("aiwatchd panic: %v", r)
				os.Exit(1)
			}
		}()
	}

	switch cmd {
	case "init":
		mustOK(cmdInit())
	case "register":
		mustOK(cmdRegister())
	case "start":
		mustOK(cmdStart())
	case "status":
		mustOK(cmdStatus())
	case "update":
		mustOK(cmdUpdate())
	case "uninstall":
		mustOK(cmdUninstall())
	case "version", "-v", "--version":
		fmt.Println("aiwatchd", Version)
	case "help", "-h", "--help":
		printUsage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command: %s\n\n", cmd)
		printUsage()
		os.Exit(2)
	}
}

// cmdUpdate 拉服务端 manifest 比对版本号，必要时下载新二进制 + 校验 + 重启 service。
//
// 支持的 flag：
//
//	--check-only   仅打印服务端版本号，不下载也不重启
//	--force        即使版本号一致也完整走一遍下载 + 替换 + 重启（开发期排查 / 修复损坏二进制）
//	--no-restart   不停启 service，仅替换二进制（运维批量升级 + 自己控制窗口时使用；
//	               员工常规升级不要带这个 flag，否则要手动重启 service）
func cmdUninstall() error {
	yes := false
	for _, a := range os.Args[2:] {
		if a == "--yes" || a == "-y" {
			yes = true
		}
	}
	return uninstall.Run(yes)
}

func cmdUpdate() error {
	opts := updater.Options{}
	for _, a := range os.Args[2:] {
		switch a {
		case "--force", "-f":
			opts.Force = true
		case "--check-only", "--check":
			opts.CheckOnly = true
		case "--no-restart":
			opts.NoRestart = true
		default:
			return fmt.Errorf("unknown flag for `update`: %s (supported: --check-only, --force, --no-restart)", a)
		}
	}
	if opts.CheckOnly {
		return updater.CheckOnly(Version)
	}
	return updater.Run(Version, opts)
}

// cmdInit 把 AM_SERVER_URL + AM_USER_CODE 写到 config.json。
//
// v2.3 起新增可选环境变量 AM_USER_NAME / AM_DEPARTMENT：
// 员工自助注册时由安装命令携带，服务端 /register 在 employee 表无此 user_code 时
// 自动创建一条 ACTIVE 记录，已存在员工不覆盖。
func cmdInit() error {
	serverURL := os.Getenv("AM_SERVER_URL")
	userCode := os.Getenv("AM_USER_CODE")
	if serverURL == "" || userCode == "" {
		return errors.New("AM_SERVER_URL and AM_USER_CODE must be set for `init`")
	}
	cfg := &config.Config{
		ServerURL:       serverURL,
		UserCode:        userCode,
		UserName:        os.Getenv("AM_USER_NAME"),
		Department:      os.Getenv("AM_DEPARTMENT"),
		ReportTimeoutMs: config.DefaultReportTimeoutMs,
	}
	if err := config.Save(cfg); err != nil {
		return fmt.Errorf("save config: %w", err)
	}
	path, _ := config.DefaultPath()
	logger.Infof("config written: %s (server_url=%s user_code=%s user_name=%s department=%s)",
		path, serverURL, userCode, cfg.UserName, cfg.Department)
	return cmdRegister()
}

// cmdRegister 调一次 /register，把 agent_id + agent_secret 写回 config.
func cmdRegister() error {
	cfg, err := config.Load()
	if err != nil && !errors.Is(err, config.ErrNotInstalled) {
		return err
	}
	if cfg.UserCode == "" || cfg.ServerURL == "" {
		return errors.New("config not initialized; run `aiwatchd init` first")
	}
	cfg.AgentID = ""
	cfg.AgentSecret = ""

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if _, err := registrar.EnsureRegistered(ctx, cfg, Version, BinaryHash); err != nil {
		return err
	}
	return nil
}

// cmdStart 启动上报循环。
//
// v2.7 修复：注册失败不再退出 daemon。
//
// <p>背景：旧逻辑里 EnsureRegistered 失败就 return err → mustOK → os.Exit(1)。员工**首次安装**
// 时若网络不通（公司 VPN 没起 / DNS 异常 / server 临时宕机），daemon 直接 crash。systemd /
// launchd 会按策略反复 restart 撞墙，触发 StartLimit 后干脆停止重启 → 即使后来网络恢复，
// 这台机器再也不会自动注册上来，需要人工干预。
//
// <p>新行为：
//   - cmdStart 仅尝试一次注册作为"快速路径"；失败只 warn 不退出。
//   - reporter.Run 内部每 tick 会检查 IsRegistered()，未注册时调一次 EnsureRegistered，
//     成功后立刻进入正常上报流程；失败 warn + 跳过本 tick，下个 tick 再试。
//     断网恢复或 server 起来后最多等一个 report 间隔（默认 60s）即可自动续上。
//   - reporter 还会识别 server 返回的 AGENT_NOT_FOUND（10004），自动清掉本地 agent_id /
//     secret 触发下次 tick 重新注册（应对 DBA 误删 / 数据库重置 / 人工标 INACTIVE）。
func cmdStart() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	if cfg2, err := registrar.EnsureRegistered(ctx, cfg, Version, BinaryHash); err == nil {
		cfg = cfg2
	} else {
		logger.Warnf("initial register failed (will retry every tick in reporter loop): %v", err)
	}

	registry := monitor.NewRegistry()
	for _, p := range buildProviders(watchDirOrCwd()) {
		registry.Register(p)
	}

	r := reporter.New(cfg, registry, Version, BinaryHash)

	// gitlog 上报独立 goroutine（v2.2 Phase 3）：扫描本机 git repo 把提交流水
	// 通过独立 endpoint 上报，与会话快照解耦。失败不影响主上报循环。
	go func() {
		gr := reporter.NewGitLog(cfg, Version)
		if err := gr.Run(ctx); err != nil {
			logger.Warnf("gitlog reporter exited: %v", err)
		}
	}()

	// 方案 A：daemon 每小时拉 manifest，有新版本则分离拉起 `aiwatchd update`
	//（不可在主进程内直接 updater.Run：Stop service 会先杀掉当前 daemon）。
	go func() {
		if !envAutoUpdateEnabled() {
			logger.Infof("auto-update: disabled via AM_AUTO_UPDATE")
			return
		}
		ticker := time.NewTicker(time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := updater.MaybeScheduleDetachedUpgrade(Version); err != nil {
					logger.Warnf("auto-update: could not spawn updater: %v", err)
				}
			}
		}
	}()

	return r.Run(ctx)
}

func envAutoUpdateEnabled() bool {
	v := strings.TrimSpace(strings.ToLower(os.Getenv("AM_AUTO_UPDATE")))
	if v == "" {
		return true
	}
	switch v {
	case "0", "false", "off", "no":
		return false
	default:
		return true
	}
}

// buildProviders 静态构造平台支持的全部 Provider。
//
// 设计取舍（v1.5 起，对应需求"启动检测 + 自动感知新装"）：
//   - 不再支持 AM_AGENTS 这类环境变量过滤；所有受支持的 Provider 一律注册。
//   - 是否真的"装了"由各 Provider 自己的 IsInstalled() 回答，仅用于 status 与日志展示。
//   - Reporter 在每个 tick 都会调 Snapshot；未安装的 Provider Snapshot 内部短路返空，
//     用户后续装上某个工具，下个 tick (10s) 即可看到数据，无需重启 Agent。
//
// 顺序仅影响 status 输出与日志可读性，不影响 ingest 行为。
func buildProviders(watchDir string) []monitor.Provider {
	return []monitor.Provider{
		cursor.New(watchDir),
		claude.New(watchDir),
		codex.New(watchDir),
		hermes.New(watchDir),
		openclaw.New(watchDir),
		openharness.New(watchDir),
		opencode.New(watchDir),
		kimicode.New(watchDir),
		zcode.New(watchDir),
	}
}

func cmdStatus() error {
	path, _ := config.DefaultPath()
	cfg, err := config.Load()
	if err != nil && !errors.Is(err, config.ErrNotInstalled) {
		return err
	}

	type providerStatus struct {
		Type      string `json:"type"`
		Installed bool   `json:"installed"`
	}
	providers := buildProviders(watchDirOrCwd())
	supported := make([]string, 0, len(providers))
	statuses := make([]providerStatus, 0, len(providers))
	installed := make([]string, 0, len(providers))
	for _, p := range providers {
		supported = append(supported, p.Type())
		ok := p.IsInstalled()
		statuses = append(statuses, providerStatus{Type: p.Type(), Installed: ok})
		if ok {
			installed = append(installed, p.Type())
		}
	}

	logPath, _ := logger.DefaultLogPath()
	out := map[string]any{
		"config_path":        path,
		"log_path":           logPath,
		"server_url":         cfg.ServerURL,
		"user_code":          cfg.UserCode,
		"agent_id":           cfg.AgentID,
		"registered":         cfg.IsRegistered(),
		"interval_ms":        cfg.ReportIntervalMs,
		"active_interval_ms": cfg.ActiveReportIntervalMs,
		"monitor_policy":     cfg.MonitorPolicy,
		"watch_dir":          watchDirOrCwd(),
		"agent_version":      Version,
		"supported_agents":   supported,
		"installed_agents":   installed,
		"agent_statuses":     statuses,
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	return enc.Encode(out)
}

func watchDirOrCwd() string {
	if dir := os.Getenv("AM_WATCH_DIR"); dir != "" {
		abs, err := filepath.Abs(dir)
		if err == nil {
			return abs
		}
		return dir
	}
	cwd, err := os.Getwd()
	if err != nil {
		return ""
	}
	return cwd
}

func mustOK(err error) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "aiwatchd: %v\n", err)
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Fprintln(os.Stderr, `aiwatchd - AIWatch 客户端（AI 使用观测与洞察）

Usage:
  aiwatchd <command>

Commands:
  init        从 AM_SERVER_URL + AM_USER_CODE 创建本地配置并完成首次注册
  register    主动重新执行 /api/v1/agent/register（幂等）
  start       启动上报循环
  status      打印当前配置 / 注册状态 / 已检测到的 AI Agent
  update      在线升级到服务端最新版本（停 service → 替换二进制 → 启 service）
                选项：--check-only 仅查看是否有新版；--force 即使版本一致也强制重新替换
  uninstall   完全卸载本机 aiwatchd（须加 --yes 防误删）
  version     打印版本号
  help        打印此帮助

Environment:
  AM_SERVER_URL     AIWatch 服务端 Base URL（如 http://aiwatch.example.com:8081）
  AM_USER_CODE      员工账号 / 工号
  AM_USER_NAME      员工姓名（可选，仅自助注册时使用）
  AM_DEPARTMENT     员工部门（可选，仅自助注册时使用）
  AM_WATCH_DIR      Provider 监控的目录（默认 cwd）
  AM_LOG_LEVEL      日志级别：debug / info（默认 info；debug 会打 idle 心跳）
  AM_AUTO_UPDATE    是否每小时自动比对 manifest 并拉起在线升级（默认开启；0/false/off/no 关闭）

Supported AI Agents（进程内静态注册；实际是否采集会话由服务端 monitor_policy 与本地是否安装共同决定）:
  cursor / claude / codex / hermes / openclaw / openharness
  - 未在策略白名单内的类型本机不会调用 Snapshot（省 IO；策略随每次 /report 刷新落盘到 config.json）
  - 是否真的"装了"由各 Provider 自检本地文件系统决定
  - 员工后续安装新工具不需要重启 aiwatchd，下个 tick 自动可见（若该类型仍被服务端启用）`)
}
