// cursors.go 是会话级"消息上报水位"持久化存储。
//
// <p>每个 (provider, sessionID) 记录"上次成功上报到的最后一条 message"（external_message_id +
// timestamp），下一次 tick 在 reporter 端基于此切片，保证消息流完整连续不漏不重。
// 与 server 端 ai_session_message 的 (ai_session_id, external_message_id) 唯一索引配合，
// 即便 client 重发已传过的消息，server 也会去重。
//
// <p>落盘：~/Library/Application Support/aiwatchd/state/cursors.json，写时 tmp + rename
// 原子替换避免崩溃留半文件。
//
// <p>本 store 设计为单 reporter 协程使用，不需要额外锁；mu 留作未来扩展防御。
//
// <p>v2.7.1 起的文件结构：
//
//	{
//	  "schema_version": 2,
//	  "cursors": {
//	    "cursor:abc-123": { "last_msg_id": "...", "last_msg_time": "...", "updated_at": "..." },
//	    ...
//	  }
//	}
//
// <p>schema_version 防御机制（设计动机详见 §15.6）：升级中途 client 在 rev1 / rev2 间反复跳，
// 旧版本写入的 cursor 可能锁死到"被截断的最后一条消息"，rev2 即使提高了 maxRecentMessages 也
// 无法回填——下次启动时 schema 不匹配（包括"没有 schema_version 字段的 v0 旧文件"）一律视作
// 首次安装清空 cursor，强制重新走全量回填。
//
// <p>v2.8 升 v1 → v2：配合本次"产品级永久保留所有历史"改造，所有 6 个 monitor 的
// recentWindow（48h 闸）在 bootstrap 模式下被临时关闭以扫描全部历史会话，然后切回 48h 稳态。
// 存量员工原本 cursors v1 已就绪，重置后第一次 tick 会看到"游标空 + lookback 无穷"的组合，
// 自动把过去 cursor SQLite / claude-codex jsonl 等里所有历史会话回灌到 server。
// 代价是 server 端把已经存过的 message 再去重一遍（按 (ai_session_id, external_message_id)
// 唯一索引兜底），是可控的一次性成本。
//
// <p>未来如果再次改 cursor 语义（比如改 LastMsgID 合成规则），把这里的常量从 2 升 3，
// 部署后所有员工自动重新全量。
//
// gz
package reporter

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/logger"
)

const (
	cursorsFileName = "cursors.json"

	// CursorSchemaVersion 当前 cursor 文件 schema 版本号。任何破坏 cursor 兼容性的改动都必须升这个号。
	//
	// !!! 不要随手升这个版本号 !!!
	// 升级会让**全员**在同一次发版后同时判定"游标为空"，进入 bootstrap 全量回填（30 天历史、每台机器数十~数百 MB），
	// 这正是 2026-09 事故的放大器之一。确需升级时必须同时满足：
	//   1. 单请求字节预算已生效（budget.go，回填被切成 ~4MB 一批的多个 tick）；
	//   2. 走灰度发布（manifest.json 的 rollout_percent，分批放量），不要一次全员；
	//   3. 服务端 /agent/report 舱壁 / 限流就位，并预估好回填期间的入库压力；
	//   4. 分批发版、逐批观察连接池与 503 比例，而不是一次性推给所有人。
	//
	// <p>升级动机示例：
	//   - v0 → v1 (v2.7.1)：增加 envelope；防御 rev1/rev2 升级中途锁死历史的边缘案例
	//   - v1 → v2 (v2.8)：配合 6 个 monitor recentWindow bootstrap 扩窗（无穷 → 48h），
	//     让所有存量员工自动重新跑一次"全量历史回灌"，把过去被 48h 闸过滤掉的老会话
	//     一次性补到 server。
	//   - 未来若改 SyntheticMessageIDByTime 的合成规则导致老 LastMsgID 找不到匹配，要升 3
	//   - v2 → v3 (2026-05)：conversation_order / 时间轴修正后强制全量回填消息元数据
	CursorSchemaVersion = 3

	// cursorRetention：UpdatedAt 早于 now-cursorRetention 的游标在 Save 时剔除，否则 cursors.json 随
	// 历史会话数只增不减、且每个活跃 tick 整文件重写。必须大于 monitor.BootstrapLookback（30d）：
	// 任何还可能出现在扫描窗口里的会话都保有游标。被剔除的会话若被续聊，按"无游标"整段重发一次，
	// 由服务端 (ai_session_id, external_message_id) 去重兜底——与首次看到该会话相同。
	cursorRetention = 45 * 24 * time.Hour
)

// MsgCursor 单 session 的"已上报到此"水位。
type MsgCursor struct {
	// LastMsgID 上次成功上报的最后一条消息的 external_message_id。空串表示该 session 还没成功上报过任何消息。
	LastMsgID string `json:"last_msg_id,omitempty"`
	// LastMsgTime 上次成功上报的最后一条消息的时间戳，作为 ID 找不到时的兜底切片依据。
	LastMsgTime time.Time `json:"last_msg_time,omitempty"`
	// LastDeltaRef 上次成功上报的最后一条 activity_delta.source_ref。
	LastDeltaRef string `json:"last_delta_ref,omitempty"`
	// LastDeltaTime 对应 activity_delta.event_time，ID 找不到时的兜底切片。
	LastDeltaTime time.Time `json:"last_delta_time,omitempty"`
	// UpdatedAt 这个游标最后一次推进的时刻：排查用，也是 Save 按 cursorRetention 剔除过期游标的依据。
	UpdatedAt time.Time `json:"updated_at,omitempty"`
}

// cursorsFile 是磁盘 envelope。schema_version 缺失或不匹配会触发重置。
type cursorsFile struct {
	SchemaVersion int                  `json:"schema_version"`
	Cursors       map[string]MsgCursor `json:"cursors"`
}

// MsgCursorStore 会话游标内存 + 磁盘存储。
type MsgCursorStore struct {
	mu      sync.Mutex
	path    string
	dirty   bool
	cursors map[string]MsgCursor
}

// LoadCursorStore 从磁盘读取游标。
//
// <p>三种"等价于首次安装、清空 cursor"的情况：
//  1. 文件不存在（新员工首装）
//  2. 文件存在但 schema_version 缺失（v2.7.0 直裸 map 写法的老文件）
//  3. 文件存在且 schema_version != CursorSchemaVersion（未来升级跨版本）
//
// 这三种都返回空 store，让 reporter 第一次 tick 走全量回填路径。
//
// <p>文件被损坏 / JSON 解析失败：把坏文件改名备份成 cursors.json.corrupt-<时间戳>（只留最近
// corruptBackupKeep 份，留给运维排查），返回一个**带正确落盘路径**的空 store，等价于"首次安装"——
// 只回填一次，随后新的游标文件正常写盘。
//
// 历史 bug：这里曾经返回 error，New 降级成一个 path 为空的 store，Save 永远写不进去
// （写 ".tmp" 再 rename 到 ""），于是每次重启都 cursors 为空 → 全量 bootstrap，无限循环。
func LoadCursorStore() (*MsgCursorStore, error) {
	dir, err := stateDir()
	if err != nil {
		return nil, fmt.Errorf("state dir: %w", err)
	}
	return loadCursorStoreFrom(filepath.Join(dir, cursorsFileName), time.Now())
}

// corruptBackupKeep 游标文件损坏时保留的备份份数。
const corruptBackupKeep = 2

// loadCursorStoreFrom 是 LoadCursorStore 去掉路径解析的部分（now 用于备份文件名，便于单测）。
func loadCursorStoreFrom(path string, now time.Time) (*MsgCursorStore, error) {
	s := &MsgCursorStore{
		path:    path,
		cursors: make(map[string]MsgCursor),
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return s, nil
		}
		return nil, err
	}
	if len(data) == 0 {
		return s, nil
	}

	// 优先按新 envelope 解析；失败再按老格式（裸 map）兜底，最后判定 schema 是否匹配。
	var env cursorsFile
	if err := json.Unmarshal(data, &env); err == nil && env.SchemaVersion > 0 {
		if env.SchemaVersion != CursorSchemaVersion {
			// schema 不匹配 → 重置（不真删文件，下次 Save 会原子覆盖；保留磁盘备份给运维查问题）
			s.dirty = true
			return s, nil
		}
		if env.Cursors != nil {
			s.cursors = env.Cursors
		}
		return s, nil
	}

	// 走到这里要么 envelope 解析失败、要么 schema_version=0（v2.7.0 老格式）。
	// 进一步尝试按老格式（裸 map）解析：能解出来说明确实是 v0，按"schema 不匹配"处理 → 清空 cursor 重置。
	var legacy map[string]MsgCursor
	if err := json.Unmarshal(data, &legacy); err == nil {
		// v0 → 当前版本：清空，让 reporter 重新全量回填
		s.dirty = true
		return s, nil
	}

	// 真损坏（既不是新格式也不是老格式）：备份坏文件、以空 store 继续。
	backup := quarantineCorruptCursors(path, now)
	logger.Warnf("cursors file %s is corrupt (neither v%d envelope nor legacy map): moved to %q, "+
		"starting from empty cursors (one-time full backfill, new file will be written on next successful report)",
		path, CursorSchemaVersion, backup)
	s.dirty = true
	return s, nil
}

// quarantineCorruptCursors 把坏的游标文件改名为 <path>.corrupt-<UTC 时间戳> 并清理旧备份，返回备份路径。
// 改名失败（权限 / 被占用）时退而求其次直接删除，保证后续 Save 能写出新文件；两者都失败返回空串，
// Save 仍会以 tmp + rename 原子覆盖。
func quarantineCorruptCursors(path string, now time.Time) string {
	backup := path + corruptSuffix + now.UTC().Format("20060102T150405.000000000")
	if err := os.Rename(path, backup); err != nil {
		if rmErr := os.Remove(path); rmErr != nil {
			logger.Warnf("cannot move or remove corrupt cursors file %s: rename=%v remove=%v", path, err, rmErr)
		}
		backup = ""
	}
	pruneCorruptCursorBackups(path, corruptBackupKeep)
	return backup
}

const corruptSuffix = ".corrupt-"

// pruneCorruptCursorBackups 只保留最近 keep 份损坏备份（文件名里的时间戳可按字典序比较）。
func pruneCorruptCursorBackups(path string, keep int) {
	matches, err := filepath.Glob(path + corruptSuffix + "*")
	if err != nil || len(matches) <= keep {
		return
	}
	sort.Strings(matches)
	for _, m := range matches[:len(matches)-keep] {
		_ = os.Remove(m)
	}
}

// Get 返回 (provider, sessionID) 对应的游标；不存在返回零值与 false。
func (s *MsgCursorStore) Get(provider, sessionID string) (MsgCursor, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.cursors[cursorKey(provider, sessionID)]
	return c, ok
}

// Set 更新（或新增）游标到内存。调用方需在合适时机调 Save() 落盘。
func (s *MsgCursorStore) Set(provider, sessionID string, c MsgCursor) {
	s.mu.Lock()
	defer s.mu.Unlock()
	c.UpdatedAt = time.Now()
	s.cursors[cursorKey(provider, sessionID)] = c
	s.dirty = true
}

// Save 把内存游标原子写盘。无变更直接返回。落盘格式永远是带 schema_version 的 envelope。
// 写盘前剔除超过 cursorRetention 未推进的游标（UpdatedAt 为零值的无法判龄，保留）。
func (s *MsgCursorStore) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.dirty {
		return nil
	}
	if s.path == "" {
		// 没有落盘路径（state 目录都解析不出来）：纯内存运行。不能继续往下写——
		// filepath.Dir("")=="." 会在进程 cwd 里留下 ".tmp"，再 rename 到 "" 必然失败。
		return errors.New("cursor store has no file path (in-memory only)")
	}
	cutoff := time.Now().Add(-cursorRetention)
	for k, c := range s.cursors {
		if !c.UpdatedAt.IsZero() && c.UpdatedAt.Before(cutoff) {
			delete(s.cursors, k)
		}
	}
	env := cursorsFile{
		SchemaVersion: CursorSchemaVersion,
		Cursors:       s.cursors,
	}
	data, err := json.Marshal(env)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, s.path); err != nil {
		return err
	}
	s.dirty = false
	return nil
}

// Size 返回当前已记录的游标数量（仅用于状态日志）。
func (s *MsgCursorStore) Size() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.cursors)
}

// IsEmpty 判断当前 store 是否完全没有任何 cursor 记录。
//
// <p>用途：reporter 启动时据此决定是否进入 bootstrap 全量回填模式——
//   - true  ：首次安装 / schema 升版重置 / 文件被运维清掉 → 给所有 provider 设无穷 lookback
//   - false ：稳态 → 沿用 48h 默认窗口
//
// <p>命中口径与 Size()==0 等价，单独命名让 reporter 调用点意图更清晰。
func (s *MsgCursorStore) IsEmpty() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.cursors) == 0
}

func cursorKey(provider, sessionID string) string {
	return provider + ":" + sessionID
}
