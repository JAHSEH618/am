// budget.go：单个 /report 请求的字节预算，以及配套的 body 瘦身。
//
// <p>背景：首次安装（bootstrap）回填 30 天历史时，一个 tick 的 body 可达 200~400MB——服务端要把它整个读进内存
// （HMAC 需要全量字节）、逐会话开事务落库，是 2026-09 事故里连接池被打满的重负载源头之一，也是"超时设 15 分钟"
// 的原因。现在每个 tick 的 body 有字节预算（默认 4MiB JSON，压缩前），超出预算的会话 / 消息留给后面的 tick，
// 历史回填自然变成多个 tick 的小批次。
//
// <p>正确性核心：游标只在上报成功后推进，并且只能推进到"实际放进 body 的最后一条"。applyMsgCursors 暂存的
// r.pending 是按"切片后全部消息"算的，所以本文件在截断 / 推迟会话时必须同步改写 r.pending 与 r.pendingSent：
//   - 整会话推迟：删掉它的 pending 游标与 pendingSent 指纹（游标不动，也不会被误记成"已发送、可去重"）；
//   - 消息被截尾：游标改成保留下来的最后一条，被截掉的留给下个 tick 重新切片得到；
//   - 每个 tick 至少放行一个会话；单会话本身超预算时按消息前缀切到预算内，且至少放一条消息（哪怕这一条
//     自己就超预算），否则一个超大会话会让所有 tick 都空转。
//
// gz
package reporter

import (
	"encoding/json"
	"sort"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/logger"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const (
	// envelopeReserveBytes 给 reportRequest 外层（agent_id / device_state / captured_at 等）预留的字节数。
	envelopeReserveBytes = 4 << 10
	// minBodyBudget 413 收缩后预算的下限。比配置允许的最小值更小：收缩要能一路收敛到"单条消息"。
	minBodyBudget = 32 << 10
	// budgetRegrowAfter 连续成功多少个 tick 后把收缩过的预算翻倍（不超过配置值）。
	// 慢回升是为了在网关限额（如 nginx client_max_body_size）介于两个预算之间时避免隔一个 tick 就 413 一次。
	budgetRegrowAfter = 10
	// quarantineTTL 单条消息就触发 413（无法再缩小）时，该会话被搁置多久再重试。
	// 只是推迟、不丢数据：游标没动，到期后仍会重新尝试；搁置期间其他会话照常上报，不被它卡死。
	quarantineTTL = time.Hour
	// backlogDrainInterval 上个 tick 因预算 / 条数上限还有余量没发完时的下个 tick 间隔（有抖动、
	// 受失败退避约束）。没有它，bootstrap 回填会按 2 分钟一批的空闲节奏拖上数小时。
	backlogDrainInterval = 30 * time.Second
)

// bodyBudget 是自适应的单请求字节预算：默认取配置值，遇 413 收缩，连续成功后逐步回升。
type bodyBudget struct {
	max      int
	cur      int
	okStreak int
}

func newBodyBudget(max int) bodyBudget {
	if max <= 0 {
		max = config.DefaultReportBodyBudgetBytes
	}
	return bodyBudget{max: max, cur: max}
}

func (b *bodyBudget) current() int {
	if b.cur <= 0 {
		return config.DefaultReportBodyBudgetBytes
	}
	return b.cur
}

// onTooLarge 服务端 / 网关回 413：下一个预算取"刚失败的那个 body 实际大小"的一半，
// 每次 413 至少减半，几个 tick 内收敛到网关能接受的大小。
func (b *bodyBudget) onTooLarge(usedBytes int) {
	base := b.current()
	if usedBytes > 0 && usedBytes < base {
		base = usedBytes
	}
	b.cur = base / 2
	if b.cur < minBodyBudget {
		b.cur = minBodyBudget
	}
	b.okStreak = 0
}

// onSuccess 一个 tick 成功。只有"预算确实在起作用"（body 用了当前预算的一半以上）的成功才算数：
// 稳态下 body 只有几十 KB，与预算是否被网关拒绝过无关，不该因为它们就把预算悄悄涨回去、
// 等下一次回填再撞一次 413。
func (b *bodyBudget) onSuccess(usedBytes int) {
	if b.cur >= b.max || usedBytes*2 < b.cur {
		return
	}
	b.okStreak++
	if b.okStreak >= budgetRegrowAfter {
		b.cur *= 2
		if b.cur > b.max {
			b.cur = b.max
		}
		b.okStreak = 0
	}
}

// budgetResult 汇总 applyByteBudget 对本 tick body 的裁剪结果。
type budgetResult struct {
	usedBytes        int // 放进 body 的会话（含消息 / 增量）JSON 字节 + 外层预留，供 413 收缩参考
	keptSessions     int
	keptMessages     int
	deferredSessions int // 整会话推迟的个数
	deferredMessages int // 因预算没发出去的消息条数（整会话推迟的 + 被截尾的）
	quarantined      int // 因 413 被搁置的会话数（不计入 deferred：搁置期内它不该拖着 bootstrap / 加速节奏）
	// minimal 表示本次 body 已经缩到最小单元（1 个会话、≤1 条消息）：再遇到 413 无法靠缩预算解决。
	minimal  bool
	firstKey string
}

// deferred 本 tick 是否还有内容留给后面的 tick。
func (b budgetResult) deferred() bool { return b.deferredSessions > 0 || b.deferredMessages > 0 }

// jsonSize 返回 v 的 JSON 字节数；序列化失败按 0 计（不会发生，出错时宁可多放也不能卡住进度）。
func jsonSize(v any) int {
	b, err := json.Marshal(v)
	if err != nil {
		return 0
	}
	return len(b)
}

// messageValueBytes 是一条消息里所有字符串值的原始字节和——严格小于等于它的 JSON 大小
// （转义只会变长），所以可以拿它做"肯定放不下"的廉价预判，避免对注定要推迟的大消息白白序列化。
func messageValueBytes(m *monitor.Message) int {
	n := len(m.ExternalMessageID) + len(m.Role) + len(m.Text) + len(m.ToolName)
	for i := range m.ContentParts {
		p := &m.ContentParts[i]
		n += len(p.Type) + len(p.Text) + len(p.Mime) + len(p.Path) + len(p.OldPath) + len(p.Language) +
			len(p.ToolName) + len(p.ArgumentsJSON) + len(p.BlobGzipBase64) + len(p.TruncateReason)
	}
	return n
}

// sessionBaseBytes 会话去掉消息与增量后的 JSON 字节（含数组分隔逗号）。
func sessionBaseBytes(s monitor.Session) int {
	s.RecentMessages = nil
	s.ActivityDeltas = nil
	return jsonSize(s) + 1
}

// cutSafeForCursor 判断在 msgs[:k] 处截断后，游标（推进到 msgs[k-1]）能否精确切出 msgs[k:]。
// 有 external_message_id 时 sliceMessagesByCursor 按 ID 定位，任何位置都安全；
// 没有 ID 时退化为"时间戳严格大于游标时间"，若 msgs[k] 与 msgs[k-1] 同一时刻，msgs[k] 会被永久跳过。
func cutSafeForCursor(msgs []monitor.Message, k int) bool {
	if k <= 0 || k >= len(msgs) {
		return true
	}
	last := msgs[k-1]
	return last.ExternalMessageID != "" || msgs[k].Timestamp.Time().After(last.Timestamp.Time())
}

// safeCut 把截断位置 k 调整到 cutSafeForCursor 成立的位置：优先往前退（少发），退不动再往后进（多发一点）。
// 结果至少为 1（每个入选会话至少发一条消息，保证进度）。
func safeCut(msgs []monitor.Message, k int) int {
	if k >= len(msgs) {
		return len(msgs)
	}
	for j := k; j >= 1; j-- {
		if cutSafeForCursor(msgs, j) {
			return j
		}
	}
	for j := k + 1; j < len(msgs); j++ {
		if cutSafeForCursor(msgs, j) {
			return j
		}
	}
	return len(msgs)
}

// applyByteBudget 把本 tick 的 monitors 裁到 budget 字节以内（原地改写），并同步修正暂存的游标 / 指纹。
// 须在 applyMsgCursors 与 dropUnchangedIdleSessions 之后调用。
//
// 选入顺序：非 idle 会话优先（服务端的 active 信号与 work_session 活跃秒数依赖它们），其后按最近活动时间
// 从新到旧——新鲜数据先到，历史回填垫底。body 内的会话顺序保持原样，只有"选哪些"受这个优先级影响。
func (r *Reporter) applyByteBudget(monitors []monitor.Snapshot, budget int, now time.Time) budgetResult {
	type ref struct{ mi, si int }
	var refs []ref
	for mi := range monitors {
		for si := range monitors[mi].Sessions {
			refs = append(refs, ref{mi, si})
		}
	}
	var res budgetResult
	if len(refs) == 0 {
		return res
	}
	sort.SliceStable(refs, func(a, b int) bool {
		sa := &monitors[refs[a].mi].Sessions[refs[a].si]
		sb := &monitors[refs[b].mi].Sessions[refs[b].si]
		ra, rb := 1, 1
		if sa.Status != common.StatusIdle {
			ra = 0
		}
		if sb.Status != common.StatusIdle {
			rb = 0
		}
		if ra != rb {
			return ra < rb
		}
		return sa.LastActivity.Time().After(sb.LastActivity.Time())
	})

	drop := make(map[ref]bool)
	used := envelopeReserveBytes
	placed := 0
	for _, rf := range refs {
		snap := &monitors[rf.mi]
		sess := &snap.Sessions[rf.si]
		if sess.SessionID == "" {
			used += jsonSize(*sess) + 1 // 无 ID 的会话没有游标可管，照常放行
			continue
		}
		key := cursorKey(snap.Type, sess.SessionID)
		msgs := sess.RecentMessages

		if until, ok := r.quarantine[key]; ok {
			if now.Before(until) {
				drop[rf] = true
				res.quarantined++
				r.discardStaged(key)
				continue
			}
			delete(r.quarantine, key)
		}

		forced := placed == 0 // 本 body 还是空的：必须放进一个会话，否则永远没有进度
		fixed := sessionBaseBytes(*sess)
		if len(sess.ActivityDeltas) > 0 {
			fixed += jsonSize(sess.ActivityDeltas)
		}
		room := budget - used - fixed
		if !forced && room < 0 {
			drop[rf] = true
			res.deferredSessions++
			res.deferredMessages += len(msgs)
			r.discardStaged(key)
			continue
		}

		// 按消息前缀装：sizes[i] 是第 i 条的 JSON 字节（含逗号）。
		sizes := make([]int, 0, len(msgs))
		msgBytes := 0
		for k := range msgs {
			first := forced && k == 0 // 强制放入的首条无视预算
			if !first && messageValueBytes(&msgs[k]) > room-msgBytes {
				break
			}
			b := jsonSize(msgs[k]) + 1
			if !first && b > room-msgBytes {
				break
			}
			sizes = append(sizes, b)
			msgBytes += b
		}
		k := len(sizes)
		if len(msgs) > 0 && k == 0 {
			// 有消息但连第一条都放不下：整会话推迟。会话标量字段随消息一起走，下个 tick 再发。
			drop[rf] = true
			res.deferredSessions++
			res.deferredMessages += len(msgs)
			r.discardStaged(key)
			continue
		}
		if k > 0 && k < len(msgs) {
			nk := safeCut(msgs, k)
			for len(sizes) < nk {
				sizes = append(sizes, jsonSize(msgs[len(sizes)])+1)
			}
			sizes = sizes[:nk]
			k = nk
			msgBytes = 0
			for _, s := range sizes {
				msgBytes += s
			}
		}
		if k < len(msgs) {
			res.deferredMessages += len(msgs) - k
			sess.RecentMessages = msgs[:k:k]
			last := msgs[k-1]
			p := r.pending[key]
			p.LastMsgID = last.ExternalMessageID
			p.LastMsgTime = last.Timestamp.Time()
			if r.pending == nil {
				r.pending = make(map[string]MsgCursor, 4)
			}
			r.pending[key] = p
		}
		used += fixed + msgBytes
		placed++
		res.keptSessions++
		res.keptMessages += k
		if res.firstKey == "" {
			res.firstKey = key
		}
	}
	res.usedBytes = used
	res.minimal = res.keptSessions == 1 && res.keptMessages <= 1

	if len(drop) > 0 {
		for mi := range monitors {
			snap := &monitors[mi]
			kept := snap.Sessions[:0]
			for si := range snap.Sessions {
				if !drop[ref{mi, si}] {
					kept = append(kept, snap.Sessions[si])
				}
			}
			for i := len(kept); i < len(snap.Sessions); i++ {
				snap.Sessions[i] = monitor.Session{}
			}
			snap.Sessions = kept
		}
	}
	return res
}

// discardStaged 撤销某会话在本 tick 暂存的游标与指纹：它没有进 body，就不能被当作"已发送"。
func (r *Reporter) discardStaged(key string) {
	delete(r.pending, key)
	delete(r.pendingSent, key)
}

// handleTooLarge 处理 HTTP 413 / 业务码 41301：重发同一个包永远不会成功，必须让下一个 body 变小。
//   - 一般情况：预算收缩到"刚失败的 body 实际大小"的一半；
//   - body 已经只剩一个会话、≤1 条消息（预算再小也发不动）：把这个会话搁置 quarantineTTL，
//     免得它每个 tick 都排在最前面（首个入选会话强制放行）、把其他会话全部饿死。不丢数据：游标没动，到期重试。
func (r *Reporter) handleTooLarge(res budgetResult, now time.Time) {
	if res.minimal && res.firstKey != "" {
		if r.quarantine == nil {
			r.quarantine = make(map[string]time.Time)
		}
		r.quarantine[res.firstKey] = now.Add(quarantineTTL)
		logger.Infof("payload-too-large on a minimal body (1 session, <=1 message): quarantine %s for %s, other sessions continue (cursor not advanced)",
			res.firstKey, quarantineTTL)
		return
	}
	r.budget.onTooLarge(res.usedBytes)
}

// localCommandNoisePrefixes 与服务端 LocalCommandNoise 的前缀清单保持一致：服务端在解析 content_parts 之前，
// 用消息的 text 字段丢弃这类"从未到达模型的本地命令包装"user 行。
var localCommandNoisePrefixes = []string{
	"<local-command-", "<command-name>", "<command-message>", "<command-args>", "Unknown command:",
}

// stripRedundantText 有 content_parts 的消息，text 只是 parts 的扁平化（FlattenParts），
// 服务端入库时按 parts 自行重新扁平化（MessageContentIngestService.prepare 只在 parts 为空时才读 text），
// 两份一起发等于把每条消息的正文重复发送一遍。这里在 body 里省掉这份重复。
//
// 唯一例外：role=user 且 text 命中服务端本地命令噪声前缀的消息——服务端的 LocalCommandNoise 过滤读的
// 是 text，省掉会让这些噪声被入库。该清单需与服务端保持同步；漏同步的后果只是这类噪声行多入库、不丢数据。
//
// 拷贝消息切片再改：provider 可能缓存并复用它返回的 RecentMessages 底层数组，原地清空 Text 会污染缓存。
func stripRedundantText(monitors []monitor.Snapshot) {
	for mi := range monitors {
		sessions := monitors[mi].Sessions
		for si := range sessions {
			msgs := sessions[si].RecentMessages
			var out []monitor.Message
			for i := range msgs {
				m := msgs[i]
				if len(m.ContentParts) == 0 || m.Text == "" || keepTextForServerFilter(m.Role, m.Text) {
					continue
				}
				if out == nil {
					out = make([]monitor.Message, len(msgs))
					copy(out, msgs)
				}
				out[i].Text = ""
			}
			if out != nil {
				sessions[si].RecentMessages = out
			}
		}
	}
}

func keepTextForServerFilter(role, text string) bool {
	if !strings.EqualFold(role, "user") {
		return false
	}
	for _, p := range localCommandNoisePrefixes {
		if strings.HasPrefix(text, p) {
			return true
		}
	}
	return false
}
