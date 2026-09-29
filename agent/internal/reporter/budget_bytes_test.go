package reporter

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// ---- 测试数据构造 ----

var budgetT0 = time.Date(2026, 9, 29, 10, 0, 0, 0, time.Local)

// mkMsgs 生成 n 条消息：ID "<prefix>-m000…"，时间戳逐条 +1s，正文 size 字节。
func mkMsgs(prefix string, n, size int) []monitor.Message {
	out := make([]monitor.Message, n)
	for i := range out {
		out[i] = monitor.Message{
			ExternalMessageID: fmt.Sprintf("%s-m%03d", prefix, i),
			Role:              "assistant",
			Text:              strings.Repeat("x", size),
			Timestamp:         monitor.LocalTime(budgetT0.Add(time.Duration(i) * time.Second)),
		}
	}
	return out
}

func mkSess(id, status string, lastActivity time.Time, msgs []monitor.Message) monitor.Session {
	return monitor.Session{
		SessionID:      id,
		Status:         status,
		LastActivity:   monitor.LocalTime(lastActivity),
		StartedAt:      monitor.LocalTime(budgetT0),
		InputTokens:    100,
		RecentMessages: msgs,
	}
}

// newBudgetReporter 构造只带游标 store 的 Reporter（游标写到临时目录，Save 真实生效）。
func newBudgetReporter(t *testing.T) *Reporter {
	t.Helper()
	return &Reporter{
		cursors: &MsgCursorStore{
			path:    filepath.Join(t.TempDir(), cursorsFileName),
			cursors: make(map[string]MsgCursor),
		},
		nowFn: func() time.Time { return budgetT0.Add(time.Hour) },
	}
}

// runTick 复现 tickOnce 里"采集之后、发送之前"的全部步骤：游标切片 → 去重未变化空闲会话 → 瘦身 → 字节预算。
// build 每次返回 provider 视角的全新快照（生产里 provider 每个 tick 也是重新构造）。
func (r *Reporter) runTick(build func() []monitor.Snapshot, budget int) ([]monitor.Snapshot, budgetResult) {
	mons := build()
	r.applyMsgCursors(mons)
	r.dropUnchangedIdleSessions(mons, r.clock())
	stripRedundantText(mons)
	res := r.applyByteBudget(mons, budget, r.clock())
	return mons, res
}

// commit 模拟"本 tick 上报成功"。
func (r *Reporter) commit() {
	r.commitPendingCursors()
	r.commitSentSessions()
}

func sentIDs(mons []monitor.Snapshot) map[string][]string {
	out := make(map[string][]string)
	for _, s := range mons {
		for _, sess := range s.Sessions {
			key := cursorKey(s.Type, sess.SessionID)
			ids := out[key]
			for _, m := range sess.RecentMessages {
				ids = append(ids, m.ExternalMessageID)
			}
			out[key] = ids
		}
	}
	return out
}

func bodySize(mons []monitor.Snapshot) int {
	b, _ := json.Marshal(mons)
	return len(b)
}

// ---- 核心：游标只推进到"实际放进 body 的最后一条"，多 tick 排空后不丢不重 ----

func TestByteBudget_MultiTickDrain_NoLossNoDuplicate(t *testing.T) {
	const (
		nSessions = 7
		nMsgs     = 30
		msgSize   = 2000
		budget    = 60 << 10
	)
	build := func() []monitor.Snapshot {
		var sessions []monitor.Session
		for i := 0; i < nSessions; i++ {
			id := fmt.Sprintf("s%d", i)
			// LastActivity 各不相同：既覆盖"新的先发"的排序，也让同一 tick 内选入顺序 ≠ body 顺序。
			sessions = append(sessions, mkSess(id, "idle", budgetT0.Add(time.Duration(i)*time.Minute), mkMsgs(id, nMsgs, msgSize)))
		}
		return []monitor.Snapshot{{Type: "claude", Sessions: sessions}}
	}

	r := newBudgetReporter(t)
	got := make(map[string][]string)
	ticks := 0
	for ; ticks < 200; ticks++ {
		mons, res := r.runTick(build, budget)
		if n := bodySize(mons); n > budget {
			t.Fatalf("tick %d body %d bytes exceeds budget %d", ticks, n, budget)
		}
		sentThisTick := 0
		for key, ids := range sentIDs(mons) {
			got[key] = append(got[key], ids...)
			sentThisTick += len(ids)
		}
		if sentThisTick == 0 && res.deferred() {
			t.Fatalf("tick %d made no progress although data is deferred", ticks)
		}
		r.commit()
		if !res.deferred() {
			break
		}
	}
	if ticks < 3 {
		t.Fatalf("expected the backlog to take several ticks, drained in %d", ticks+1)
	}

	total := 0
	for i := 0; i < nSessions; i++ {
		key := cursorKey("claude", fmt.Sprintf("s%d", i))
		ids := got[key]
		total += len(ids)
		if len(ids) != nMsgs {
			t.Errorf("%s: sent %d messages, want %d (loss or duplicate)", key, len(ids), nMsgs)
			continue
		}
		for j, id := range ids {
			if want := fmt.Sprintf("s%d-m%03d", i, j); id != want {
				t.Errorf("%s: position %d = %s, want %s (order broken / gap)", key, j, id, want)
				break
			}
		}
	}
	if total != nSessions*nMsgs {
		t.Errorf("total sent %d, want %d", total, nSessions*nMsgs)
	}

	// 排空之后再来一个 tick：全部是"未变化的 idle 会话"，什么都不该再发。
	mons, res := r.runTick(build, budget)
	if n := len(sentIDs(mons)); n != 0 || res.deferred() {
		t.Errorf("after draining, next tick still sends %d sessions (deferred=%v)", n, res.deferred())
	}
}

// 失败的 tick 不提交：下个 tick 拿到的内容与失败那次完全一致（游标没有被越过）。
func TestByteBudget_FailedTickDoesNotAdvance(t *testing.T) {
	build := func() []monitor.Snapshot {
		return []monitor.Snapshot{{Type: "codex", Sessions: []monitor.Session{
			mkSess("a", "idle", budgetT0, mkMsgs("a", 40, 3000)),
			mkSess("b", "idle", budgetT0.Add(time.Minute), mkMsgs("b", 40, 3000)),
		}}}
	}
	r := newBudgetReporter(t)
	first, res1 := r.runTick(build, 50<<10)
	if !res1.deferred() {
		t.Fatal("test setup: expected a deferral")
	}
	want := sentIDs(first)
	// 不 commit（模拟发送失败），直接进入下一个 tick。
	second, _ := r.runTick(build, 50<<10)
	got := sentIDs(second)
	if fmt.Sprint(want) != fmt.Sprint(got) {
		t.Errorf("a failed tick must be retried with identical content\nfirst =%v\nsecond=%v", want, got)
	}
	if n := r.cursors.Size(); n != 0 {
		t.Errorf("cursors advanced (%d entries) without a successful send", n)
	}
}

// 单个会话本身超预算：按消息前缀切，游标恰好停在保留下来的最后一条，下个 tick 从下一条接着发。
func TestByteBudget_SingleOversizeSession_SplitByMessages(t *testing.T) {
	msgs := mkMsgs("big", 50, 10_000)
	build := func() []monitor.Snapshot {
		return []monitor.Snapshot{{Type: "claude", Sessions: []monitor.Session{mkSess("big", "idle", budgetT0, append([]monitor.Message(nil), msgs...))}}}
	}
	r := newBudgetReporter(t)
	mons, res := r.runTick(build, 64<<10)
	kept := mons[0].Sessions[0].RecentMessages
	if len(kept) < 3 || len(kept) > 7 {
		t.Fatalf("kept %d messages, expected ~5 (64KB / ~10KB)", len(kept))
	}
	if res.deferredMessages != 50-len(kept) {
		t.Errorf("deferredMessages=%d want %d", res.deferredMessages, 50-len(kept))
	}
	key := cursorKey("claude", "big")
	if got, want := r.pending[key].LastMsgID, kept[len(kept)-1].ExternalMessageID; got != want {
		t.Errorf("pending cursor = %s, want the last KEPT message %s (never the last collected one)", got, want)
	}
	r.commit()
	cur, _ := r.cursors.Get("claude", "big")
	if cur.LastMsgID != kept[len(kept)-1].ExternalMessageID {
		t.Errorf("committed cursor %s, want %s", cur.LastMsgID, kept[len(kept)-1].ExternalMessageID)
	}
	next, _ := r.runTick(build, 64<<10)
	if first := next[0].Sessions[0].RecentMessages[0].ExternalMessageID; first != msgs[len(kept)].ExternalMessageID {
		t.Errorf("second tick starts at %s, want %s", first, msgs[len(kept)].ExternalMessageID)
	}
}

// 每个 tick 至少发一个会话；首个会话自己的第一条消息就超预算时也要放行（否则永远空转）。
func TestByteBudget_AlwaysMakesProgress(t *testing.T) {
	build := func() []monitor.Snapshot {
		return []monitor.Snapshot{{Type: "claude", Sessions: []monitor.Session{
			mkSess("huge", "idle", budgetT0.Add(time.Hour), mkMsgs("huge", 3, 200_000)),
			mkSess("small", "idle", budgetT0, mkMsgs("small", 2, 100)),
		}}}
	}
	r := newBudgetReporter(t)
	mons, res := r.runTick(build, 8<<10) // 预算比一条消息还小
	if res.keptSessions != 1 || res.keptMessages != 1 {
		t.Fatalf("kept sessions=%d messages=%d, want exactly the forced 1/1", res.keptSessions, res.keptMessages)
	}
	if len(mons[0].Sessions) != 1 || mons[0].Sessions[0].SessionID != "huge" {
		t.Fatalf("forced session should be the highest-priority one (most recent), got %+v", mons[0].Sessions)
	}
	if !res.minimal {
		t.Error("a 1-session/1-message body is the minimal unit")
	}
	if !res.deferred() {
		t.Error("the rest must be deferred to later ticks")
	}
	// 后续 tick 逐步排空，不会卡死。
	for i := 0; i < 20 && res.deferred(); i++ {
		r.commit()
		_, res = r.runTick(build, 8<<10)
	}
	if res.deferred() {
		t.Error("backlog never drained")
	}
}

// 整会话推迟：既不在 body 里，也没有暂存游标 / 暂存指纹——否则会被当成"已发送"而丢数据。
func TestByteBudget_DeferredSessionIsNotMarkedSent(t *testing.T) {
	build := func() []monitor.Snapshot {
		return []monitor.Snapshot{{Type: "claude", Sessions: []monitor.Session{
			mkSess("first", "idle", budgetT0.Add(2*time.Hour), mkMsgs("first", 10, 4000)),
			mkSess("second", "idle", budgetT0.Add(time.Hour), mkMsgs("second", 10, 4000)),
			mkSess("third", "idle", budgetT0, mkMsgs("third", 10, 4000)),
		}}}
	}
	r := newBudgetReporter(t)
	mons, res := r.runTick(build, 48<<10)
	if res.deferredSessions == 0 {
		t.Fatal("test setup: expected at least one whole-session deferral")
	}
	inBody := map[string]bool{}
	for _, s := range mons[0].Sessions {
		inBody[s.SessionID] = true
	}
	for _, id := range []string{"first", "second", "third"} {
		key := cursorKey("claude", id)
		_, hasPending := r.pending[key]
		_, hasSent := r.pendingSent[key]
		if inBody[id] != hasPending {
			t.Errorf("%s: inBody=%v but pending cursor present=%v", id, inBody[id], hasPending)
		}
		if inBody[id] != hasSent {
			t.Errorf("%s: inBody=%v but staged fingerprint present=%v", id, inBody[id], hasSent)
		}
	}
	r.commit()
	for _, id := range []string{"first", "second", "third"} {
		if _, ok := r.cursors.Get("claude", id); ok != inBody[id] {
			t.Errorf("%s: cursor committed=%v but inBody=%v", id, ok, inBody[id])
		}
	}
}

// 只有标量字段变化（没有新消息）的会话被推迟：指纹不能被提交，否则下个 tick 会被当作"未变化"而永远发不出去。
func TestByteBudget_DeferredScalarOnlySessionIsResentNextTick(t *testing.T) {
	tokens, nBig := int64(100), 30
	build := func() []monitor.Snapshot {
		scalar := mkSess("scalar", "idle", budgetT0, nil)
		scalar.InputTokens = tokens
		return []monitor.Snapshot{{Type: "claude", Sessions: []monitor.Session{
			mkSess("big", "idle", budgetT0.Add(time.Hour), mkMsgs("big", nBig, 3000)),
			scalar,
		}}}
	}
	r := newBudgetReporter(t)
	// 第一轮：两个会话都发出（预算充足），scalar 的指纹落账。
	r.runTick(build, 1<<20)
	r.commit()

	tokens, nBig = 999, 90 // scalar 会话的标量字段变了（没有新消息）；big 同时来了一大批新消息
	mons, res := r.runTick(build, 32<<10)
	if res.deferredSessions != 1 {
		t.Fatalf("setup: expected the scalar-only session to be deferred, res=%+v sessions=%+v", res, mons[0].Sessions)
	}
	r.commit()

	// 下一轮：big 的剩余消息还在排空，但 scalar 必须重新出现在 body 里（它的新指纹从未被成功发送）。
	for i := 0; i < 20; i++ {
		mons, res = r.runTick(build, 32<<10)
		for _, s := range mons[0].Sessions {
			if s.SessionID == "scalar" {
				return
			}
		}
		r.commit()
		if !res.deferred() {
			break
		}
	}
	t.Fatal("the deferred scalar-only session was never re-sent: its fingerprint was committed without being sent")
}

// 非 idle 会话优先入选（服务端 active 信号依赖它们），其后按最近活动从新到旧；body 内顺序保持原样。
func TestByteBudget_PriorityAndBodyOrder(t *testing.T) {
	build := func() []monitor.Snapshot {
		return []monitor.Snapshot{{Type: "claude", Sessions: []monitor.Session{
			mkSess("old-idle", "idle", budgetT0, mkMsgs("old", 10, 4000)),
			mkSess("new-idle", "idle", budgetT0.Add(time.Hour), mkMsgs("new", 10, 4000)),
			mkSess("working", "working", budgetT0.Add(-time.Hour), mkMsgs("w", 3, 100)),
		}}}
	}
	r := newBudgetReporter(t)
	mons, _ := r.runTick(build, 48<<10)
	var order []string
	for _, s := range mons[0].Sessions {
		order = append(order, s.SessionID)
	}
	if got := strings.Join(order, ","); got != "new-idle,working" && got != "old-idle,working" {
		// 48KB 只够放下一个 ~40KB 的 idle 会话 + working；body 顺序 = 原顺序。
		t.Fatalf("unexpected body: %s", got)
	}
	if order[len(order)-1] != "working" {
		t.Errorf("working session must be kept (it drives the server's active signal): %v", order)
	}
	if order[0] != "new-idle" {
		t.Errorf("among idle sessions the most recently active goes first: %v", order)
	}
}

// 消息被截尾时 activity_deltas 整体保留，delta 游标不受影响。
func TestByteBudget_DeltasKeptAndCursorSeparate(t *testing.T) {
	msgs := mkMsgs("d", 20, 5000)
	sess := mkSess("d", "idle", budgetT0, msgs)
	for i := 0; i < 5; i++ {
		sess.ActivityDeltas = append(sess.ActivityDeltas, monitor.ActivityDelta{
			EventTime:        monitor.LocalTime(budgetT0.Add(time.Duration(i) * time.Second)),
			InputTokensDelta: int64(i + 1),
			SourceRef:        fmt.Sprintf("d-ev%d", i),
		})
	}
	build := func() []monitor.Snapshot {
		s := sess
		s.RecentMessages = append([]monitor.Message(nil), msgs...)
		s.ActivityDeltas = append([]monitor.ActivityDelta(nil), sess.ActivityDeltas...)
		return []monitor.Snapshot{{Type: "codex", Sessions: []monitor.Session{s}}}
	}
	r := newBudgetReporter(t)
	mons, res := r.runTick(build, 40<<10)
	if !res.deferred() {
		t.Fatal("test setup: expected message truncation")
	}
	if got := len(mons[0].Sessions[0].ActivityDeltas); got != 5 {
		t.Errorf("deltas kept = %d, want all 5", got)
	}
	p := r.pending[cursorKey("codex", "d")]
	if p.LastDeltaRef != "d-ev4" {
		t.Errorf("delta cursor = %q, want d-ev4", p.LastDeltaRef)
	}
	kept := mons[0].Sessions[0].RecentMessages
	if p.LastMsgID != kept[len(kept)-1].ExternalMessageID {
		t.Errorf("message cursor = %q, want last kept %q", p.LastMsgID, kept[len(kept)-1].ExternalMessageID)
	}
}

// 没有 external_message_id 的消息只能靠"时间戳严格大于游标"切片：不能把截断点放在同一时刻的两条消息之间，
// 否则后一条会被永久跳过。
func TestSafeCut(t *testing.T) {
	ts := func(sec int) monitor.LocalTime {
		return monitor.LocalTime(budgetT0.Add(time.Duration(sec) * time.Second))
	}
	noID := func(secs ...int) []monitor.Message {
		var out []monitor.Message
		for _, s := range secs {
			out = append(out, monitor.Message{Role: "assistant", Timestamp: ts(s)})
		}
		return out
	}
	withID := noID(1, 1, 1, 1)
	for i := range withID {
		withID[i].ExternalMessageID = fmt.Sprintf("id%d", i)
	}
	cases := []struct {
		name string
		msgs []monitor.Message
		k    int
		want int
	}{
		{"with IDs any cut is safe", withID, 2, 2},
		{"no IDs, timestamps distinct", noID(1, 2, 3, 4), 2, 2},
		{"no IDs, cut between equal timestamps retreats", noID(1, 2, 2, 2, 3), 3, 1},
		{"no IDs, cannot retreat below 1: advance to next boundary", noID(1, 1, 1, 2), 1, 3},
		{"no IDs, all equal: send everything", noID(5, 5, 5), 1, 3},
		{"k at end", noID(1, 2), 2, 2},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := safeCut(tc.msgs, tc.k)
			if got != tc.want {
				t.Fatalf("safeCut(k=%d) = %d, want %d", tc.k, got, tc.want)
			}
			if !cutSafeForCursor(tc.msgs, got) {
				t.Errorf("result %d is not a cursor-safe cut", got)
			}
		})
	}
}

// 无 ID 消息的端到端：按时间戳游标续传，不丢同一时刻的消息。
func TestByteBudget_IDlessMessagesSameTimestamp_NoLoss(t *testing.T) {
	var msgs []monitor.Message
	for i := 0; i < 24; i++ {
		msgs = append(msgs, monitor.Message{
			Role: "assistant", Text: strings.Repeat("y", 3000),
			// 每 3 条共用一个时间戳。
			Timestamp: monitor.LocalTime(budgetT0.Add(time.Duration(i/3) * time.Second)),
			ToolName:  fmt.Sprintf("n%02d", i), // 用 tool_name 当作可追踪的标识
		})
	}
	build := func() []monitor.Snapshot {
		return []monitor.Snapshot{{Type: "x", Sessions: []monitor.Session{
			mkSess("s", "idle", budgetT0, append([]monitor.Message(nil), msgs...)),
		}}}
	}
	r := newBudgetReporter(t)
	var seen []string
	for i := 0; i < 50; i++ {
		mons, res := r.runTick(build, 12<<10)
		for _, s := range mons {
			for _, ss := range s.Sessions {
				for _, m := range ss.RecentMessages {
					seen = append(seen, m.ToolName)
				}
			}
		}
		r.commit()
		if !res.deferred() {
			break
		}
	}
	if len(seen) != len(msgs) {
		t.Fatalf("sent %d messages, want %d: %v", len(seen), len(msgs), seen)
	}
	for i, id := range seen {
		if id != fmt.Sprintf("n%02d", i) {
			t.Fatalf("position %d = %s (gap or reorder): %v", i, id, seen)
		}
	}
}

// ---- 瘦身：有 parts 时省掉重复的 text ----

func TestStripRedundantText(t *testing.T) {
	parts := []monitor.ContentPart{{Type: "text", Text: "hello"}}
	cases := []struct {
		name     string
		msg      monitor.Message
		wantText string
	}{
		{"assistant with parts: text dropped", monitor.Message{Role: "assistant", Text: "hello", ContentParts: parts}, ""},
		{"user with parts: text dropped", monitor.Message{Role: "user", Text: "hello", ContentParts: parts}, ""},
		{"tool with parts: text dropped", monitor.Message{Role: "tool", Text: "out", ContentParts: parts}, ""},
		{"no parts: text is the only content, keep", monitor.Message{Role: "assistant", Text: "hello"}, "hello"},
		{"empty text stays empty", monitor.Message{Role: "assistant", ContentParts: parts}, ""},
		{"user local-command wrapper: server filters on text, keep", monitor.Message{Role: "user", Text: "<local-command-stdout>ok</local-command-stdout>", ContentParts: parts}, "<local-command-stdout>ok</local-command-stdout>"},
		{"user command-name wrapper: keep", monitor.Message{Role: "User", Text: "<command-name>/usage</command-name>", ContentParts: parts}, "<command-name>/usage</command-name>"},
		{"user Unknown command: keep", monitor.Message{Role: "user", Text: "Unknown command: /x", ContentParts: parts}, "Unknown command: /x"},
		{"assistant that merely looks like a wrapper: server only filters user rows", monitor.Message{Role: "assistant", Text: "<command-name>x", ContentParts: parts}, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			orig := []monitor.Message{tc.msg}
			mons := []monitor.Snapshot{{Type: "claude", Sessions: []monitor.Session{{SessionID: "s", RecentMessages: orig}}}}
			stripRedundantText(mons)
			got := mons[0].Sessions[0].RecentMessages[0]
			if got.Text != tc.wantText {
				t.Errorf("Text = %q, want %q", got.Text, tc.wantText)
			}
			if len(got.ContentParts) != len(tc.msg.ContentParts) {
				t.Error("content_parts must never be touched")
			}
			if orig[0].Text != tc.msg.Text {
				t.Error("provider-owned backing array was mutated in place (would corrupt provider caches)")
			}
		})
	}
}

// 瘦身确实缩小了线上字节。
func TestStripRedundantText_ShrinksBody(t *testing.T) {
	text := strings.Repeat("payload ", 500)
	m := monitor.Message{Role: "assistant", Text: text, ContentParts: []monitor.ContentPart{{Type: "text", Text: text}}}
	mons := []monitor.Snapshot{{Type: "claude", Sessions: []monitor.Session{{SessionID: "s", RecentMessages: []monitor.Message{m}}}}}
	before := bodySize(mons)
	stripRedundantText(mons)
	after := bodySize(mons)
	if after > before*6/10 {
		t.Errorf("body %d -> %d bytes, expected roughly half", before, after)
	}
}

// ---- 自适应预算 / 413 ----

func TestBodyBudget_ShrinkAndRegrow(t *testing.T) {
	b := newBodyBudget(4 << 20)
	if b.current() != 4<<20 {
		t.Fatalf("initial = %d", b.current())
	}
	b.onTooLarge(3 << 20) // 刚失败的 body 3MB → 下一个预算 1.5MB
	if got := b.current(); got != (3<<20)/2 {
		t.Errorf("after 413 on 3MB body: %d, want %d", got, (3<<20)/2)
	}
	b.onTooLarge(0) // 不知道大小：至少减半
	if got := b.current(); got != (3<<20)/4 {
		t.Errorf("after second 413: %d, want %d", got, (3<<20)/4)
	}
	for i := 0; i < 20; i++ {
		b.onTooLarge(1)
	}
	if got := b.current(); got != minBodyBudget {
		t.Errorf("shrink must stop at the floor %d, got %d", minBodyBudget, got)
	}
	cur := b.current()
	for i := 0; i < 100; i++ {
		b.onSuccess(100) // 稳态的小 body：与预算无关，不算数
	}
	if b.current() != cur {
		t.Fatal("tiny bodies say nothing about the gateway limit; they must not regrow the budget")
	}
	for i := 0; i < budgetRegrowAfter-1; i++ {
		b.onSuccess(cur)
	}
	if b.current() != cur {
		t.Error("must not regrow before enough consecutive successes")
	}
	b.onSuccess(cur)
	if b.current() != cur*2 {
		t.Errorf("regrow = %d, want doubled %d", b.current(), cur*2)
	}
	for i := 0; i < 2000; i++ {
		b.onSuccess(b.current())
	}
	if b.current() != 4<<20 {
		t.Errorf("regrow must be capped at the configured max, got %d", b.current())
	}
	var zero bodyBudget
	if zero.current() <= 0 {
		t.Error("zero value must yield a usable default")
	}
}

// 413 且 body 已是最小单元：搁置该会话一段时间，其他会话照常上报；到期后重新尝试。数据不丢（游标没动）。
func TestHandleTooLarge_QuarantinesUnshrinkableSession(t *testing.T) {
	build := func() []monitor.Snapshot {
		return []monitor.Snapshot{{Type: "claude", Sessions: []monitor.Session{
			mkSess("poison", "idle", budgetT0.Add(time.Hour), mkMsgs("poison", 2, 300_000)),
			mkSess("ok", "idle", budgetT0, mkMsgs("ok", 3, 100)),
		}}}
	}
	now := budgetT0.Add(2 * time.Hour)
	r := newBudgetReporter(t)
	r.nowFn = func() time.Time { return now }

	mons, res := r.runTick(build, 16<<10)
	if len(mons[0].Sessions) != 1 || mons[0].Sessions[0].SessionID != "poison" || !res.minimal {
		t.Fatalf("setup: expected the forced minimal body to be the poison session, got %+v (minimal=%v)", mons[0].Sessions, res.minimal)
	}
	budgetBefore := r.budget
	r.handleTooLarge(res, now) // 服务端对这个最小 body 回 413
	if r.budget != budgetBefore {
		t.Error("a minimal body cannot be fixed by shrinking the budget; the session should be quarantined instead")
	}

	// 下个 tick：poison 被搁置，其余会话正常发出。
	mons, res = r.runTick(build, 16<<10)
	if res.quarantined != 1 {
		t.Errorf("quarantined = %d, want 1", res.quarantined)
	}
	if len(mons[0].Sessions) != 1 || mons[0].Sessions[0].SessionID != "ok" {
		t.Fatalf("other sessions must keep flowing, got %+v", mons[0].Sessions)
	}
	if res.deferred() {
		t.Error("a quarantined session must not count as deferred (would pin bootstrap / fast cadence for an hour)")
	}
	if _, ok := r.pending[cursorKey("claude", "poison")]; ok {
		t.Error("quarantined session must not advance its cursor")
	}

	// 到期后重新尝试（游标没动，数据还在）。
	now = now.Add(quarantineTTL + time.Second)
	mons, _ = r.runTick(build, 16<<10)
	found := false
	for _, s := range mons[0].Sessions {
		if s.SessionID == "poison" {
			found = true
		}
	}
	if !found {
		t.Error("after the quarantine expires the session must be retried")
	}
}

// 413 且 body 里不止一个最小单元：预算收缩到刚失败 body 的一半。
func TestHandleTooLarge_ShrinksBudgetForMultiUnitBody(t *testing.T) {
	r := newBudgetReporter(t)
	r.budget = newBodyBudget(4 << 20)
	r.handleTooLarge(budgetResult{usedBytes: 2 << 20, keptSessions: 3, keptMessages: 40}, budgetT0)
	if got := r.budget.current(); got != 1<<20 {
		t.Errorf("budget after 413 = %d, want %d", got, 1<<20)
	}
	if len(r.quarantine) != 0 {
		t.Error("no quarantine for a shrinkable body")
	}
}

// 空快照 / 全部被去重：不崩、不改动。
func TestApplyByteBudget_Empty(t *testing.T) {
	r := newBudgetReporter(t)
	if res := r.applyByteBudget(nil, 1024, budgetT0); res.deferred() || res.keptSessions != 0 {
		t.Errorf("unexpected result for empty input: %+v", res)
	}
	mons := []monitor.Snapshot{{Type: "claude"}}
	if res := r.applyByteBudget(mons, 1024, budgetT0); res.deferred() {
		t.Errorf("unexpected result: %+v", res)
	}
}
