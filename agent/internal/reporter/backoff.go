// backoff.go：上报失败后的指数退避、节奏抖动与"忙碌截止时间"。
//
// <p>背景（2026-09 事故，1.3.3 只修了一半）：服务端被打满后，客户端要么 15s 一次快节奏重报，要么把整包
// 写进 outbox、恢复后全员补发，越重试越挂。1.3.3 让 503 不再落 outbox，但其余失败（500/502/504、
// 超时、连接错误、HTTP200+业务码 50000）仍会以原节奏重试，且所有客户端的 tick 相位对齐——服务端一恢复
// 就是一个洪峰。本文件补齐三件事：
//
//   - 连续失败 → 指数退避 + equal jitter，并把服务端的 Retry-After 当下限（backoffDelay）；
//   - 每次 ticker.Reset 加 ±20% 抖动、启动时随机延迟 0–60s，打散相位（jitterDuration / startupDelay）；
//   - 失败退避期间用一个截止时间 busyUntil 同时屏蔽定时以外的一切触发（文件监听 / 心跳以外的手动触发），
//     watcher 在此期间连 mtime 扫描都跳过（见 Run 的 watchPaused）。
//
// <p>为什么失败后不写 outbox：游标只在上报成功后才推进（commitPendingCursors），失败的 tick 下次会
// 带着同样的增量重发——outbox 里的包与下个 tick 的新包高度重复，写盘只会在恢复时叠出重放风暴。
// 因此 tickOnce 的任何失败路径都不再 Append；outbox 只剩"排空旧版本遗留文件"的职责。
//
// gz
package reporter

import (
	"math/rand/v2"
	"time"

	"github.com/am/aiwatch-agent/internal/apiclient"
	"github.com/am/aiwatch-agent/internal/logger"
)

const (
	// backoffBase 第一次失败的退避基数。equal jitter 下首次等待落在 [15s, 30s)；
	// 实际间隔还会被 nextTickDelay 与空闲基线取大（见 noteFailure），所以前几次失败等价于"退回空闲节奏"。
	backoffBase = 30 * time.Second
	// backoffMax 指数退避的上限：连续失败下每个 agent 至多每 5~10 分钟探一次服务端。
	backoffMax = 10 * time.Minute
	// retryAfterCap 采信服务端 Retry-After 的上限，防止异常响应头（如 86400）让 agent 静默一整天。
	// 它高于 backoffMax：服务端明确要求等更久时，尊重它比自己封顶更重要。
	retryAfterCap = time.Hour

	// tickJitterFrac 每次 ticker.Reset 的抖动幅度（±20%）。
	tickJitterFrac = 0.2
	// startupDelayMax 启动后第一个 tick 前的随机延迟上限。
	startupDelayMax = 60 * time.Second
)

// backoffDelay 计算第 streak 次（≥1）连续失败之后的等待时长。
//
// 指数部分 exp = min(backoffMax, backoffBase·2^(streak-1))，取 equal jitter：exp/2 + rnd·exp/2，
// 落在 [exp/2, exp)——既保证有下限（不会抖成"立刻重试"），又把同时失败的一批 agent 打散。
// retryAfter（服务端 Retry-After，0 表示没给）作为下限：结果 ≥ min(retryAfter, retryAfterCap)。
// rnd 取 [0,1)，注入以便单测。
func backoffDelay(streak int, retryAfter time.Duration, rnd float64) time.Duration {
	if streak < 1 {
		streak = 1
	}
	exp := backoffBase
	for i := 1; i < streak && exp < backoffMax; i++ {
		exp *= 2
	}
	if exp > backoffMax {
		exp = backoffMax
	}
	half := exp / 2
	d := half + time.Duration(rnd*float64(half))
	if retryAfter > retryAfterCap {
		retryAfter = retryAfterCap
	}
	if retryAfter > d {
		d = retryAfter
	}
	return d
}

// jitterDuration 给间隔 d 加 ±frac 的均匀抖动（rnd ∈ [0,1)），结果不低于 min(d, minActiveReportInterval)：
// 快报间隔的 5s 硬下限（minActiveReportInterval）抖动后也不能被突破。
func jitterDuration(d time.Duration, frac, rnd float64) time.Duration {
	if d <= 0 {
		return d
	}
	out := time.Duration(float64(d) * (1 - frac + 2*frac*rnd))
	floor := d
	if floor > minActiveReportInterval {
		floor = minActiveReportInterval
	}
	if out < floor {
		out = floor
	}
	return out
}

// clock 返回当前时间；单测通过 nowFn 注入。
func (r *Reporter) clock() time.Time {
	if r.nowFn != nil {
		return r.nowFn()
	}
	return time.Now()
}

// rand01 返回 [0,1) 随机数；单测通过 randFn 注入。
func (r *Reporter) rand01() float64 {
	if r.randFn != nil {
		return r.randFn()
	}
	return rand.Float64()
}

// startupDelay 首个 tick 之前的随机延迟 [0, startupDelayMax)：服务端重启 / 发版后一批 agent 几乎同时被拉起，
// 不打散的话第一个 tick 就是一个对齐的洪峰。
func (r *Reporter) startupDelay() time.Duration {
	return time.Duration(r.rand01() * float64(startupDelayMax))
}

// inBackoff 是否处于失败退避（忙碌）期。atomic 读，watcher goroutine 也会调用。
func (r *Reporter) inBackoff() bool {
	return r.backoffRemaining() > 0
}

// backoffRemaining 距 busyUntil 还有多久；不在退避期为 0。
func (r *Reporter) backoffRemaining() time.Duration {
	until := r.busyUntil.Load()
	if until == 0 {
		return 0
	}
	if rem := time.Duration(until - r.clock().UnixNano()); rem > 0 {
		return rem
	}
	return 0
}

// noteFailure 记录一次"服务端没有处理成功"的失败（5xx / 超时 / 连接错误 / 业务码 50000 / 503 / 429 / 4xx …）：
// 连续失败数 +1、算出退避截止时间 busyUntil、退回空闲节奏。调用方随后返回 err，Run 会据 nextTickDelay
// 重设 ticker。不写 outbox——理由见文件头。
//
// busyUntil 至少覆盖一个空闲基线间隔：否则第一次失败后（退避仅 15~30s）watcher 仍会在基线间隔内提前补一个 tick，
// 正是 1.3.3 遗留的"503 之后 5 秒又触发一次"。
func (r *Reporter) noteFailure(err error) {
	r.lastActive.Store(false)
	r.failStreak++
	wait := backoffDelay(r.failStreak, apiclient.RetryAfter(err), r.rand01())
	if base := time.Duration(r.baseIntervalMs.Load()) * time.Millisecond; base > wait {
		wait = base
	}
	r.busyUntil.Store(r.clock().Add(wait).UnixNano())
	logger.Infof("report failed (streak=%d), hold off %s, body not spooled (cursors not advanced): %v",
		r.failStreak, wait.Round(time.Second), err)
}

// noteSuccess 一次成功上报清零退避状态。
func (r *Reporter) noteSuccess() {
	r.failStreak = 0
	r.busyUntil.Store(0)
}

// nextTickDelay 是 Run 每次 ticker.Reset 用的间隔：自适应间隔（active / 基线 / 回填加速）±20% 抖动，
// 且不早于退避截止时间。resolveActiveInterval / nextInterval 保持纯函数 / 无随机，抖动只在这里叠加。
func (r *Reporter) nextTickDelay() time.Duration {
	d := jitterDuration(r.nextInterval(), tickJitterFrac, r.rand01())
	if rem := r.backoffRemaining(); rem > d {
		d = rem
	}
	return d
}

// triggerAllowed 决定 watcher 的一次触发是否可以立刻补一个 tick：
// 本定时周期内还没响应过（armed）、不在快报 / 回填加速节奏、不在失败退避期、且距上次 tick 已够久。
// 抽成方法是为了让"退避期一律不发起上报"这条规则可以脱离 Run 的 select 循环单测。
func (r *Reporter) triggerAllowed(armed bool, sinceLastTick time.Duration) bool {
	return armed && !r.fastCadence() && !r.inBackoff() && sinceLastTick >= minActiveReportInterval
}
