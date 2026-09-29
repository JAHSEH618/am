// 文件级活动监听：在空闲基线节奏下，轮询各 provider 暴露的 hint 路径 mtime，一旦推进就让
// reporter 立刻补一个 tick——把"刚开始干活 → 第一条活动出现在大盘"的冷启动延迟从一个基线间隔
// （默认 45s）压到 ~一个轮询周期（默认 2s）。活跃期由 reporter 侧限流（见 Run 的 triggerCh 分支），
// 不会把快报节奏冲成轮询风暴；触发必被丢弃的时段（快报节奏 / 本周期已触发过）连扫描都暂停（paused）。
//
// <p>安全性：watcher 只做只读 stat，从不写盘；逻辑出错最坏是"少触发 / 多触发"，绝不影响定时 tick
// 上报。scanAll 内 recover 任何 panic 后继续，watcher goroutine 挂掉也只是退回纯定时节奏。
//
// gz
package reporter

import (
	"context"
	"os"
	"path/filepath"
	"time"

	"github.com/am/aiwatch-agent/internal/logger"
	"github.com/am/aiwatch-agent/internal/monitor"
)

const (
	// watchPollInterval 文件 mtime 轮询周期。2s 足够把冷启动延迟压到 ~一个周期，又不至于太频繁。
	watchPollInterval = 2 * time.Second
	// watchScanMaxDepth 目录型 hint 的有界递归深度（claude 会话文件在 projects/<proj>/<sess>.jsonl，
	// 深度 2；留 4 兜底子目录变体）。append 只更新叶子文件 mtime、不动父目录，必须递归到叶子才看得到。
	watchScanMaxDepth = 4
	// watchScanMaxEntries 单次轮询遍历的目录项硬上限，防御超大目录树把轮询拖慢。
	watchScanMaxEntries = 4096
)

// activityWatcher 轮询一组 hint 路径的 mtime，发现推进即向 triggerCh 发非阻塞信号。
type activityWatcher struct {
	hints     []string
	triggerCh chan<- struct{}
	last      map[string]int64 // hint -> 上轮观测到的最新 mtime(unixNano)
	interval  time.Duration
	// paused 返回 true 时本轮不扫描：reporter 此刻反正会丢弃触发（已在活跃快报节奏，或本定时周期已响应过
	// 一次）。trae/codebuddy/qoder 的 hint 是整个 IDE 配置目录，每 2s 走一遍上千个目录项纯属浪费。
	// 由 watcher goroutine 调用，实现只能读 atomic；nil 表示从不暂停。
	paused func() bool
	// stale 表示暂停期间跳过了扫描、基线已过期：恢复后的首轮只重建基线不触发——与"暂停期间照扫、
	// 信号被 reporter 丢弃"等价，避免刚退出快报节奏就因暂停期间的旧变更误补一个 tick。
	stale bool
}

// newActivityWatcher 从 registry 里所有实现 monitor.WatchHints 的 provider 收集监听路径，去重。
// 没有任何 hint 时返回 nil（调用方据此不启动 watcher）。paused 见 activityWatcher.paused。
func newActivityWatcher(registry *monitor.Registry, triggerCh chan<- struct{}, paused func() bool) *activityWatcher {
	var hints []string
	seen := make(map[string]struct{})
	for _, p := range registry.All() {
		wh, ok := p.(monitor.WatchHints)
		if !ok {
			continue
		}
		for _, h := range wh.WatchHints() {
			if h == "" {
				continue
			}
			if _, dup := seen[h]; dup {
				continue
			}
			seen[h] = struct{}{}
			hints = append(hints, h)
		}
	}
	if len(hints) == 0 {
		return nil
	}
	return &activityWatcher{
		hints:     hints,
		triggerCh: triggerCh,
		last:      make(map[string]int64, len(hints)),
		interval:  watchPollInterval,
		paused:    paused,
	}
}

// run 阻塞轮询直到 ctx 结束。首轮只建立基线、不触发（避免启动即误判为"有活动"）。
func (w *activityWatcher) run(ctx context.Context) {
	logger.Infof("activity watcher started: hints=%d poll=%s", len(w.hints), w.interval)
	w.scanAll() // 建立首轮基线
	ticker := time.NewTicker(w.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			w.poll()
		}
	}
}

// poll 是一次轮询：暂停时跳过扫描并标记基线过期；恢复后的首轮只重建基线；否则推进即发信号。
func (w *activityWatcher) poll() {
	if w.paused != nil && w.paused() {
		w.stale = true
		return
	}
	if w.stale {
		w.stale = false
		w.scanAll()
		return
	}
	if w.scanAll() {
		w.signal()
	}
}

// scanAll 重算各 hint 的最新 mtime，任一较上轮推进则返回 true。无论是否推进都会把所有 hint 的
// 基线更新到最新，避免同一次变更反复触发。
func (w *activityWatcher) scanAll() (advanced bool) {
	defer func() {
		if rec := recover(); rec != nil {
			logger.Warnf("activity watcher scan panic (continuing): %v", rec)
		}
	}()
	for _, h := range w.hints {
		cur := newestMTime(h)
		if cur == 0 {
			continue // 路径不存在 / 不可读：跳过，不动基线
		}
		prev, had := w.last[h]
		w.last[h] = cur
		if had && cur > prev {
			advanced = true
		}
	}
	return advanced
}

// signal 向 triggerCh 做非阻塞发送：缓冲已满说明已有待处理触发，直接丢弃即可。
func (w *activityWatcher) signal() {
	select {
	case w.triggerCh <- struct{}{}:
	default:
	}
}

// newestMTime 返回 path 的最新修改时间(unixNano)：文件取自身 mtime；目录按有界深度递归取子树最新
// mtime（catch 对已存在文件的 append——父目录 mtime 不随 append 变，必须看到叶子文件）。
// 0 表示路径不存在或不可读。遍历项数受 watchScanMaxEntries 硬上限保护。
func newestMTime(path string) int64 {
	info, err := os.Stat(path)
	if err != nil {
		return 0
	}
	if !info.IsDir() {
		return info.ModTime().UnixNano()
	}
	newest := info.ModTime().UnixNano()
	entries := 0
	var walk func(dir string, depth int)
	walk = func(dir string, depth int) {
		if depth > watchScanMaxDepth || entries >= watchScanMaxEntries {
			return
		}
		des, err := os.ReadDir(dir)
		if err != nil {
			return
		}
		for _, de := range des {
			if entries >= watchScanMaxEntries {
				return
			}
			entries++
			fi, err := de.Info()
			if err != nil {
				continue
			}
			if mt := fi.ModTime().UnixNano(); mt > newest {
				newest = mt
			}
			if de.IsDir() {
				walk(filepath.Join(dir, de.Name()), depth+1)
			}
		}
	}
	walk(path, 0)
	return newest
}
