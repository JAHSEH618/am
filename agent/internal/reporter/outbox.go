// outbox.go 是上报失败的报文磁盘队列。
//
// <p>设计目标：员工离线（在家干活、网络故障、server 宕机）期间，所有 tickOnce 拼好的 body
// 全量持久化到 ~/Library/Application Support/aiwatchd/state/outbox/，按文件名（time-sortable）
// 顺序在恢复联网后逐个补发，**永不过期**。
//
// <p>文件名格式：YYYYMMDDTHHMMSSnnnnnnnnn-<seq>.json
// （UnixNano 时间精度 + 进程内单调递增 seq，避免同毫秒下多次 Append 撞名）。
//
// <p>文件内容：原始 reportRequest 的 JSON 字节。重发时由 apiclient.Report 用当下时间重新做
// HMAC 签名（X-Agent-Ts），所以即便 body 在磁盘上躺了几天再发，签名也不会过期；body 里的
// captured_at（事件实际发生时刻）保持原值不变，server 端时序判定不受影响。
//
// <p>线程模型：单 reporter 协程使用，不需要额外锁；seqCnt 仍用 atomic 防御未来并发扩展。
//
// gz
package reporter

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync/atomic"
	"time"

	"github.com/am/aiwatch-agent/internal/logger"
)

const outboxDirName = "outbox"

// Sender 是 Drain 的实际发送回调；返回 nil 视为成功，可删除该队列文件。
type Sender func(ctx context.Context, body []byte) error

// Outbox 是磁盘失败队列（FIFO，按文件名时序）。
type Outbox struct {
	dir     string
	seqCnt  atomic.Uint64
	pending atomic.Int32 // -1 = 未知；≥0 时 Drain 可跳过 ReadDir

	// maxFiles 是队列文件数上限；Append 超过时按 FIFO 淘汰最旧。0 视为不限。
	maxFiles int
	// maxDrainPerCall 是单次 Drain 最多发送的文件数；余量留到下次 tick，避免重连时同步爆发。0 视为不限。
	maxDrainPerCall int
}

// LoadOutbox 创建（或挂载已存在的）outbox 目录。
func LoadOutbox() (*Outbox, error) {
	base, err := stateDir()
	if err != nil {
		return nil, err
	}
	dir := filepath.Join(base, outboxDirName)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	o := &Outbox{dir: dir, maxFiles: 2000, maxDrainPerCall: 200}
	o.pending.Store(-1)
	return o, nil
}

// Append 把一份失败的 body 持久化到队列尾部。空 body 直接返回错误。
func (o *Outbox) Append(body []byte) error {
	if len(body) == 0 {
		return errors.New("empty body")
	}
	seq := o.seqCnt.Add(1)
	name := fmt.Sprintf("%s-%010d.json",
		time.Now().UTC().Format("20060102T150405.000000000"), seq)
	path := filepath.Join(o.dir, name)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, body, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	o.bumpPending()
	if o.maxFiles > 0 && o.Pending() > o.maxFiles {
		o.evictOldest()
	}
	return nil
}

// evictOldest 在文件数超过 maxFiles 时，按文件名时序删除最旧的若干个，使其回落到上限。
// 离线过久（misconfig / 长时间 server 宕机）下保护本地磁盘；最旧的报文最可能已被 server 去重
// 或超出采集 lookback，故优先淘汰。删除发生即记 warn（数据丢失提示）。
func (o *Outbox) evictOldest() {
	entries, err := o.list()
	if err != nil {
		return
	}
	if len(entries) <= o.maxFiles {
		o.pending.Store(int32(len(entries)))
		return
	}
	drop := len(entries) - o.maxFiles
	for _, p := range entries[:drop] {
		_ = os.Remove(p)
	}
	logger.Warnf("outbox over cap: evicted %d oldest file(s) (cap=%d)", drop, o.maxFiles)
	o.pending.Store(int32(o.maxFiles))
}

func (o *Outbox) bumpPending() {
	for {
		cur := o.pending.Load()
		if cur < 0 {
			return
		}
		if o.pending.CompareAndSwap(cur, cur+1) {
			return
		}
	}
}

// Pending 返回当前 outbox 待发送文件数（用于状态展示 / 日志）；列目录失败返回 -1。
func (o *Outbox) Pending() int {
	if p := o.pending.Load(); p >= 0 {
		return int(p)
	}
	entries, err := o.list()
	if err != nil {
		return -1
	}
	n := len(entries)
	o.pending.Store(int32(n))
	return n
}

// Drain 按文件名时序逐个发送，成功就删；任一失败立即停下并返回 (sent, err)。
//
// 调用方语义：
//   - err == nil 时表示队列已清空（含本次没有任何待发文件的情况）
//   - err != nil 时调用方应把当前 tick 的 body 也 Append，下次 tick 再一起重试
func (o *Outbox) Drain(ctx context.Context, send Sender) (int, error) {
	if o.Pending() == 0 {
		return 0, nil
	}
	entries, err := o.list()
	if err != nil {
		return 0, fmt.Errorf("list outbox: %w", err)
	}
	o.pending.Store(int32(len(entries)))
	if len(entries) == 0 {
		return 0, nil
	}
	sent := 0
	for i, p := range entries {
		if o.maxDrainPerCall > 0 && sent >= o.maxDrainPerCall {
			// 达到单次上限：余量留到下次 tick；pending 反映剩余数。
			o.pending.Store(int32(len(entries) - i))
			return sent, nil
		}
		body, err := os.ReadFile(p)
		if err != nil {
			// 文件读不出来直接清掉，避免坏文件无限阻塞队列
			_ = os.Remove(p)
			continue
		}
		if err := send(ctx, body); err != nil {
			return sent, err
		}
		_ = os.Remove(p)
		sent++
	}
	o.pending.Store(0)
	return sent, nil
}

// list 返回 outbox 目录下所有 .json 文件的全路径，按文件名升序（= 时序）排列。
func (o *Outbox) list() ([]string, error) {
	dirEnts, err := os.ReadDir(o.dir)
	if err != nil {
		return nil, err
	}
	out := make([]string, 0, len(dirEnts))
	for _, e := range dirEnts {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if !strings.HasSuffix(name, ".json") {
			continue
		}
		out = append(out, filepath.Join(o.dir, name))
	}
	sort.Strings(out)
	return out, nil
}
