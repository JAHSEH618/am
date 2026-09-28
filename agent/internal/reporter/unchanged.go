// unchanged.go：本 tick 与上次成功上报相比毫无变化的空闲会话不再重复上报。
//
// <p>背景：provider 每个 tick 都返回 lookback（48h）窗口内的全部会话，游标只切掉了已发过的消息与增量，
// 会话本身仍逐个进 body。服务端对每个会话都要开一个事务、跑 5~7 条查询（按会话读消息计数、
// 事件 source_ref 等），重度用户窗口内几十个会话 × 8s 活跃节奏 × 全员，是 /report 打满连接池的
// 主要负载；而其中绝大多数是早已 idle、一个字段都没变的会话。
//
// <p>规则（{@link Reporter.dropUnchangedIdleSessions}）：同时满足以下条件的会话从本 tick body 中剔除——
//   - 游标切片后没有待发消息、没有待发 activity_delta；
//   - status == idle（非 idle 会话决定服务端回的 active 信号与 work_session 活跃秒数，必须每 tick 带上）；
//   - 会话标量指纹与上次**成功**上报时一致；
//   - 距上次成功上报它不足 unchangedResyncInterval（到点强制重发一次，给服务端自愈的机会）。
//
// <p>指纹与游标同一套"成功才提交"语义：失败 tick 暂存的指纹直接丢弃，下个 tick 重新比对。
// 进程重启后指纹表为空，首个 tick 全量上报，与旧行为一致。
//
// gz
package reporter

import (
	"hash/fnv"
	"strconv"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"
)

// unchangedResyncInterval 未变化的空闲会话最长多久强制重发一次。
const unchangedResyncInterval = 15 * time.Minute

// sentSession 记录某会话最近一次成功上报时的指纹与时刻。
type sentSession struct {
	fingerprint uint64
	sentAt      time.Time
}

// sessionFingerprint 覆盖服务端 upsert 会写入 ai_session 的全部会话级字段；
// 消息 / 增量不在其中——它们由游标切片单独判定"有没有新东西"。
func sessionFingerprint(s *monitor.Session) uint64 {
	h := fnv.New64a()
	w := func(v string) {
		_, _ = h.Write([]byte(v))
		_, _ = h.Write([]byte{0})
	}
	wi := func(v int64) { w(strconv.FormatInt(v, 10)) }
	w(s.Status)
	w(s.CurrentTool)
	w(s.Cwd)
	w(s.CwdHash)
	w(s.GitBranch)
	w(s.RepoURL)
	w(s.ProjectName)
	w(strconv.FormatBool(s.IsWorktree))
	w(s.MainRepo)
	w(s.Model)
	wi(s.StartedAt.Time().Unix())
	wi(s.LastActivity.Time().Unix())
	wi(int64(s.UserMessages))
	wi(int64(s.AssistantMessages))
	wi(int64(s.SnapshotMessageCount))
	wi(s.InputTokens)
	wi(s.OutputTokens)
	wi(s.CacheCreateTokens)
	wi(s.CacheReadTokens)
	wi(int64(len(s.RecentTools)))
	if n := len(s.RecentTools); n > 0 {
		last := s.RecentTools[n-1]
		w(last.Name)
		wi(last.Timestamp.Time().Unix())
	}
	return h.Sum64()
}

// dropUnchangedIdleSessions 须在 applyMsgCursors 之后调用（依赖切片后的 RecentMessages / ActivityDeltas）。
// 原地改写 monitors[i].Sessions，把本 tick 实际上报的会话指纹暂存到 r.pendingSent，返回被剔除的会话数。
func (r *Reporter) dropUnchangedIdleSessions(monitors []monitor.Snapshot, now time.Time) (dropped int) {
	r.pendingSent = make(map[string]sentSession, 16)
	r.seenSessions = make(map[string]struct{}, 16)
	for i := range monitors {
		snap := &monitors[i]
		kept := snap.Sessions[:0]
		for j := range snap.Sessions {
			sess := snap.Sessions[j]
			if sess.SessionID == "" {
				kept = append(kept, sess)
				continue
			}
			key := cursorKey(snap.Type, sess.SessionID)
			r.seenSessions[key] = struct{}{}
			fp := sessionFingerprint(&sess)
			prev, ok := r.sent[key]
			if ok &&
				len(sess.RecentMessages) == 0 &&
				len(sess.ActivityDeltas) == 0 &&
				sess.Status == common.StatusIdle &&
				prev.fingerprint == fp &&
				now.Sub(prev.sentAt) < unchangedResyncInterval {
				dropped++
				continue
			}
			r.pendingSent[key] = sentSession{fingerprint: fp, sentAt: now}
			kept = append(kept, sess)
		}
		// 清掉尾部残留引用，让被剔除会话的消息切片能被 GC。
		for k := len(kept); k < len(snap.Sessions); k++ {
			snap.Sessions[k] = monitor.Session{}
		}
		snap.Sessions = kept
	}
	return dropped
}

// commitSentSessions 在上报成功后提交本 tick 的指纹，并淘汰本 tick 已不在窗口内的会话条目。
func (r *Reporter) commitSentSessions() {
	if r.sent == nil {
		r.sent = make(map[string]sentSession, len(r.pendingSent))
	}
	for k, v := range r.pendingSent {
		r.sent[k] = v
	}
	if r.seenSessions != nil {
		for k := range r.sent {
			if _, ok := r.seenSessions[k]; !ok {
				delete(r.sent, k)
			}
		}
	}
	r.pendingSent = nil
	r.seenSessions = nil
}
