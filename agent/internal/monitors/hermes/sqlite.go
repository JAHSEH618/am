// Hermes 的 SQLite 查询：sessions 主表 + messages 聚合 + tail messages，全部只读。
// gz
package hermes

import (
	"database/sql"
	"strings"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"

	_ "modernc.org/sqlite"
)

const (
	// maxRecentMessages 单 session 单次 Snapshot 从 hermes sqlite 拉的消息上限。
	//
	// v2.7 起放大到 1000：reporter 端基于游标做"自上次以来增量"切片，本地拉够多就不会
	// 因 LIMIT 截断在 client 入口处把消息丢掉。1000 足以覆盖 hermes 单会话单次活跃峰值，
	// 同时避免一次 SQL 拉几万行造成的 sqlite 锁延迟。
	maxRecentMessages = 1000
	maxRecentTools    = 10
)

func openHermesDBRO(path string) (*sql.DB, error) {
	// _txlock=immediate + mode=ro 避免与 hermes 进程竞争 WAL 写入；nolock=1 在我们只读一个 snapshot 时是安全的。
	dsn := path + "?mode=ro&_pragma=busy_timeout(2000)"
	return sql.Open("sqlite", dsn)
}

// readDataVersionFast 用 Provider 持久化的 RO 连接读 PRAGMA data_version。
//
// 与 cursor.Provider.readDataVersionFast 完全同款：首次 lazy open，后续仅做 PRAGMA 查询，
// 把 fast path 的入口判断成本从单次"~50-200ms open"降到"~1-3ms PRAGMA"。失败返回 0
// 退化到全扫描兜底，并把陈旧连接置 nil 由下次 Snapshot 重建。
func (p *Provider) readDataVersionFast(path string) int64 {
	p.pragmaMu.Lock()
	defer p.pragmaMu.Unlock()
	if p.pragmaDB == nil {
		db, err := openHermesDBRO(path)
		if err != nil {
			return 0
		}
		db.SetMaxOpenConns(1)
		db.SetMaxIdleConns(1)
		db.SetConnMaxIdleTime(0)
		p.pragmaDB = db
	}
	var dv int64
	if err := p.pragmaDB.QueryRow("PRAGMA data_version").Scan(&dv); err != nil {
		_ = p.pragmaDB.Close()
		p.pragmaDB = nil
		return 0
	}
	return dv
}

// querySessions 把 (cutoff, now] 内有活动的 hermes session 全部读出，并附上消息聚合 + tail。
func querySessions(db *sql.DB, cutoff time.Time) ([]*parsedSession, error) {
	cutoffEpoch := float64(cutoff.Unix())
	rows, err := db.Query(`
		WITH last_msg AS (
			SELECT session_id, MAX(timestamp) AS ts FROM messages GROUP BY session_id
		)
		SELECT s.id, s.source, IFNULL(s.user_id,''), IFNULL(s.model,''),
		       IFNULL(s.title,''),
		       s.started_at, IFNULL(s.ended_at, 0),
		       s.input_tokens, s.output_tokens,
		       s.cache_read_tokens, s.cache_write_tokens, s.reasoning_tokens,
		       s.tool_call_count,
		       lm.ts AS last_msg_ts
		FROM sessions s
		LEFT JOIN last_msg lm ON lm.session_id = s.id
		WHERE COALESCE(lm.ts, s.ended_at, s.started_at) >= ?
		ORDER BY last_msg_ts DESC
	`, cutoffEpoch)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]*parsedSession, 0, 32)
	for rows.Next() {
		var (
			id, source, userID, model, title         string
			started, ended                           float64
			input, output, cacheR, cacheW, reasoning int64
			toolCalls                                int
			lastTS                                   sql.NullFloat64
		)
		if err := rows.Scan(&id, &source, &userID, &model, &title,
			&started, &ended,
			&input, &output, &cacheR, &cacheW, &reasoning,
			&toolCalls, &lastTS); err != nil {
			continue
		}
		ps := &parsedSession{
			SessionID:       id,
			Source:          source,
			UserID:          userID,
			Model:           model,
			Title:           title,
			StartedAt:       fromEpoch(started),
			EndedAt:         fromEpoch(ended),
			InputTokens:     input,
			OutputTokens:    output,
			CacheRead:       cacheR,
			CacheCreate:     cacheW,
			ReasoningTokens: reasoning,
			ToolCallCount:   toolCalls,
		}
		if lastTS.Valid {
			ps.LastActivity = fromEpoch(lastTS.Float64)
		} else if !ps.EndedAt.IsZero() {
			ps.LastActivity = ps.EndedAt
		} else {
			ps.LastActivity = ps.StartedAt
		}
		out = append(out, ps)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(out) == 0 {
		return out, nil
	}

	// 先把 user/assistant 消息计数批量查出来，避免每个 session 一次往返。
	if err := fillCounts(db, out); err != nil {
		return out, err
	}

	// 每个 session 取最近 N 条消息 + 工具调用，逐个查询（hermes 默认 sessions 数量较少）。
	for _, ps := range out {
		fillRecent(db, ps)
	}
	return out, nil
}

func fillCounts(db *sql.DB, sessions []*parsedSession) error {
	if len(sessions) == 0 {
		return nil
	}
	// 每个已筛 session 绑一个 host variable 进 IN(...)。hermes 数据集很小（sessions 数远小于
	// SQLite 的 32766 变量上限，bootstrap 拉到 30d 也是），故不做分批；万一超限 Query 报错，
	// querySessions 会把错误上抛、Snapshot 退空快照，不会崩。
	idIndex := make(map[string]int, len(sessions))
	placeholders := make([]string, len(sessions))
	args := make([]any, len(sessions))
	for i, ps := range sessions {
		idIndex[ps.SessionID] = i
		placeholders[i] = "?"
		args[i] = ps.SessionID
	}
	query := `SELECT session_id, role, COUNT(*) FROM messages WHERE session_id IN (` +
		strings.Join(placeholders, ",") + `) GROUP BY session_id, role`
	rows, err := db.Query(query, args...)
	if err != nil {
		return err
	}
	defer func() { _ = rows.Close() }()
	for rows.Next() {
		var sid, role string
		var n int
		if err := rows.Scan(&sid, &role, &n); err != nil {
			continue
		}
		idx, ok := idIndex[sid]
		if !ok {
			continue
		}
		switch role {
		case "user":
			sessions[idx].UserMessages = n
		case "assistant":
			sessions[idx].AssistantMessages = n
		}
	}
	return rows.Err()
}

func fillRecent(db *sql.DB, ps *parsedSession) {
	rows, err := db.Query(`
		SELECT IFNULL(id,''), role, IFNULL(content,''), IFNULL(tool_name,''), timestamp,
		       IFNULL(token_count, 0)
		FROM messages
		WHERE session_id = ?
		ORDER BY timestamp DESC
		LIMIT ?
	`, ps.SessionID, maxRecentMessages)
	if err != nil {
		return
	}
	defer func() { _ = rows.Close() }()

	type row struct {
		id       string
		role     string
		content  string
		toolName string
		ts       float64
		tokens   int64
	}
	var fetched []row
	for rows.Next() {
		var r row
		if err := rows.Scan(&r.id, &r.role, &r.content, &r.toolName, &r.ts, &r.tokens); err != nil {
			continue
		}
		fetched = append(fetched, r)
	}
	// fetched 当前是 DESC（最新在前），翻转成时间序输出。
	tools := make([]monitor.Tool, 0, maxRecentTools)
	msgs := make([]monitor.Message, 0, maxRecentMessages)
	for i := len(fetched) - 1; i >= 0; i-- {
		r := fetched[i]
		eventTime := fromEpoch(r.ts)
		ts := monitor.LocalTime(eventTime)
		ref := r.id
		if ref == "" {
			ref = common.SyntheticMessageIDByTime(ps.SessionID, eventTime, r.role, r.content)
		}
		switch r.role {
		case "tool":
			tools = append(tools, monitor.Tool{
				Name:      common.NormalizeToolName(r.toolName),
				Timestamp: ts,
			})
			parts := buildMessageParts("tool", r.content, r.toolName)
			if len(parts) > 0 {
				msg := monitor.Message{
					ExternalMessageID: ref,
					Role:              "tool",
					ContentParts:      parts,
					ToolName:          common.NormalizeToolName(r.toolName),
					Timestamp:         ts,
				}
				monitor.FinalizeMessage(&msg)
				msgs = append(msgs, msg)
			}
		case "user":
			text := strings.TrimSpace(r.content)
			parts := buildMessageParts(r.role, text, r.toolName)
			if len(parts) == 0 {
				continue
			}
			inTok := int(r.tokens)
			msg := monitor.Message{
				ExternalMessageID: ref,
				Role:              r.role,
				ContentParts:      parts,
				Timestamp:         ts,
				InputTokens:       inTok,
			}
			monitor.FinalizeMessage(&msg)
			msgs = append(msgs, msg)
			common.AppendActivityDelta(&ps.ActivityDeltas, eventTime, ref+":user",
				"hermes_message", int64(inTok), 0, 1)
		case "assistant":
			text := strings.TrimSpace(r.content)
			parts := buildMessageParts(r.role, text, r.toolName)
			if len(parts) == 0 {
				continue
			}
			outTok := int(r.tokens)
			msg := monitor.Message{
				ExternalMessageID: ref,
				Role:              r.role,
				ContentParts:      parts,
				Timestamp:         ts,
				OutputTokens:      outTok,
			}
			monitor.FinalizeMessage(&msg)
			msgs = append(msgs, msg)
			common.AppendActivityDelta(&ps.ActivityDeltas, eventTime, ref,
				"hermes_message", 0, int64(outTok), 1)
		}
	}
	if len(tools) > maxRecentTools {
		tools = tools[len(tools)-maxRecentTools:]
	}
	if len(msgs) > maxRecentMessages {
		msgs = msgs[len(msgs)-maxRecentMessages:]
	}
	ps.RecentTools = tools
	ps.RecentMessages = msgs
	if n := len(tools); n > 0 {
		ps.CurrentTool = tools[n-1].Name
	}
}

func fromEpoch(seconds float64) time.Time {
	if seconds <= 0 {
		return time.Time{}
	}
	sec := int64(seconds)
	nsec := int64((seconds - float64(sec)) * 1e9)
	return time.Unix(sec, nsec)
}
