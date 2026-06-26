// Package reporter 负责把所有 Provider 的快照按周期上报到服务端。
//
// 取代 v1.2 的 heartbeat：v1.3 的上报包含 device_state + monitors[]。
//
// v2.7 起的"完整连续上传"机制（设计文档 §15.5）：
//
//   - 会话级游标 {@link MsgCursorStore}：每 (provider, sessionID) 记录上次成功上报的最后一条
//     external_message_id + timestamp，下一次 tick 在 reporter 端按游标切片，server 端不再依靠
//     client 的 tail(N) 截断（消除"client 只发最后 10 条 → server 永远收不到中间消息"的截断丢失）。
//
//   - 磁盘失败队列 {@link Outbox}：上报失败的 body 全量持久化到 ~/.../aiwatchd/state/outbox/，
//     按文件名时序在恢复联网后逐个补发，永不过期。员工在家干活离线一天，第二天回公司联网时
//     所有当天 body 全部按时序补齐到 server。
//
//   - gzip 压缩 {@link maybeCompress}：body ≥ 1KB 时启用 gzip，HMAC 对压缩字节计算，server 端
//     GzipDecodingFilter 解压。员工规模上行带宽下降 5~10 倍。
//
//   - 首次安装无截断：cursors.json 为空时游标视为 ε，第一次 tick 自然把所有 provider 当前可见的
//     全部 RecentMessages 全量上报，无需额外的 bootstrap.done 标记文件。
//
// v2.8 在 v2.7 之上补齐 v2.7 漏的最后一道闸——provider 内 recentWindow 48h 时间窗：
//
//   - 启动时若 cursors store 为空（首次安装 / schema 升版重置），把所有支持 LookbackSetter
//     的 provider 切到 monitor.BootstrapLookback（≈无穷），让 provider 把磁盘 / SQLite 里
//     可见的全部历史会话扫出来；
//   - 每 tick 入口检查 backfillPending == 0 且本 tick 上报成功后，切回 monitor.DefaultLookback (48h)，
//     避免每个 tick 都跑全量扫描；之后稳态下只增量。
//   - 不在 cursors store 为空时 → 维持 48h 默认，重启后只扫近端。
//
// 单次上报失败不会阻断后续 tick；连续失败次数会写入日志（设计文档 §15.4）。
//
// gz
package reporter

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/am/aiwatch-agent/internal/apiclient"
	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/device"
	"github.com/am/aiwatch-agent/internal/logger"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitorpolicy"
	"github.com/am/aiwatch-agent/internal/monitors/common"
	"github.com/am/aiwatch-agent/internal/monitors/gitlog"
	"github.com/am/aiwatch-agent/internal/registrar"
)

// Reporter 周期上报循环。
type Reporter struct {
	cfg          *config.Config
	registry     *monitor.Registry
	client       *apiclient.Client
	agentVersion string
	binaryHash   string

	// v2.7 新增
	cursors *MsgCursorStore
	outbox  *Outbox
	// 本 tick 准备落游标的"暂存区"（key: provider:sessionID -> last MsgCursor）。
	// 仅在 send 成功后才推进 cursors.Set + Save，保证"未确认收到则下次仍发"。
	pending map[string]MsgCursor

	tickMu sync.Mutex

	// lastActive 缓存最近一次成功上报时服务端返回的 active 标志，驱动 Run 的自适应 cadence
	// （active=true → fast 间隔，false → 基线间隔）。tickOnce 写、Run 循环读，当前同 goroutine，
	// 用 atomic 防御未来可能的并发触发，零额外成本。
	lastActive atomic.Bool

	// baseIntervalMs / activeIntervalMs 是当前生效的自适应节奏（毫秒）：空闲基线 / 活跃快报。
	// Run 启动时按 cfg 初始化；之后每个成功 tick 的 mergeReportCadence 会按服务端 /report 响应
	// 下发的值刷新（下个 tick 即生效），让 ops 调服务端节奏后存量 agent 无需重装即变速。
	// nextInterval 读它们决定下个 tick 间隔；atomic 以容未来并发触发（如文件 watch 触发的 tick）。
	baseIntervalMs   atomic.Int64
	activeIntervalMs atomic.Int64

	// v2.8 新增：bootstrap 模式标志。
	// true  = 启动时 cursors 为空，已把所有 LookbackSetter provider 切到无穷窗口；
	//         本字段在 backfillPending 第一次归零 + 上报成功的 tick 末被复位。
	// false = 稳态，所有 provider 用 monitor.DefaultLookback (48h)。
	bootstrap bool

	// dedupeReplayStreak 连续「payload 有消息但 server messagesWritten=0」的 tick 计数。
	// 用于 cursors schema 升版后 server 已有全量、client 空转 replay 时触发快进到 uncapped 尾部。
	dedupeReplayStreak int
	// uncappedTail 本 tick 因 cap 截断而未发完的 session 游标候选（key = provider:sessionID）。
	uncappedTail map[string]MsgCursor
}

// New 构造一个 Reporter。游标 / outbox 任一加载失败时降级为"无断点续传"模式继续运行
// （记录 warn，不阻断 reporter 启动）。
//
// <p>v2.8 启动时如果 cursors.IsEmpty() == true（首次安装 / schema 升版重置 / 运维清空），
// 自动给 registry 里所有实现 monitor.LookbackSetter 的 provider 切到 BootstrapLookback——
// 让本次进程生命周期内，cursor / claude / codex / openclaw / openharness / hermes 都把
// 磁盘 / SQLite 里能看到的全部历史会话扫出来供 reporter 上报。
// 配合 cursors store 为空时 sliceMessagesByCursor 返回原表，第一 tick 即可回灌全部历史；
// 后续每 tick 末，backfillPending 归零 + 上报成功 → 切回 DefaultLookback 48h 稳态。
func New(cfg *config.Config, registry *monitor.Registry, agentVersion, binaryHash string) *Reporter {
	cursors, err := LoadCursorStore()
	if err != nil {
		logger.Warnf("load cursors store failed (continue without resume): %v", err)
		cursors = &MsgCursorStore{cursors: make(map[string]MsgCursor)}
	}
	outbox, err := LoadOutbox()
	if err != nil {
		logger.Warnf("load outbox failed (continue without offline buffer): %v", err)
		outbox = nil
	}
	r := &Reporter{
		cfg:          cfg,
		registry:     registry,
		client:       apiclient.New(cfg.ServerURL, cfg.ReportTimeout()),
		agentVersion: agentVersion,
		binaryHash:   binaryHash,
		cursors:      cursors,
		outbox:       outbox,
	}
	if cursors.IsEmpty() {
		r.bootstrap = true
		applyLookback(registry, monitor.BootstrapLookback)
		logger.Infof("bootstrap mode ON: cursors store empty, all LookbackSetter providers switched to %s "+
			"(will switch back to %s once backfill drained)",
			monitor.BootstrapLookback, monitor.DefaultLookback)
	}
	return r
}

// applyLookback 把指定的 lookback 推给所有实现 monitor.LookbackSetter 接口的 provider。
// 不实现该接口的 provider（例如未来加的 gitlog 派生类）会被静默跳过。
func applyLookback(registry *monitor.Registry, d time.Duration) {
	for _, p := range registry.All() {
		if ls, ok := p.(monitor.LookbackSetter); ok {
			ls.SetLookback(d)
		}
	}
}

// Run 阻塞执行上报循环，直到 ctx 结束。
//
// <p>v2.7 新行为：
//   - 未注册时不会阻塞主循环，而是每 tick 调一次 ensureRegistered，成功立即上报，失败 warn 跳过。
//   - 收到 server AGENT_NOT_FOUND 时自动清本地 agent_id+secret，下个 tick 自然走重注册路径。
func (r *Reporter) Run(ctx context.Context) error {
	interval := time.Duration(r.cfg.ReportIntervalMs) * time.Millisecond
	// 一次性升版迁移：历史 server 默认（10000 / 5000）已落盘到 config.json，升级后不会自动续上。
	// 检测到精确的旧默认值 → 视作"未自定义"，走新默认。真正手动改过 interval 的运维不会被踢。
	if interval == 10*time.Second || interval == 5*time.Second || interval == 5*time.Minute {
		logger.Infof("interval upgrade: legacy default %v → new default %dms", interval, config.DefaultReportIntervalMs)
		interval = 0
	}
	if interval <= 0 {
		interval = time.Duration(config.DefaultReportIntervalMs) * time.Millisecond
	}
	intervalMs := interval.Milliseconds()
	common.ConfigureActivityTimeouts(intervalMs)
	// 初始化动态节奏（下个 tick 起每次成功上报都会按服务端下发刷新）。ConfigureActivityTimeouts
	// 故意只在启动按基线锁定一次，不随 cadence 抖动，避免 active↔idle 反馈震荡。
	r.setCadence(intervalMs, r.cfg.ActiveReportIntervalMs)
	all := r.registry.All()
	installed := make([]string, 0, len(all))
	for _, p := range all {
		if p.IsInstalled() {
			installed = append(installed, p.Type())
		}
	}
	pending := -1
	if r.outbox != nil {
		pending = r.outbox.Pending()
	}
	// 自适应 cadence：fast = 活跃时段快报间隔（默认 15s / 服务端可下发），永不超过 interval 基线。
	// 状态判定窗口（ConfigureActivityTimeouts 上面已按 interval 基线缩放）保持稳定，不随 cadence 抖动，
	// 否则会出现 active→idle→active 的反馈震荡。
	fast := time.Duration(r.activeIntervalMs.Load()) * time.Millisecond
	logger.Infof("reporter started: interval=%s active_interval=%s report_timeout=%s providers=%d installed=%d %v registered=%v cursors=%d outbox_pending=%d bootstrap=%v",
		interval, fast, r.cfg.ReportTimeout(), len(all), len(installed), installed, r.cfg.IsRegistered(), r.cursors.Size(), pending, r.bootstrap)

	if err := r.tickOnce(ctx); err != nil {
		logger.Warnf("first report failed: %v", err)
	}
	lastTick := time.Now()

	// 文件级活动监听：空闲基线节奏下，hint 路径 mtime 一推进就补一个 tick，把冷启动延迟压到
	// ~一个轮询周期。没有任何 provider 暴露 hint 时 newActivityWatcher 返回 nil，不启动。
	triggerCh := make(chan struct{}, 1)
	if w := newActivityWatcher(r.registry, triggerCh); w != nil {
		go w.run(ctx)
	}

	// 首 tick 后即按 active 信号决定下个间隔；每个 tick 末 Reset 一次，使 cadence 跟随活跃度
	// 与服务端下发的最新节奏（mergeReportCadence 已在 tickOnce 成功路径刷新过 atomic）。
	ticker := time.NewTicker(r.nextInterval())
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			logger.Infof("reporter stopping: %v", ctx.Err())
			return nil
		case <-ticker.C:
			if err := r.tickOnce(ctx); err != nil {
				logger.Warnf("report failed: %v", err)
			}
			lastTick = time.Now()
			ticker.Reset(r.nextInterval())
		case <-triggerCh:
			// watcher 触发：限流到至少 minActiveReportInterval 一次，避免活跃期把快报节奏冲成轮询
			// 风暴——真正价值是空闲→活跃的首次加速。距上次 tick 太近就丢弃本次信号。
			if time.Since(lastTick) < minActiveReportInterval {
				continue
			}
			if err := r.tickOnce(ctx); err != nil {
				logger.Warnf("watch-triggered report failed: %v", err)
			}
			lastTick = time.Now()
			ticker.Reset(r.nextInterval())
		}
	}
}

// minActiveReportInterval 是自适应快报间隔的硬下限，避免误配把全员压到亚秒级上报风暴。
const minActiveReportInterval = 5 * time.Second

// resolveActiveInterval 解析活跃时段快报间隔：cfg 优先，缺省走 DefaultActiveReportIntervalMs，
// 夹到 [minActiveReportInterval, slow]——fast 模式不应比稳态基线还慢；运维把 interval 调到 ≤15s 时
// 自适应自然退化为定频，无副作用。
func resolveActiveInterval(cfgActiveMs int64, slow time.Duration) time.Duration {
	fast := time.Duration(cfgActiveMs) * time.Millisecond
	if fast <= 0 {
		fast = time.Duration(config.DefaultActiveReportIntervalMs) * time.Millisecond
	}
	if fast < minActiveReportInterval {
		fast = minActiveReportInterval
	}
	if fast > slow {
		fast = slow
	}
	return fast
}

// nextInterval 按最近一次上报的 active 信号选择下个 tick 间隔，读取当前动态节奏 atomic。
func (r *Reporter) nextInterval() time.Duration {
	if r.lastActive.Load() {
		return time.Duration(r.activeIntervalMs.Load()) * time.Millisecond
	}
	return time.Duration(r.baseIntervalMs.Load()) * time.Millisecond
}

// setCadence 归一化并存储自适应节奏。baseMs<=0 用内置默认；activeMs 经 resolveActiveInterval
// 夹到 [minActiveReportInterval, base]（fast 不应比基线还慢，也不破亚秒下限）。
// 返回较旧值是否发生变化，供 mergeReportCadence 决定要不要打日志。
func (r *Reporter) setCadence(baseMs, activeMs int64) (changed bool) {
	base := time.Duration(baseMs) * time.Millisecond
	if base <= 0 {
		base = time.Duration(config.DefaultReportIntervalMs) * time.Millisecond
	}
	fast := resolveActiveInterval(activeMs, base)
	newBase := base.Milliseconds()
	newFast := fast.Milliseconds()
	oldBase := r.baseIntervalMs.Swap(newBase)
	oldFast := r.activeIntervalMs.Swap(newFast)
	return oldBase != newBase || oldFast != newFast
}

// mergeReportCadence 把服务端在 /report 响应里下发的节奏 honor 到本地，下个 tick 即生效。
// 这样 ops 调服务端 aiwatch.agent.{report,active-report}-interval-ms 后，存量 agent 无需重装即变速——
// 弥补「register 下发的 interval 落盘 config.json 后，老 agent 不再刷新」的缺口。
//
// <p>两字段都 <=0 视为「老服务端未下发该字段」，整体跳过、不动现状（不把现有节奏误清成默认）；
// 只下发其一时另一个保留当前生效值。仅在上报成功路径调用，避免瞬态网络抖动改节奏。
func (r *Reporter) mergeReportCadence(summary *apiclient.ReportSummary) {
	if summary == nil || (summary.ReportIntervalMs <= 0 && summary.ActiveReportIntervalMs <= 0) {
		return
	}
	base := summary.ReportIntervalMs
	if base <= 0 {
		base = r.baseIntervalMs.Load()
	}
	active := summary.ActiveReportIntervalMs
	if active <= 0 {
		active = r.activeIntervalMs.Load()
	}
	if r.setCadence(base, active) {
		logger.Infof("report cadence updated from server: base=%dms active=%dms",
			r.baseIntervalMs.Load(), r.activeIntervalMs.Load())
	}
}

// ensureRegistered 在每个 tick 入口调用：
//   - 已注册：直接返回 nil
//   - 未注册：调 registrar.EnsureRegistered；成功 → 写回 r.cfg；失败 → 返回 err 让 tickOnce 跳过本次
//
// 网络中断 / server 临时不可达 / 时钟漂移导致的注册失败都不会致命；只要到下个 tick 网络恢复，
// 注册立即续上，员工无感。
func (r *Reporter) ensureRegistered(ctx context.Context) error {
	if r.cfg.IsRegistered() {
		return nil
	}
	logger.Infof("agent not registered, attempting register ...")
	cfg2, err := registrar.EnsureRegistered(ctx, r.cfg, r.agentVersion, r.binaryHash)
	if err != nil {
		return fmt.Errorf("register: %w", err)
	}
	r.cfg = cfg2
	return nil
}

// handleReportError 在 server 返回 AGENT_NOT_FOUND 时，把本地 agent_id / secret 清空并落盘，
// 下个 tick 的 ensureRegistered 会自动触发重新注册。其它错误原样向上抛。
//
// <p>设计要点：
//   - 只清 agent_id / secret，不动 user_code / server_url（user_code 是员工身份，不能丢）
//   - 落盘失败仅 warn，不阻断流程：内存 cfg 已经清掉，下个 tick 仍能触发重注册
//   - 不在这里直接调 register，避免在错误处理路径里再叠一层网络 IO，让流程死掉
func (r *Reporter) handleReportError(err error) {
	if !apiclient.IsAgentNotFound(err) {
		return
	}
	logger.Warnf("server returned AGENT_NOT_FOUND, clearing local credentials and will re-register next tick (was agent_id=%s)",
		r.cfg.AgentID)
	r.cfg.AgentID = ""
	r.cfg.AgentSecret = ""
	if saveErr := config.Save(r.cfg); saveErr != nil {
		logger.Warnf("save config after AGENT_NOT_FOUND failed (will still retry in-memory): %v", saveErr)
	}
}

// reportRequest 与服务端 AgentReportRequest 对齐（snake_case）。
type reportRequest struct {
	AgentID      string             `json:"agent_id"`
	AgentVersion string             `json:"agent_version"`
	BinaryHash   string             `json:"binary_hash,omitempty"`
	CapturedAt   monitor.LocalTime  `json:"captured_at"`
	DeviceState  *deviceStateDto    `json:"device_state,omitempty"`
	Monitors     []monitor.Snapshot `json:"monitors"`
}

type deviceStateDto struct {
	OSType                 string `json:"os_type,omitempty"`
	Hostname               string `json:"hostname,omitempty"`
	HostHash               string `json:"host_hash,omitempty"`
	LocalIP                string `json:"local_ip,omitempty"`
	GitUserName            string `json:"git_user_name,omitempty"`
	GitUserEmail           string `json:"git_user_email,omitempty"`
	CursorEmail            string `json:"cursor_email,omitempty"`
	CursorMembershipType   string `json:"cursor_membership_type,omitempty"`
	CursorSubscriptionStat string `json:"cursor_subscription_status,omitempty"`
	CursorSignUpType       string `json:"cursor_signup_type,omitempty"`
	ForegroundApp          string `json:"foreground_app,omitempty"`
	IdleSeconds            int    `json:"idle_seconds"`
	Battery                int    `json:"battery"`
}

func (r *Reporter) tickOnce(ctx context.Context) error {
	if !r.tickMu.TryLock() {
		logger.Debugf("skip overlapping report tick")
		return nil
	}
	defer r.tickMu.Unlock()

	tickStart := time.Now()
	// v2.7：先确保已注册。未注册（首次安装但当时无网 / server 暂不可达）时本 tick 跳过；
	// 已注册的常态下这里几乎是 0 开销。
	if err := r.ensureRegistered(ctx); err != nil {
		return err
	}

	now := monitor.Now()

	info, err := device.Collect(r.cfg.UserCode)
	if err != nil {
		return fmt.Errorf("collect device: %w", err)
	}
	deviceMs := time.Since(tickStart).Milliseconds()

	// v2.8：6 个 provider 并发 collect。各 provider 的 Snapshot 完全独立——内部状态
	// （如 cursor 的 activityTracker）只在自己的 goroutine 内被读写，没有交叉。
	// v2.12：服务端 monitor_target.enabled 下发白名单 → 跳过未启用的会话类 Provider，本机不落盘快照。
	// 旧串行实现下 cursor 即使被 SQL 优化压到 ~1.5s，剩余 5 个 provider 的扫描叠加
	// 仍把 tick 推到 ~50s。改并发后整体 = max(单 provider 时间)，预期 5-15s。
	//
	// <p>错误隔离：任何 provider 的 panic 都被 recover 到自己的 slot，不影响其他 provider；
	// 单个 provider 失败也不 cancel 其他（不用 errgroup），让"半数 provider 健康"时
	// 这次 tick 仍能上报有效数据。
	//
	// <p>结果按 eligible providers 切片顺序回拼，使上报体顺序稳定。
	all := r.registry.All()
	eligible := make([]monitor.Provider, 0, len(all))
	for _, p := range all {
		if monitorpolicy.CollectorEnabled(r.cfg, p.Type()) {
			eligible = append(eligible, p)
		}
	}
	providers := eligible

	type providerResult struct {
		snap   monitor.Snapshot
		err    error
		costMs int64
	}
	results := make([]providerResult, len(providers))
	collectStart := time.Now()
	var wg sync.WaitGroup
	for i, p := range providers {
		wg.Add(1)
		go func(idx int, p monitor.Provider) {
			defer wg.Done()
			pStart := time.Now()
			defer func() {
				results[idx].costMs = time.Since(pStart).Milliseconds()
				if rec := recover(); rec != nil {
					results[idx].err = fmt.Errorf("provider %s panic: %v", p.Type(), rec)
				}
			}()
			snap, err := p.Snapshot(ctx)
			if err != nil {
				results[idx].err = err
				return
			}
			snap.Type = p.Type()
			snap.TargetVersion = p.TargetVersion()
			snap.CapturedAt = now
			results[idx].snap = snap
		}(i, p)
	}
	wg.Wait()
	collectMs := time.Since(collectStart).Milliseconds()

	monitors := make([]monitor.Snapshot, 0, len(providers))
	var account monitor.Account
	for i, p := range providers {
		if results[i].err != nil {
			logger.Warnf("provider %s snapshot failed: %v", p.Type(), results[i].err)
			continue
		}
		monitors = append(monitors, results[i].snap)

		if account.IsZero() {
			if ap, ok := p.(monitor.AccountProvider); ok {
				account = ap.Account()
			}
		}
	}

	gitlog.RecordSessionRepoRoots(monitors)

	// v2.7 关键步骤：按游标切片，把每个 session 已上报过的 RecentMessages 过滤掉，
	// 同时把"本 tick 即将上报的最后一条 message"暂存到 r.pending，等本次发送成功后再 commit。
	// backfillPending = 因 MaxMessagesPerSession cap 而未发完的余量（存量员工首次升级时 > 0）。
	backfillPending := r.applyMsgCursors(monitors)

	state := &deviceStateDto{
		OSType:       info.OSType,
		Hostname:     info.Hostname,
		HostHash:     info.HostHash,
		LocalIP:      info.LocalIP,
		GitUserName:  info.GitUserName,
		GitUserEmail: info.GitUserEmail,
	}
	if account.Provider == "cursor" {
		state.CursorEmail = account.Email
		state.CursorMembershipType = account.MembershipType
		state.CursorSubscriptionStat = account.SubscriptionStatus
		state.CursorSignUpType = account.SignUpType
	}

	req := reportRequest{
		AgentID:      r.cfg.AgentID,
		AgentVersion: r.agentVersion,
		BinaryHash:   r.binaryHash,
		CapturedAt:   now,
		DeviceState:  state,
		Monitors:     monitors,
	}

	rawBody, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("marshal report: %w", err)
	}

	body, encoding, err := maybeCompress(rawBody)
	if err != nil {
		// 压缩失败不影响主流程，退化到原始字节
		logger.Warnf("compress body failed (fallback to raw): %v", err)
		body, encoding = rawBody, ""
	}

	// 1) 先 drain outbox 历史失败报文（按文件名时序）。drain 出错时把本次 body 也排队，下次再试。
	if r.outbox != nil {
		if drained, derr := r.outbox.Drain(ctx, r.sendOutboxBody); derr != nil {
			if drained > 0 {
				logger.Infof("outbox partial drained: sent=%d remaining_err=%v", drained, derr)
			}
			if appendErr := r.outbox.Append(body); appendErr != nil {
				logger.Warnf("outbox append after drain fail: %v", appendErr)
			}
			return derr
		} else if drained > 0 {
			logger.Infof("outbox drained: sent=%d", drained)
		}
	}

	// 2) 发本次 body
	summary, err := r.client.Report(ctx, r.cfg.AgentID, r.cfg.AgentSecret, body, encoding)
	if err != nil {
		// AGENT_NOT_FOUND 是数据问题（server 端没这条 device），重发 100 次也无用。
		// 直接清本地凭证触发下个 tick 重新注册，body 不进 outbox 避免无限堆积。
		if apiclient.IsAgentNotFound(err) {
			r.handleReportError(err)
			return err
		}
		if r.outbox != nil {
			if appendErr := r.outbox.Append(body); appendErr != nil {
				logger.Warnf("outbox append after send fail: %v", appendErr)
			} else {
				logger.Infof("body queued to outbox after send fail (will retry next tick)")
			}
		}
		return err
	}

	r.mergeMonitorPolicy(summary)

	// 3) 上报成功：把暂存的游标 commit 到内存 + 落盘
	r.commitPendingCursors()

	// 自适应 cadence 信号：记录服务端本次判定的 active，供 Run 决定下个 tick 间隔。
	// 仅成功路径更新——失败 / overlap-skip 的 tick 不改 cadence，避免瞬态网络抖动拖慢活跃上报。
	r.lastActive.Store(summary.Active)
	// honor 服务端下发的最新节奏（若有变化，下个 ticker.Reset 即采用）。
	r.mergeReportCadence(summary)

	payloadMsgCount := countSnapshotMessages(monitors)
	_ = payloadMsgCount // 保留计数供后续诊断；dedupe replay 快进已禁用。

	totalMs := time.Since(tickStart).Milliseconds()
	var perProvider strings.Builder
	for i, p := range providers {
		if i > 0 {
			perProvider.WriteByte(' ')
		}
		perProvider.WriteString(p.Type())
		perProvider.WriteByte('=')
		fmt.Fprintf(&perProvider, "%dms", results[i].costMs)
	}
	// 仅"有事件 / 有消息 / 有活跃 / 慢 tick"才打 INFO；纯 idle tick 走 Debug，避免在
	// fast path 之后每 5 秒一行 INFO 把日志撑爆。Debug 包含完整 per-provider 拆解便于排障。
	if summary.Events > 0 || summary.Messages > 0 || summary.Active {
		logger.Infof("report ok: providers=%d sessions=%d events=%d messages=%d active=%v size=%d encoding=%s backfill_pending=%d total=%dms device=%dms collect=%dms",
			len(monitors), summary.Sessions, summary.Events, summary.Messages, summary.Active,
			len(body), encodingLabel(encoding), backfillPending, totalMs, deviceMs, collectMs)
	} else {
		logger.Debugf("report ok (idle): providers=%d sessions=%d size=%d encoding=%s backfill_pending=%d total=%dms device=%dms collect=%dms breakdown=[%s]",
			len(monitors), summary.Sessions, len(body), encodingLabel(encoding), backfillPending, totalMs, deviceMs, collectMs, perProvider.String())
	}
	if totalMs > 5000 {
		logger.Infof("slow tick breakdown: %s", perProvider.String())
	}
	if backfillPending > 0 {
		// 存量员工升级首次回填 / 长时间断网恢复后，会持续若干 tick 看到这条日志，
		// pending 数会单调递减直至归零，是预期行为；运维可以以此预估补完时间：
		// 估算公式 ≈ pending / MaxMessagesPerSession × report_interval。
		logger.Infof("backfill in progress: pending_msgs=%d (will drain ~%d ticks)",
			backfillPending,
			estimateBackfillTicks(backfillPending, r.bootstrap))
	}

	// 4) v2.8：bootstrap 阶段所有积压消息发完后切回稳态 48h 窗口，避免每 tick 都跑全量扫描。
	// 触发条件：本 tick 之前还在 bootstrap，本 tick 上报成功且没有任何 session 被 cap 截断。
	// 切回之后 cursors 已有水位，下次 tick 即使有老 session 也只走"自上次以来增量"。
	if r.bootstrap && backfillPending == 0 {
		r.bootstrap = false
		applyLookback(r.registry, monitor.DefaultLookback)
		logger.Infof("bootstrap mode OFF: backfill drained, all providers switched back to %s lookback",
			monitor.DefaultLookback)
	}
	return nil
}

// sendOutboxBody 是 Outbox.Drain 的回调。outbox 文件里的字节可能已经是 gzip 压缩字节，
// 也可能是原始 JSON（对应 body < CompressMinBytes 的情况），通过 magic bytes 自动判断。
//
// <p>错误处理与 tickOnce 主路径一致：碰到 AGENT_NOT_FOUND 立刻清掉本地凭证，
// 让 Drain 的外层 derr 路径直接停下；下个 tick 重新注册后再 drain，否则 outbox 会一直 fail。
func (r *Reporter) sendOutboxBody(ctx context.Context, body []byte) error {
	encoding := ""
	if IsGzip(body) {
		encoding = EncodingGzip
	}
	summary, err := r.client.Report(ctx, r.cfg.AgentID, r.cfg.AgentSecret, body, encoding)
	if err != nil && apiclient.IsAgentNotFound(err) {
		r.handleReportError(err)
	}
	if err == nil {
		r.mergeMonitorPolicy(summary)
	}
	return err
}

func (r *Reporter) mergeMonitorPolicy(summary *apiclient.ReportSummary) {
	if summary == nil || summary.MonitorPolicy == nil {
		return
	}
	if !monitorpolicy.MergeFromServer(r.cfg, summary.MonitorPolicy) {
		return
	}
	if err := config.Save(r.cfg); err != nil {
		logger.Warnf("persist monitor policy after report: %v", err)
		return
	}
	mp := r.cfg.MonitorPolicy
	if mp != nil {
		logger.Infof("monitor policy persisted: version=%d types=%v", mp.Version, mp.EnabledMonitorTypes)
	}
}

// MaxMessagesPerSession 单个 session 单次 tick 最多上传的 message 数（稳态）。
// 没有这个限制，"去掉 tail(10) + cursor 空 = 全发"组合下，老员工可能一次性把
// 几千条 cursor / hermes 历史消息全部 marshal 进单个 request body，触发：
//   - body 几十 MB（gzip 后仍数 MB），nginx / Tomcat / DB 长事务风险
//   - 单 tick 阻塞数十秒，错过下一个 ticker 周期
//
// <p>取 200 的依据：
//   - 单条 message 平均 1~2 KB（含 prompt 文本），200 条 ≈ 200~400 KB / session
//   - 中位员工 5 个活跃 session × 200 ≈ 1~2 MB，gzip 后 100~300 KB，单次 RT < 2s
//   - server 端 ingest 200 条 ≈ 200 次 (count + findByExternalId + insert)，
//     在 dev 上实测 < 500ms，可控
const MaxMessagesPerSession = 200

// MaxMessagesPerSessionBootstrap 首次安装 / schema 升版回填期：单 session 单次 tick 上限。
// 与 provider maxRecentMessages=1000 对齐，长会话（~1000 条）可在 1~2 tick 内补完。
const MaxMessagesPerSessionBootstrap = 1000

// MaxMessagesPerSessionActive 回填期对近端活跃 session 的兜底上限（LastActivity 在 2h 内）。
const MaxMessagesPerSessionActive = 500

const activeSessionLookback = 2 * time.Hour

// applyMsgCursors 按游标过滤每个 session 的 RecentMessages，把"已上报过的"剔除；
// 同时把"本 tick 即将上报的最后一条"暂存到 r.pending，等 send 成功后再正式 commit。
//
// 切片策略（针对单个 session 的 RecentMessages）：
//  1. 找游标 LastMsgID 在当前列表中的位置 idx：从 idx+1 开始切走（最稳）
//  2. 若 LastMsgID 找不到（client 端清过缓存 / message 已经从源数据中消失），
//     退化为 timestamp > LastMsgTime 的过滤（兜底）
//  3. 若两者都没匹配（首次上报 / 游标为空），保留全部
//  4. 最后对结果做 MaxMessagesPerSession cap，超出部分留给下个 tick
//     （配合 server 端 (session_id, external_msg_id) 唯一索引天然幂等）
//
// 返回值 backfillPending = 本 tick 因 cap 截断而**没发完**的剩余消息总数，
// 给到 tickOnce 用于观测日志。0 表示没有任何 session 在补历史。
func (r *Reporter) applyMsgCursors(monitors []monitor.Snapshot) (backfillPending int) {
	r.pending = make(map[string]MsgCursor, 16)
	r.uncappedTail = make(map[string]MsgCursor, 16)
	for i := range monitors {
		snap := &monitors[i]
		provider := snap.Type
		for j := range snap.Sessions {
			sess := &snap.Sessions[j]
			if sess.SessionID == "" {
				continue
			}
			cur, hasCur := r.cursors.Get(provider, sess.SessionID)
			key := cursorKey(provider, sess.SessionID)
			pending := r.pending[key]

			if len(sess.RecentMessages) > 0 {
				filtered := sliceMessagesByCursor(sess.RecentMessages, cur, hasCur)
				capN := r.maxMessagesPerSession(*sess)
				if len(filtered) > capN {
					backfillPending += len(filtered) - capN
					last := filtered[len(filtered)-1]
					r.uncappedTail[key] = MsgCursor{
						LastMsgID:   last.ExternalMessageID,
						LastMsgTime: last.Timestamp.Time(),
					}
					filtered = filtered[:capN]
				}
				sess.RecentMessages = filtered
				if len(filtered) > 0 {
					last := filtered[len(filtered)-1]
					pending.LastMsgID = last.ExternalMessageID
					pending.LastMsgTime = last.Timestamp.Time()
				}
			}

			if len(sess.ActivityDeltas) > 0 {
				filteredD := sliceActivityDeltasByCursor(sess.ActivityDeltas, cur, hasCur)
				capN := r.maxMessagesPerSession(*sess)
				if len(filteredD) > capN {
					filteredD = filteredD[:capN]
				}
				sess.ActivityDeltas = filteredD
				if len(filteredD) > 0 {
					last := filteredD[len(filteredD)-1]
					pending.LastDeltaRef = last.SourceRef
					pending.LastDeltaTime = last.EventTime.Time()
				}
			}

			if pending.LastMsgID != "" || pending.LastDeltaRef != "" || !pending.LastMsgTime.IsZero() || !pending.LastDeltaTime.IsZero() {
				r.pending[key] = pending
			}
		}
	}
	return backfillPending
}

// sliceActivityDeltasByCursor 按游标过滤 activity_deltas，只保留上次成功上报之后的新增量。
func sliceActivityDeltasByCursor(deltas []monitor.ActivityDelta, cur MsgCursor, hasCur bool) []monitor.ActivityDelta {
	if !hasCur {
		return deltas
	}
	if cur.LastDeltaRef != "" {
		for i, d := range deltas {
			if d.SourceRef == cur.LastDeltaRef {
				return deltas[i+1:]
			}
		}
	}
	if !cur.LastDeltaTime.IsZero() {
		out := deltas[:0:0]
		for _, d := range deltas {
			if d.EventTime.Time().After(cur.LastDeltaTime) {
				out = append(out, d)
			}
		}
		return out
	}
	return deltas
}

func (r *Reporter) maxMessagesPerSession(sess monitor.Session) int {
	capN := MaxMessagesPerSession
	if r.bootstrap {
		capN = MaxMessagesPerSessionBootstrap
	}
	if !sess.LastActivity.Time().IsZero() && time.Since(sess.LastActivity.Time()) <= activeSessionLookback {
		if capN < MaxMessagesPerSessionActive {
			capN = MaxMessagesPerSessionActive
		}
	}
	return capN
}

func estimateBackfillTicks(pending int, bootstrap bool) int {
	if pending <= 0 {
		return 0
	}
	perTick := MaxMessagesPerSession
	if bootstrap {
		perTick = MaxMessagesPerSessionBootstrap
	}
	return (pending + perTick - 1) / perTick
}

// dedupeReplayFastForwardAfter 连续多少个 tick「有 payload 消息但 server 新写入 0 条」后，
// 认为 server 已拥有 uncapped 窗口内全部历史，一次性推进游标到截断前尾部。
const dedupeReplayFastForwardAfter = 2

func countSnapshotMessages(monitors []monitor.Snapshot) int {
	n := 0
	for i := range monitors {
		for j := range monitors[i].Sessions {
			n += len(monitors[i].Sessions[j].RecentMessages)
		}
	}
	return n
}

// applyDedupeReplayFastForward 曾用于 dedupe replay 空转时把游标快进到 uncapped 尾部。
// 已禁用：在 backfillPending > 0 时 server 返回 messages=0 只说明本批重复，
// 不能证明 uncapped 窗口已全部入库，快进会永久跳过中间消息（#71 133 条缺口根因）。
func (r *Reporter) applyDedupeReplayFastForward(backfillPending, payloadMsgCount, serverMessagesWritten int) int {
	return 0
}

// sliceMessagesByCursor 按游标过滤 RecentMessages，返回只包含"游标之后"的消息子序列。
func sliceMessagesByCursor(msgs []monitor.Message, cur MsgCursor, hasCur bool) []monitor.Message {
	if !hasCur {
		return msgs
	}
	if cur.LastMsgID != "" {
		for i, m := range msgs {
			if m.ExternalMessageID != "" && m.ExternalMessageID == cur.LastMsgID {
				return msgs[i+1:]
			}
		}
	}
	if !cur.LastMsgTime.IsZero() {
		out := msgs[:0:0]
		for _, m := range msgs {
			if m.Timestamp.Time().After(cur.LastMsgTime) {
				out = append(out, m)
			}
		}
		return out
	}
	return msgs
}

// commitPendingCursors 把本 tick 暂存的游标推进到 store 并落盘。
func (r *Reporter) commitPendingCursors() {
	if len(r.pending) == 0 {
		return
	}
	for k, v := range r.pending {
		// 复用 cursorKey 反解出 provider + sessionID
		provider, sessionID, ok := splitCursorKey(k)
		if !ok {
			continue
		}
		r.cursors.Set(provider, sessionID, v)
	}
	r.pending = nil
	if err := r.cursors.Save(); err != nil {
		logger.Warnf("save cursors failed (will retry next tick): %v", err)
	}
}

// splitCursorKey 与 cursorKey 配对，按第一个 ':' 拆 provider + sessionID。
func splitCursorKey(k string) (provider, sessionID string, ok bool) {
	idx := bytes.IndexByte([]byte(k), ':')
	if idx <= 0 || idx >= len(k)-1 {
		return "", "", false
	}
	return k[:idx], k[idx+1:], true
}

// encodingLabel 把空 contentEncoding 渲染成 "raw"，方便日志阅读。
func encodingLabel(enc string) string {
	if enc == "" {
		return "raw"
	}
	return enc
}
