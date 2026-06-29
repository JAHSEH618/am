// Cursor IDE Provider（monitor.Snapshot）。
//
// gz
package cursor

import (
	"context"
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/gitinfo"
	"github.com/am/aiwatch-agent/internal/logger"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const (
	// TypeCode 与服务端 monitor_target.type_code 对齐。
	TypeCode = "cursor"
)

// parsedSessionCache 记录上一次 tick 解析出的 parsedSession，按 (sid, lastBubbleAt) 命中复用：
//
//   - cursor 一个 session 的 lastBubbleAt 取自该 session 所有 bubble 中 createdAt 的 max；
//     只要这个 max 没变，就意味着 SQLite 中这个 session 的 composer + bubbles 全部内容
//     一定没变（cursor 写入只 append 不改写历史 bubble）。
//   - 首次 tick 14 sessions 全 miss → 一次性 SQLite fetch + 解析；之后 tick 只有真正在
//     用的 1-2 个活跃 session lastBubbleAt 推进，老会话 100% 命中缓存，省掉 N-1 次
//     `composerData:?` 单点 + `bubbleId:?:%` 全 LIKE。
//   - 缓存随 Provider 生命周期常驻，进程退出即清空，无需淘汰策略；体积 ≈ session 数 × 单 session
//     parsedSession（含 RecentMessages 已是去 prompt 的精简结构），整体 KB 量级。
type parsedSessionCache struct {
	mu      sync.RWMutex
	entries map[string]*parsedSessionCacheEntry
}

type parsedSessionCacheEntry struct {
	lastAt time.Time
	parsed *parsedSession
}

func newParsedSessionCache() *parsedSessionCache {
	return &parsedSessionCache{entries: make(map[string]*parsedSessionCacheEntry)}
}

// get 命中条件：sid 存在 且 lastAt 与缓存值完全相等。
func (c *parsedSessionCache) get(sid string, lastAt time.Time) (*parsedSession, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	e, ok := c.entries[sid]
	if !ok || !e.lastAt.Equal(lastAt) {
		return nil, false
	}
	return e.parsed, true
}

// put 写入或更新某个 session 的最新解析结果。
func (c *parsedSessionCache) put(sid string, lastAt time.Time, ps *parsedSession) {
	if ps == nil {
		return
	}
	c.mu.Lock()
	c.entries[sid] = &parsedSessionCacheEntry{lastAt: lastAt, parsed: ps}
	c.mu.Unlock()
}

// retain 保留 alive 集合中的 sid，删掉缓存里其他条目，避免老会话长期占用内存。
func (c *parsedSessionCache) retain(alive map[string]struct{}) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for sid := range c.entries {
		if _, ok := alive[sid]; !ok {
			delete(c.entries, sid)
		}
	}
}

// dbSig 描述一次 cursor state.vscdb 的"是否被写入"指纹，由 SQLite PRAGMA data_version
// 与 -wal 文件 size 组合而成。
//
// <p>设计动机：cursor 在 WAL 模式下后台会周期性 fsync / touch -wal 文件刷新 mtime，
// 即便没有真实写入也会改 mtime——直接用 (mtime,size) 做指纹会让 fast path 永远失效。
//
// <p>SQLite 提供 `PRAGMA data_version`（SQLite 3.7+）：本连接读其它写者已提交事务时自增；
// 这是 SQLite 自身定义的"自上次以来 schema/数据有变化"语义，零写也零变。配合 -wal size
// 兜底（同 data_version 但 wal 在膨胀也会被检测到），能把 idle 时段的 cursor.Snapshot
// 从 17s 降到 ~50ms。
//
// <p>读取一次 PRAGMA 的成本：sql.Open + Query + Close ≈ 30-100ms，对比扫表 17s 完全可忽略。
type dbSig struct {
	dataVersion int64
	walSize     int64
}

func (s dbSig) equal(other dbSig) bool {
	return s.dataVersion == other.dataVersion && s.walSize == other.walSize
}

// readDataVersionFast 用持久化的 RO 连接读 PRAGMA data_version。
//
// 首次调用 lazy 打开连接（一次 ~1s 在大库上的成本，分摊到所有 tick）；
// 后续每次仅 ~1-3ms 完成读取。失败返回 0 走全扫描兜底。
func (p *Provider) readDataVersionFast(dbPath string) int64 {
	p.pragmaMu.Lock()
	defer p.pragmaMu.Unlock()
	if p.pragmaDB == nil {
		db, err := openStateDBRO(dbPath)
		if err != nil {
			return 0
		}
		// 将连接池上限收紧到 1：data_version 是单行 PRAGMA，不需要并发，
		// 同时避免无意中创建多个对 2.4GB 文件的 mmap。
		db.SetMaxOpenConns(1)
		db.SetMaxIdleConns(1)
		db.SetConnMaxIdleTime(0) // 永不空闲断开
		p.pragmaDB = db
	}
	var dv int64
	if err := p.pragmaDB.QueryRow("PRAGMA data_version").Scan(&dv); err != nil {
		// 连接异常 → 重置，下次 lazy 重建
		_ = p.pragmaDB.Close()
		p.pragmaDB = nil
		return 0
	}
	return dv
}

// Provider 从 Cursor state.vscdb 解析真实会话并产出 monitor.Snapshot。
type Provider struct {
	watchDir string
	tracker  *activityTracker
	// lookback 决定扫描多久之前的 composer。默认 monitor.DefaultLookback (48h)；
	// reporter 在 bootstrap 模式下会通过 SetLookback 切到 monitor.BootstrapLookback。
	lookback time.Duration
	// cache：(sid, lastBubbleAt) → parsedSession 复用，详见 parsedSessionCache 注释。
	cache *parsedSessionCache
	// sigMu / lastSig：上次成功 discoverSessions 时观测到的 -wal 指纹 + 当时存活的 sid 列表。
	// 下个 tick 入口若指纹未变即整段 SQL 跳过，对 idle 时段把单次 cursor.Snapshot 从 17s
	// 压到亚秒级，是带来"客户端到大盘 ≤ 10s"延迟的最关键一刀。
	sigMu     sync.Mutex
	lastSig   dbSig
	lastAlive []sessionRef

	// v1.2.0 增量发现：避免每 tick 对 cursorDiskKV 全表 json_extract（重度用户 2-3GB 库单次 5-6 分钟）。
	//   sessionMaxAt     每 session 已知最后活动时间（增量累积）
	//   lastRowid        上次扫描时的 cursorDiskKV 最大 rowid（增量水位）
	//   incrementalReady 增量基线是否已由一次全量建立
	//   rowidUnusable    探测到 rowid 不可用（WITHOUT ROWID）→ 永久回退全量，零退化
	//   lastFullScanAt   上次全量时刻；每 fullRescanInterval 强制全量兜底 rowid 复用漏扫
	// 仅 discoverSessions 串行访问（reporter tickMu 保证同一 provider 的 Snapshot 不并发），无需额外锁。
	sessionMaxAt     map[string]time.Time
	lastRowid        int64
	incrementalReady bool
	rowidUnusable    bool
	lastFullScanAt   time.Time

	// v2.8 持久化 PRAGMA 连接：每次 Snapshot 仅用它读一行 data_version 判断 fast path 是否命中。
	//
	// <p>动机：cursor state.vscdb 在重度用户机器上 2-3 GB，modernc.org/sqlite 在 sql.Open 时
	// 需要做 page cache 预热 / wal-index 映射，单次 open 实测 0.8-1.4 秒。如果每个 tick 都重新
	// open + close 仅为读一行 PRAGMA，fast path 节省的 17s 大半被 open 成本吃掉（idle tick 仍
	// 1.2 秒）。改成进程级常驻一个 RO 连接后 PRAGMA 读取在 ms 级。
	//
	// <p>RO + WAL 模式下持久连接是安全的：cursor 主进程对历史 bubble 只追加不改写，PRAGMA
	// data_version 又是 SQLite 内置的"读到新写者已提交事务"指标，不会因长连接而错失新数据。
	// 唯一异常路径是 cursor 把整个 state.vscdb 重建（极罕见，例如 reset all data）；下次 fast
	// path miss 走全量扫描自然恢复，不需要主动重建连接。
	pragmaMu sync.Mutex
	pragmaDB *sql.DB

	// v2.8 cwd → gitinfo 缓存：toMonitor 每个 session 调一次 gitinfo.Detect(cwd) 会 fork
	// 一次 git rev-parse；14 个 session 串行下来要 1.1 秒 / tick。claude/codex/openclaw 早就
	// 加了同样的缓存，cursor 因为历史原因落下了，补齐之后 toMonitor 单 tick 总耗时从 1.1s
	// 降到 < 1ms。
	gitMu    sync.Mutex
	gitCache map[string]gitinfo.Info
}

// New 创建 Provider。watchDir 在气泡未带 workspaceUri 时用作 CWD / git 探测兜底目录。
func New(watchDir string) *Provider {
	return &Provider{
		watchDir:     watchDir,
		tracker:      newActivityTracker(),
		lookback:     monitor.DefaultLookback,
		cache:        newParsedSessionCache(),
		gitCache:     make(map[string]gitinfo.Info),
		sessionMaxAt: make(map[string]time.Time),
	}
}

// lookupGit 与 codex/claude/openclaw 同款 cwd → gitinfo 进程级缓存。
//
// <p>仅在 cursor session 第一次出现时 fork 一次 git rev-parse；后续 tick 命中即返回。
// 下游 toMonitor 不再受 git 调用拖慢，使 fast-path 命中时整段 Snapshot 在亚毫秒级。
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

// SetLookback 切换扫描时间窗。由 reporter 在 bootstrap 状态切换时调用，详见
// monitor.LookbackSetter 接口注释。
func (p *Provider) SetLookback(d time.Duration) {
	if d <= 0 {
		d = monitor.DefaultLookback
	}
	p.lookback = d
}

// WatchHints 暴露 cursor globalStorage 的 state.vscdb 及其 -wal（SQLite WAL 每次写入都会刷新 -wal 的
// mtime，是最灵敏的活动信号），供 reporter 文件级监听做 mtime 轮询加速冷启动（见 monitor.WatchHints）。
// workspaceStorage 树较大、轮询成本高，暂不纳入——globalStorage 已覆盖主聊天写入信号。
func (p *Provider) WatchHints() []string {
	db := stateDBPath()
	if db == "" {
		return nil
	}
	return []string{db, db + "-wal"}
}

func (p *Provider) Type() string { return TypeCode }

// IsInstalled 以 state.vscdb 是否存在判断 Cursor 是否安装。
//
// 仅做存在性检查，不打开 SQLite——Reporter 会在 Snapshot 里补一次 Stat + Open。
func (p *Provider) IsInstalled() bool {
	dbPath := stateDBPath()
	if dbPath == "" {
		return false
	}
	_, err := os.Stat(dbPath)
	return err == nil
}

func (p *Provider) TargetVersion() string {
	v := readCursorVersion()
	if v == "" || v == "unknown" {
		return "cursor-state"
	}
	return v
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	dbPath := stateDBPath()
	if dbPath == "" {
		return p.emptySnapshot(), nil
	}
	if _, err := os.Stat(dbPath); err != nil {
		return p.emptySnapshot(), nil
	}

	now := time.Now()
	parsed, err := p.discoverSessions(ctx, dbPath, now, p.lookback)
	if err != nil {
		return monitor.Snapshot{}, err
	}

	// Task_v2 子 composer 归并到父 chat，避免一条 Cursor 窗口变成多条 ai_session。
	var suppressed []string
	parsed, suppressed = mergeSubagentSessions(parsed)

	p.tracker.updateFromParsed(parsed, now)

	sessions := make([]monitor.Session, 0, len(parsed))
	for _, ps := range parsed {
		st := p.tracker.get(ps.SessionID)
		sessions = append(sessions, p.toMonitor(ps, st))
	}

	return monitor.Snapshot{
		Type:                 TypeCode,
		TargetVersion:        p.TargetVersion(),
		CapturedAt:           monitor.Now(),
		Sessions:             sessions,
		SuppressedSessionIDs: suppressed,
	}, nil
}

func (p *Provider) emptySnapshot() monitor.Snapshot {
	return monitor.Snapshot{
		Type:          TypeCode,
		TargetVersion: p.TargetVersion(),
		CapturedAt:    monitor.Now(),
		Sessions:      nil,
	}
}

// parseConcurrency 控制 buildParsedSession 的并发度。SQLite 文件级 RO 模式可同时
// 多读，但完全放飞会让大会话同时拉 GB 级 bubble JSON 把内存峰值打高，且 sqlite-go
// 驱动自身会按 Conn 序列化执行。8 是 14 个会话场景下实测 IO/内存 trade-off 的甜区，
// 仍优于串行 14 倍最差情况。
const parseConcurrency = 8

func (p *Provider) discoverSessions(ctx context.Context, dbPath string, now time.Time, lookback time.Duration) ([]*parsedSession, error) {
	// v2.8 fast path：用 SQLite PRAGMA data_version 判断 cursor 自上次 tick 以来是否
	// 真有新写。无写 → 跳过整段 SQLite 扫描，直接从缓存里把上次 alive 的 sid 全量返回。
	// idle 时段（员工早晨开机但没用 cursor / 中午吃饭 / 写 git commit 中）90% 的 tick
	// 属于此场景，省下 ~17s/cycle 的扫描，把客户端→大盘延迟压到 <15s。
	curDV := p.readDataVersionFast(dbPath)
	_, curWalSize := walStats(dbPath)
	curSig := dbSig{dataVersion: curDV, walSize: curWalSize}
	p.sigMu.Lock()
	if curDV > 0 && p.lastSig.dataVersion > 0 && p.lastSig.equal(curSig) && len(p.lastAlive) > 0 {
		alive := append([]sessionRef(nil), p.lastAlive...)
		p.sigMu.Unlock()
		out := make([]*parsedSession, 0, len(alive))
		for _, r := range alive {
			if cached, ok := p.cache.get(r.sid, r.lastAt); ok {
				out = append(out, cached)
			}
		}
		return out, nil
	}
	p.sigMu.Unlock()

	db, err := openStateDBRO(dbPath)
	if err != nil {
		return nil, err
	}
	defer func() { _ = db.Close() }()

	if err := ping(db); err != nil {
		return nil, err
	}

	// v1.2.0：增量发现——优先只扫新增 bubble（rowid 水位），冷启动/周期性/无 rowid 时回退全量。
	cutoff := now.Add(-lookback)
	refs, err := p.discoverRefs(ctx, db, now, cutoff)
	if err != nil {
		return nil, err
	}

	queryMs := time.Since(now).Milliseconds()

	// v2.8：缓存命中的 session 完全跳过 SQLite 解析；剩下未命中的并发 build。
	type slot struct {
		ps *parsedSession
	}
	slots := make([]slot, len(refs))
	misses := make([]int, 0, len(refs))
	alive := make(map[string]struct{}, len(refs))
	for i, r := range refs {
		alive[r.sid] = struct{}{}
		if cached, ok := p.cache.get(r.sid, r.lastAt); ok {
			slots[i].ps = cached
			continue
		}
		misses = append(misses, i)
	}
	// 老会话（不在 refs 中的）从缓存中清掉，防止常驻内存。
	p.cache.retain(alive)

	buildStart := time.Now()
	workspaceCtx := loadComposerWorkspaceContext()
	if len(misses) > 0 {
		sem := make(chan struct{}, parseConcurrency)
		var wg sync.WaitGroup
		for _, idx := range misses {
			wg.Add(1)
			sem <- struct{}{}
			go func(idx int) {
				defer wg.Done()
				defer func() { <-sem }()
				r := refs[idx]
				ps := buildParsedSession(db, r.sid, r.lastAt, workspaceCtx)
				slots[idx].ps = ps
				if ps != nil {
					p.cache.put(r.sid, r.lastAt, ps)
				}
			}(idx)
		}
		wg.Wait()
	}
	buildMs := time.Since(buildStart).Milliseconds()
	totalMs := time.Since(now).Milliseconds()
	if totalMs > 1000 {
		logger.Infof("cursor discover: total=%dms query=%dms build=%dms sessions=%d hits=%d misses=%d",
			totalMs, queryMs, buildMs, len(refs), len(refs)-len(misses), len(misses))
	}

	out := make([]*parsedSession, 0, len(refs))
	for _, s := range slots {
		if s.ps != nil {
			out = append(out, s.ps)
		}
	}
	out = hydrateMissingSubagents(db, out, workspaceCtx)

	// 更新指纹 + alive 列表，下个 tick 若 wal 没变就走 fast path。
	p.sigMu.Lock()
	p.lastSig = curSig
	p.lastAlive = append(p.lastAlive[:0], refs...)
	p.sigMu.Unlock()

	return out, nil
}

// fullRescanInterval：即便增量可用，也每隔这么久强制一次全量重扫，兜底 SQLite 删最大 rowid 行后
// 新插入复用该 rowid 导致的增量漏扫。cursor 基本只追加、几乎不删最新行，该路径极罕见；又因全量在大库
// 要数分钟（见下方 fullScanTimeout，会阻塞本轮 tick），故取较长间隔——冷启动/进程重启已自然重建基线。
const fullRescanInterval = 6 * time.Hour

// fullScanTimeout：全量扫描（冷启动 / 周期性 / 无 rowid 回退）的自有超时。全量在重度用户 2-3GB 库要
// 5-6 分钟（bootstrap 30 天首扫更久），不能受 25s 采集预算约束——否则永远扫不完、建不起增量基线。
// 故全量用 context.Background() 派生的宽松 ctx，本轮 tick 会被它阻塞，但 1.0.19 解耦心跳保证此间设备
// 仍在线；进程退出时该扫描随进程结束。增量路径（毫秒级）仍走 tick 预算 ctx。
const fullScanTimeout = 15 * time.Minute

// discoverRefs 返回 cutoff 之后有活动的会话 (sid,lastAt) 列表，维护增量基线 p.sessionMaxAt/p.lastRowid。
//
//   - 全量（冷启动 / 每 fullRescanInterval / rowid 不可用）：沿用 queryRecentSessions 的 GROUP BY，
//     用结果重建 sessionMaxAt，并记录当前 MAX(rowid) 作为增量水位。
//   - 增量：只读 rowid > lastRowid 的新 bubble（毫秒级），合并进 sessionMaxAt，推进水位。
//
// 取消安全：任何 SQL 出错（含 ctx 预算取消）都在改动 p.* 之前 return，绝不留半更新状态——
// 全量的大扫描被取消时直接丢弃、下个 tick 重试；增量同理。
func (p *Provider) discoverRefs(ctx context.Context, db *sql.DB, now, cutoff time.Time) ([]sessionRef, error) {
	full := !p.incrementalReady || p.rowidUnusable || now.Sub(p.lastFullScanAt) >= fullRescanInterval
	if full {
		// 全量用自有宽松 ctx（脱离 tick 采集预算），否则大库 5-6 分钟的全量会被 25s 预算反复取消、
		// 永远建不起增量基线。本轮 tick 会被它阻塞，但解耦心跳保证此间设备仍在线。
		fctx, cancel := context.WithTimeout(context.Background(), fullScanTimeout)
		defer cancel()
		maxRowid, rerr := queryMaxRowid(fctx, db)
		refs, err := queryRecentSessions(fctx, db, cutoff)
		if err != nil {
			return nil, err // 全量被取消(超 15min)/失败：状态不动，下个 tick 重试
		}
		p.sessionMaxAt = make(map[string]time.Time, len(refs))
		for _, r := range refs {
			p.sessionMaxAt[r.sid] = r.lastAt
		}
		p.lastFullScanAt = now
		if rerr == nil {
			p.lastRowid = maxRowid
			p.incrementalReady = true
		} else {
			// queryRecentSessions 成功而 MAX(rowid) 失败 ⇒ 该表无 rowid（WITHOUT ROWID）→ 永久全量、零退化。
			p.rowidUnusable = true
			p.incrementalReady = false
		}
		return refs, nil
	}

	rows, err := queryBubblesAfter(ctx, db, p.lastRowid)
	if err != nil {
		return nil, err // 增量被取消/失败：状态不动，下个 tick 重试
	}
	for _, b := range rows {
		if b.at.IsZero() {
			continue
		}
		if cur, ok := p.sessionMaxAt[b.sid]; !ok || b.at.After(cur) {
			p.sessionMaxAt[b.sid] = b.at
		}
	}
	// 推进水位越过所有新增行（含非 bubble 写），避免增量范围随无关写入累积变大。
	if maxRowid, rerr := queryMaxRowid(ctx, db); rerr == nil {
		p.lastRowid = maxRowid
	}
	refs := make([]sessionRef, 0, len(p.sessionMaxAt))
	for sid, at := range p.sessionMaxAt {
		if at.After(cutoff) {
			refs = append(refs, sessionRef{sid: sid, lastAt: at})
		} else {
			delete(p.sessionMaxAt, sid) // prune 过期会话，约束内存
		}
	}
	return refs, nil
}

func ping(db *sql.DB) error {
	return db.Ping()
}

func (p *Provider) toMonitor(ps *parsedSession, status string) monitor.Session {
	cwd := ps.CWD
	if cwd == "" {
		cwd = p.watchDir
	}
	gi := p.lookupGit(cwd)
	projectName := gi.ProjectName
	if projectName == "" && cwd != "" {
		projectName = filepath.Base(cwd)
	}

	curTool := pickCurrentTool(ps, status)

	return monitor.Session{
		SessionID:         ps.SessionID,
		Cwd:               cwd,
		CwdHash:           gi.ProjectPathHash,
		GitBranch:         gi.BranchName,
		RepoURL:           gi.RepoURL,
		ProjectName:       projectName,
		IsWorktree:        ps.IsWorktree,
		MainRepo:          ps.MainRepo,
		Model:             ps.Model,
		Status:            status,
		CurrentTool:       curTool,
		StartedAt:         monitor.LocalTime(ps.StartedAt),
		LastActivity:      monitor.LocalTime(ps.LastActivity),
		UserMessages:         ps.UserMessages,
		AssistantMessages:    ps.AssistantMessages,
		SnapshotMessageCount: len(ps.RecentMessages),
		InputTokens:          ps.InputTokens,
		OutputTokens:      ps.OutputTokens,
		RecentTools:       tailToolsCopy(ps.RecentTools, 10),
		// v2.7：不再做 tail(10) 截断——reporter 端按 cursors.json 游标切片做"自上次以来增量"，
		// 配合磁盘 outbox 在离线 / 网络故障时缓存补发，保证消息流完整连续不漏不重。
		// server 端 ai_session_message (ai_session_id, external_message_id) 唯一索引兜底去重。
		RecentMessages:  ps.RecentMessages,
		ActivityDeltas:  common.TailActivityDeltas(ps.ActivityDeltas, maxRecentMessages),
	}
}

func pickCurrentTool(ps *parsedSession, status string) string {
	switch status {
	case "reading", "writing", "running", "searching", "browsing", "spawning", "compacting":
		if ps.CurrentTool != "" {
			return ps.CurrentTool
		}
	}
	if ps.BubbleStatus == statusExecTool && ps.CurrentTool != "" {
		return ps.CurrentTool
	}
	return ""
}

func tailToolsCopy(in []monitor.Tool, n int) []monitor.Tool {
	if len(in) <= n {
		out := make([]monitor.Tool, len(in))
		copy(out, in)
		return out
	}
	out := make([]monitor.Tool, n)
	copy(out, in[len(in)-n:])
	return out
}

func readCursorVersion() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "unknown"
	}
	// macOS Cursor 安装路径（与 VS Code 派生版一致）
	candidates := []string{
		filepath.Join(home, "Library", "Application Support", "Cursor", "resources", "app", "package.json"),
	}
	for _, path := range candidates {
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		var meta struct {
			Version string `json:"version"`
		}
		if json.Unmarshal(data, &meta) == nil && meta.Version != "" {
			return meta.Version
		}
	}
	return "unknown"
}
