package monitor

import (
	"context"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/logger"
)

// Token 合理性护栏（客户端一侧）。
//
// 背景：控制台「Token 走势」= SUM(daily_summary.total_*_tokens)，而后者是"last_activity 落在当日的会话累计值之和"，
// ai_session.input_tokens 又是服务端原样采信客户端上报值。任何 provider 解析出的异常大值（典型：pre-1.3.3 的子 agent
// 归并每个 tick 原地累加缓存对象——见 monitors/common/subagent_merge.go 与 docs/ops/token-anomaly.md）都会一路
// 流到趋势图，且会话滑出回看窗口后不再被上报，坏值永远留在库里。所以上报前在客户端也钳一道，与服务端
// TokenSanityGuard 用同一组默认上限（可用 AM_TOKEN_MAX_* 环境变量覆盖）：
//
//	单会话累计 input / output            ≤ 20 亿
//	单会话累计 cache_read / cache_create ≤ 100 亿（缓存读每个请求都会把整段前缀再计一遍，故放宽）
//	单条 activity delta / 单条消息 token ≤ 5 亿
//
// 依据见服务端 TokenSanitySupport 的 Javadoc：单请求上下文 ≤ 数 M，重度 agent 24h 连续跑也就 ~2 万次请求。
//
// 处理：负数或超上限的值一律"不采信"——
//   - 会话累计：回退到该会话在本进程内上一次的可信值，没有则 0；
//   - delta / 消息级 token：置 0（delta 记录本身与 messages_delta 保留，游标语义不变）；
//   - 打一条限流 WARN（不含任何消息内容，只有 provider / 会话 id / 字段 / 被拒值 / 上限）。
//
// 接入点：Registry.Register 把每个 Provider 包一层 sanitizingProvider，Snapshot 出口统一过一遍，
// 不需要改各 provider，也不需要改 reporter。

const (
	// MaxSessionTokens 单会话累计 input / output 上限：20 亿。
	MaxSessionTokens int64 = 2_000_000_000
	// MaxSessionCacheTokens 单会话累计 cache_read / cache_create 上限：100 亿。
	MaxSessionCacheTokens int64 = 10_000_000_000
	// MaxDeltaTokens 单条 activity delta / 消息级 token 上限：5 亿。
	MaxDeltaTokens int64 = 500_000_000

	// tokenWarnInterval 同一 (provider, 会话, 字段) 的 WARN 间隔。
	tokenWarnInterval = 10 * time.Minute
	// maxTrackedWarnKeys 限流表容量上限，超过整体清空。
	maxTrackedWarnKeys = 4096
)

// TokenLimits 一组上限。零值字段视为使用默认值。
type TokenLimits struct {
	MaxSession      int64
	MaxSessionCache int64
	MaxDelta        int64
}

// DefaultTokenLimits 返回默认上限，并允许 AM_TOKEN_MAX_SESSION / AM_TOKEN_MAX_SESSION_CACHE / AM_TOKEN_MAX_DELTA 覆盖。
func DefaultTokenLimits() TokenLimits {
	return TokenLimits{
		MaxSession:      envInt64("AM_TOKEN_MAX_SESSION", MaxSessionTokens),
		MaxSessionCache: envInt64("AM_TOKEN_MAX_SESSION_CACHE", MaxSessionCacheTokens),
		MaxDelta:        envInt64("AM_TOKEN_MAX_DELTA", MaxDeltaTokens),
	}
}

func (l TokenLimits) withDefaults() TokenLimits {
	if l.MaxSession <= 0 {
		l.MaxSession = MaxSessionTokens
	}
	if l.MaxSessionCache <= 0 {
		l.MaxSessionCache = MaxSessionCacheTokens
	}
	if l.MaxDelta <= 0 {
		l.MaxDelta = MaxDeltaTokens
	}
	return l
}

func envInt64(name string, def int64) int64 {
	if v := os.Getenv(name); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
			return n
		}
	}
	return def
}

// TokenTotals 一个会话的四个累计 token 字段。
type TokenTotals struct {
	Input       int64
	Output      int64
	CacheCreate int64
	CacheRead   int64
}

// TokenViolation 一次被拒的取值。不含任何内容，只有字段名与数值。
type TokenViolation struct {
	Field       string
	Value       int64
	Limit       int64
	Replacement int64
}

// tokenOK 判断 v 是否在 [0, max]（闭区间：刚好等于上限合法）。
func tokenOK(v, max int64) bool {
	return v >= 0 && v <= max
}

// SanitizeTokens 就地钳制 s 上的异常 token 值并返回被拒的取值（无异常返回 nil）。纯函数：不打日志、不持状态。
//
//	prev  该会话此前的可信累计值（未知传 nil）；会话累计异常时回退到它，没有则 0。
//
// 检查范围：Session 的四个累计字段、每条 ActivityDelta 的 input/output 增量、每条 RecentMessages 的 input/output。
func SanitizeTokens(s *Session, prev *TokenTotals, lim TokenLimits) []TokenViolation {
	if s == nil {
		return nil
	}
	lim = lim.withDefaults()
	var prevT TokenTotals
	if prev != nil {
		prevT = *prev
	}
	var out []TokenViolation

	total := func(field string, v *int64, max, fallback int64) {
		if tokenOK(*v, max) {
			return
		}
		out = append(out, TokenViolation{Field: field, Value: *v, Limit: max, Replacement: fallback})
		*v = fallback
	}
	total("input_tokens", &s.InputTokens, lim.MaxSession, prevT.Input)
	total("output_tokens", &s.OutputTokens, lim.MaxSession, prevT.Output)
	total("cache_create_tokens", &s.CacheCreateTokens, lim.MaxSessionCache, prevT.CacheCreate)
	total("cache_read_tokens", &s.CacheReadTokens, lim.MaxSessionCache, prevT.CacheRead)

	zero := func(field string, v *int64) {
		if tokenOK(*v, lim.MaxDelta) {
			return
		}
		out = append(out, TokenViolation{Field: field, Value: *v, Limit: lim.MaxDelta, Replacement: 0})
		*v = 0
	}
	for i := range s.ActivityDeltas {
		d := &s.ActivityDeltas[i]
		zero("delta_input_tokens", &d.InputTokensDelta)
		zero("delta_output_tokens", &d.OutputTokensDelta)
	}
	for i := range s.RecentMessages {
		m := &s.RecentMessages[i]
		in, outTok := int64(m.InputTokens), int64(m.OutputTokens)
		zero("message_input_tokens", &in)
		zero("message_output_tokens", &outTok)
		m.InputTokens, m.OutputTokens = int(in), int(outTok)
	}
	return out
}

// totalsOf 取 s 当前的四个累计字段。
func totalsOf(s *Session) TokenTotals {
	return TokenTotals{Input: s.InputTokens, Output: s.OutputTokens, CacheCreate: s.CacheCreateTokens, CacheRead: s.CacheReadTokens}
}

// TokenSanitizer 是 SanitizeTokens 的有状态外壳：记住每个会话上一次的可信累计值（用于回退），
// 并对 WARN 做限流。每个 Provider 一个实例（Snapshot 在各自 goroutine 内串行调用，仍加锁保平安）。
type TokenSanitizer struct {
	limits TokenLimits
	now    func() time.Time
	warnf  func(format string, args ...any)

	mu     sync.Mutex
	last   map[string]TokenTotals
	warned map[string]time.Time
	// rejected 累计被拒取值个数（排障 / 测试用）。
	rejected int64
}

// NewTokenSanitizer 创建实例；lim 的零值字段取默认。
func NewTokenSanitizer(lim TokenLimits) *TokenSanitizer {
	return &TokenSanitizer{
		limits: lim.withDefaults(),
		now:    time.Now,
		warnf:  logger.Warnf,
		last:   make(map[string]TokenTotals),
		warned: make(map[string]time.Time),
	}
}

// Rejected 返回累计被拒的取值个数。
func (t *TokenSanitizer) Rejected() int64 {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.rejected
}

// SanitizeSnapshot 对 snap 里的每个会话做 SanitizeTokens，返回被拒取值总数。
// 会话的"上一次可信累计值"表在每次调用后重建为本次快照里的会话集合，所以内存随活跃会话数有界。
func (t *TokenSanitizer) SanitizeSnapshot(providerType string, snap *Snapshot) int {
	if snap == nil {
		return 0
	}
	t.mu.Lock()
	defer t.mu.Unlock()

	next := make(map[string]TokenTotals, len(snap.Sessions))
	total := 0
	now := t.now()
	for i := range snap.Sessions {
		s := &snap.Sessions[i]
		key := providerType + "\x00" + s.SessionID
		var prev *TokenTotals
		if p, ok := t.last[key]; ok {
			pp := p
			prev = &pp
		}
		violations := SanitizeTokens(s, prev, t.limits)
		for _, v := range violations {
			total++
			t.rejected++
			t.warnLocked(now, providerType, s.SessionID, v)
		}
		next[key] = totalsOf(s)
	}
	t.last = next
	return total
}

func (t *TokenSanitizer) warnLocked(now time.Time, providerType, sessionID string, v TokenViolation) {
	if len(t.warned) > maxTrackedWarnKeys {
		t.warned = make(map[string]time.Time)
	}
	key := providerType + "\x00" + sessionID + "\x00" + v.Field
	if last, ok := t.warned[key]; ok && now.Sub(last) < tokenWarnInterval {
		return
	}
	t.warned[key] = now
	t.warnf("token sanity: provider=%s session=%s field=%s value=%d limit=%d -> not reported, using %d",
		providerType, sessionID, v.Field, v.Value, v.Limit, v.Replacement)
}

// sanitizingProvider 包装 Provider，在 Snapshot 出口统一做 token 钳制，其余方法（含可选接口）原样透传。
//
// 可选接口必须显式转发：reporter 通过 p.(LookbackSetter) / p.(WatchHints) / p.(AccountProvider) 做类型断言，
// 只嵌入 Provider 接口会让这些断言全部失败。包装类型对三者都"实现"，底层没有时退化为空操作 / 零值——
// reporter 对 nil WatchHints 与零 Account 本就按"不参与"处理。
type sanitizingProvider struct {
	Provider
	san *TokenSanitizer
}

// Snapshot 调用底层 Snapshot 后就地钳制。出错时原样返回，不动结果。
func (p *sanitizingProvider) Snapshot(ctx context.Context) (Snapshot, error) {
	snap, err := p.Provider.Snapshot(ctx)
	if err != nil {
		return snap, err
	}
	p.san.SanitizeSnapshot(p.Provider.Type(), &snap)
	return snap, nil
}

// Unwrap 返回被包装的 Provider（测试 / 排障用）。
func (p *sanitizingProvider) Unwrap() Provider { return p.Provider }

// SetLookback 转发给底层（若支持）。
func (p *sanitizingProvider) SetLookback(d time.Duration) {
	if ls, ok := p.Provider.(LookbackSetter); ok {
		ls.SetLookback(d)
	}
}

// WatchHints 转发给底层（若支持），否则 nil（不参与监听）。
func (p *sanitizingProvider) WatchHints() []string {
	if wh, ok := p.Provider.(WatchHints); ok {
		return wh.WatchHints()
	}
	return nil
}

// Account 转发给底层（若支持），否则零值（reporter 视为无账号）。
func (p *sanitizingProvider) Account() Account {
	if ap, ok := p.Provider.(AccountProvider); ok {
		return ap.Account()
	}
	return Account{}
}

// WithTokenSanity 把 p 包一层 token 钳制。已经包过的不重复包。
func WithTokenSanity(p Provider, lim TokenLimits) Provider {
	if p == nil {
		return nil
	}
	if _, ok := p.(*sanitizingProvider); ok {
		return p
	}
	return &sanitizingProvider{Provider: p, san: NewTokenSanitizer(lim)}
}
