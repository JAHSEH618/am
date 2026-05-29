// Package monitor 定义 Agent 的"监控目标提供者"抽象。
//
// 设计原则（对应 v1.3 设计文档 §3.2）：
//
//	所有"监控目标"都通过 Provider 接口接入：
//	  - cursor   读 ~/Library/Application Support/Cursor/User/globalStorage/state.vscdb
//	  - claude   预留：监控 Claude Code session
//	  - codex    预留：监控 Codex / OpenAI Codex CLI
//
// Reporter 不关心具体 Provider，只在每个 tick 上调 Snapshot()，
// 把结果封装为 /api/v1/agent/report 的 monitors[] 数组。
// gz
package monitor

import (
	"context"
	"strings"
	"sync"
	"time"
)

// LocalTime 是 time.Time 的别名，序列化为 "2006-01-02T15:04:05"，
// 与服务端 java.time.LocalDateTime 默认 ISO 形式严格对齐。
//
// 直接用 time.Time 会输出 RFC3339Nano（带时区+纳秒），Jackson LocalDateTime 默认无法解析。
type LocalTime time.Time

// Now 返回当前本地时间。
func Now() LocalTime { return LocalTime(time.Now()) }

// Time 返回底层 time.Time。
func (t LocalTime) Time() time.Time { return time.Time(t) }

// MarshalJSON 自定义序列化，截到秒。
func (t LocalTime) MarshalJSON() ([]byte, error) {
	s := time.Time(t).Format(`"2006-01-02T15:04:05"`)
	return []byte(s), nil
}

// UnmarshalJSON 兼容 ISO 局部时间与 RFC3339（带时区）。
func (t *LocalTime) UnmarshalJSON(data []byte) error {
	s := strings.Trim(string(data), `"`)
	if s == "" || s == "null" {
		return nil
	}
	for _, layout := range []string{"2006-01-02T15:04:05", time.RFC3339, time.RFC3339Nano} {
		if v, err := time.ParseInLocation(layout, s, time.Local); err == nil {
			*t = LocalTime(v)
			return nil
		}
	}
	return nil
}

// Provider 监控目标提供者接口。
//
// 实现要求：
//   - Type() 返回的字符串必须与服务端 monitor_target.type_code 对齐（如 "cursor"）
//   - Snapshot() 必须 best-effort：读不到数据时返回空切片而非 error，避免阻塞 reporter
//   - 实现需自己处理缓存（如 SQLite WAL mtime），Reporter 会以固定 tick 调用
//   - IsInstalled() 仅依赖文件系统指纹判断目标软件是否安装，要轻量、可频繁调用；
//     用于 status 输出与 reporter 日志展示，不参与 Snapshot 流程（未安装时 Snapshot 仍会被调用，
//     但 Provider 内部应自己短路返回空 Snapshot）
type Provider interface {
	Type() string
	TargetVersion() string
	IsInstalled() bool
	Snapshot(ctx context.Context) (Snapshot, error)
}

// AccountProvider 可选扩展：监控目标自带"用户账号"概念时实现这个接口。
//
// reporter 在每个 tick 通过 type assertion 调用 Account()，把首个非空账号塞进 deviceState 一起上报。
// 当前由 cursor.Provider 实现（cursorAuth/* ItemTable）；未来 claude / codex 也可同样接入。
type AccountProvider interface {
	Account() Account
}

// 用于会话扫描时间窗的两个标准取值。
//
//	DefaultLookback   稳态窗口：只扫近 48h 内有活动的会话
//	BootstrapLookback 首次安装 / schema 升版重置 / 运维清空 cursors.json 时使用：
//	                  比 DefaultLookback 大得多的"全量历史"窗口，让 provider 在新装机器上
//	                  一次性把过去若干天的会话上送上来，员工不用手动 backfill。
//
// <p>BootstrapLookback 选 30d 而非 100y 的工程考量：cursor sqlite 在重度用户机器上
// 会累积上百会话 / 数万 bubble，100y 全扫等价于把 sqlite 全表 join 一遍，单次
// collect 跑几分钟、apiclient.Timeout 触发、大 body 上传又要几十秒——bootstrap tick
// 甚至完不成第一个循环。30d 既覆盖"我刚到岗"的真实新员工诉求，又把单次 collect
// 控制在秒级；老数据通过运维侧"重置 cursors.json"按需触发，不强制下发到所有人。
const (
	DefaultLookback   = 48 * time.Hour
	BootstrapLookback = 30 * 24 * time.Hour
)

// LookbackSetter 可选扩展：Provider 支持运行时切换扫描时间窗时实现这个接口。
//
// <p>reporter 在 New 阶段判断 cursors.json 是否为空，从而决定每个 provider 启动时
// 用 DefaultLookback 还是 BootstrapLookback；bootstrap 阶段所有积压消息发完后，
// 再调一次 SetLookback(DefaultLookback) 切回稳态，避免每个 tick 都跑全量扫描。
//
// <p>当前由 cursor / claude / codex / openclaw / openharness / hermes 6 个 Provider 实现；
// gitlog 这类不是按"会话窗口"采集的 provider 不实现，reporter 通过类型断言安全跳过。
type LookbackSetter interface {
	SetLookback(d time.Duration)
}

// Account 监控目标的登录账号信息。
//
//	Provider             所属监控目标 type_code（cursor / claude / ...）
//	Email                登录邮箱
//	MembershipType       付费档：free / pro / pro_plus / business / ultra / enterprise
//	SubscriptionStatus   订阅状态：active / canceled / past_due / trialing / 空
//	SignUpType           注册渠道：Auth_0 / Google / GitHub / 邮箱
type Account struct {
	Provider           string `json:"provider,omitempty"`
	Email              string `json:"email,omitempty"`
	MembershipType     string `json:"membership_type,omitempty"`
	SubscriptionStatus string `json:"subscription_status,omitempty"`
	SignUpType         string `json:"sign_up_type,omitempty"`
}

// IsZero 判断账号是否完全为空（reporter 用来决定是否上报）。
func (a Account) IsZero() bool {
	return a.Email == "" && a.MembershipType == "" && a.SubscriptionStatus == "" && a.SignUpType == ""
}

// Snapshot 单次采集得到的会话集合。
type Snapshot struct {
	Type                   string    `json:"type"`
	TargetVersion          string    `json:"target_version,omitempty"`
	CapturedAt             LocalTime `json:"captured_at"`
	Sessions               []Session `json:"sessions"`
	// SuppressedSessionIDs 被归并到父 chat、不应再单独展示的 external_session_id（如 Cursor Task 子 composer）。
	SuppressedSessionIDs   []string  `json:"suppressed_session_ids,omitempty"`
}

// Session 一个 AI 会话的当前状态视图。
type Session struct {
	SessionID         string    `json:"session_id"`
	Cwd               string    `json:"cwd,omitempty"`
	CwdHash           string    `json:"cwd_hash,omitempty"`
	GitBranch         string    `json:"git_branch,omitempty"`
	RepoURL           string    `json:"repo_url,omitempty"`
	ProjectName       string    `json:"project_name,omitempty"`
	IsWorktree        bool      `json:"is_worktree"`
	MainRepo          string    `json:"main_repo,omitempty"`
	Model             string    `json:"model,omitempty"`
	Status            string    `json:"status"`
	CurrentTool       string    `json:"current_tool,omitempty"`
	StartedAt         LocalTime `json:"started_at"`
	LastActivity      LocalTime `json:"last_activity"`
	UserMessages      int       `json:"user_messages"`
	AssistantMessages int       `json:"assistant_messages"`
	// SnapshotMessageCount 当前 tick 快照中的 recent_messages 条数（含 tool/thinking），供服务端判断回填进度。
	SnapshotMessageCount int    `json:"snapshot_message_count,omitempty"`
	InputTokens       int64     `json:"input_tokens"`
	OutputTokens      int64     `json:"output_tokens"`
	CacheCreateTokens int64     `json:"cache_create_tokens"`
	CacheReadTokens   int64     `json:"cache_read_tokens"`
	RecentTools       []Tool    `json:"recent_tools,omitempty"`
	RecentMessages    []Message `json:"recent_messages,omitempty"`
	// ActivityDeltas 带原始 event_time 的 token/消息增量（按源日志 turn 或 token_count 快照）。
	// reporter 按游标切片后上报；server 逐条写 ai_session_event，不再用 last_activity 代理所有增量。
	ActivityDeltas []ActivityDelta `json:"activity_deltas,omitempty"`
}

// ActivityDelta 单条 token/消息增量及其在客户端的发生时刻。
type ActivityDelta struct {
	EventTime         LocalTime `json:"event_time"`
	InputTokensDelta  int64     `json:"input_tokens_delta,omitempty"`
	OutputTokensDelta int64     `json:"output_tokens_delta,omitempty"`
	MessagesDelta     int       `json:"messages_delta,omitempty"`
	Source            string    `json:"source,omitempty"`
	SourceRef         string    `json:"source_ref,omitempty"`
}

// Tool 单次工具调用记录。
type Tool struct {
	Name      string    `json:"name"`
	Timestamp LocalTime `json:"timestamp"`
}

// Message 单条会话消息原文（团队内部公开使用，不截断）。
type Message struct {
	ExternalMessageID string        `json:"external_message_id,omitempty"`
	Role              string        `json:"role"`
	Text              string        `json:"text,omitempty"`
	ContentParts      []ContentPart `json:"content_parts,omitempty"`
	ToolName          string        `json:"tool_name,omitempty"`
	Timestamp         LocalTime     `json:"timestamp"`
	// ConversationOrder Cursor 等 provider 在会话内的对话顺序（header 序号，1 起）。
	// 与入库 sequence_no 不同；用于展示排序，解决续聊批量改写 createdAt 导致的乱序。
	ConversationOrder int           `json:"conversation_order,omitempty"`
	InputTokens       int           `json:"input_tokens"`
	OutputTokens      int           `json:"output_tokens"`
}

// FinalizeMessage 根据 ContentParts 填充 Text（legacy 扁平字段）。
func FinalizeMessage(m *Message) {
	if m == nil {
		return
	}
	if len(m.ContentParts) > 0 && m.Text == "" {
		m.Text = FlattenParts(m.ContentParts)
	}
}

// Registry 持有当前进程注册的所有 Provider。
//
// 不实现复杂调度：Reporter 在每个 tick 主动 Snapshot 所有 Provider。
type Registry struct {
	mu        sync.RWMutex
	providers []Provider
}

// NewRegistry 创建空 registry。
func NewRegistry() *Registry {
	return &Registry{}
}

// Register 添加一个 Provider，重名直接覆盖（不会出现，因为 Type 是唯一键）。
func (r *Registry) Register(p Provider) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for i, existing := range r.providers {
		if existing.Type() == p.Type() {
			r.providers[i] = p
			return
		}
	}
	r.providers = append(r.providers, p)
}

// All 返回当前所有 Provider 的快照（slice 拷贝，可在锁外遍历）。
func (r *Registry) All() []Provider {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]Provider, len(r.providers))
	copy(out, r.providers)
	return out
}

// InstalledCount 返回当前已安装的 Provider 数量（用于 reporter 日志展示）。
//
// 单次调用会遍历全部 Provider 的 IsInstalled()，因此实现必须保持轻量。
func (r *Registry) InstalledCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	n := 0
	for _, p := range r.providers {
		if p.IsInstalled() {
			n++
		}
	}
	return n
}
