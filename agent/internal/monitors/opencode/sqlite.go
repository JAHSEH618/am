// OpenCode 的 SQLite 查询：session 主表 + message/part 聚合，全部只读。
// gz
package opencode

import (
	"database/sql"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"

	_ "modernc.org/sqlite"
)

// openDBRO 只读打开 opencode.db。WAL 模式由 SQLite 自行读取；mode=ro 不竞争 opencode 进程的写入
// （与 cursor.openStateDBRO / hermes.openHermesDBRO 同款）。
func openDBRO(path string) (*sql.DB, error) {
	return sql.Open("sqlite", path+"?mode=ro&_pragma=busy_timeout(2000)")
}

// readDataVersionFast 用 Provider 持久化的 RO 连接读 PRAGMA data_version（与 hermes 同款 fast path）。
// opencode 未写库时直接复用上次解析结果，省掉 session+message+part 三表扫描。失败返回 0 退化全扫描。
func (p *Provider) readDataVersionFast(path string) int64 {
	p.pragmaMu.Lock()
	defer p.pragmaMu.Unlock()
	if p.pragmaDB == nil {
		db, err := openDBRO(path)
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

// querySessions 读出 (cutoff, now] 内有更新、且未归档的 session，并填充消息聚合。
//
// 会话级记忆化：time_updated 未变的 session 复用 p.scanCache 里上次 fillRecent 装配好的结果，
// 跳过本轮的 message/part 重读；time_updated 前进（或为 NULL/0，无法判定）的 session 照常重读。
func (p *Provider) querySessions(db *sql.DB, cutoff time.Time) ([]*parsedSession, error) {
	cutoffMs := cutoff.UnixMilli()
	rows, err := db.Query(`
		SELECT id, IFNULL(directory,''), IFNULL(title,''), IFNULL(version,''), IFNULL(model,''),
		       IFNULL(tokens_input,0), IFNULL(tokens_output,0), IFNULL(tokens_reasoning,0),
		       IFNULL(tokens_cache_read,0), IFNULL(tokens_cache_write,0),
		       IFNULL(time_created,0), IFNULL(time_updated,0)
		FROM session
		WHERE IFNULL(time_archived,0)=0
		  AND IFNULL(time_updated, time_created) >= ?
		ORDER BY IFNULL(time_updated,0) DESC
	`, cutoffMs)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	out := make([]*parsedSession, 0, 16)
	updatedMsOf := make([]int64, 0, 16)
	for rows.Next() {
		var (
			id, dir, title, version, model       string
			tIn, tOut, tReason, tCacheR, tCacheW int64
			createdMs, updatedMs                 int64
		)
		if err := rows.Scan(&id, &dir, &title, &version, &model,
			&tIn, &tOut, &tReason, &tCacheR, &tCacheW,
			&createdMs, &updatedMs); err != nil {
			continue
		}
		ps := &parsedSession{
			SessionID:       id,
			Directory:       dir,
			Title:           title,
			Version:         version,
			Model:           parseModel(model),
			InputTokens:     tIn,
			OutputTokens:    tOut,
			ReasoningTokens: tReason,
			CacheRead:       tCacheR,
			CacheCreate:     tCacheW,
			StartedAt:       fromEpochMs(createdMs),
			LastActivity:    fromEpochMs(updatedMs),
		}
		if ps.LastActivity.IsZero() {
			ps.LastActivity = ps.StartedAt
		}
		out = append(out, ps)
		updatedMsOf = append(updatedMsOf, updatedMs)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	seen := make(map[string]struct{}, len(out))
	for i, ps := range out {
		id := ps.SessionID
		seen[id] = struct{}{}
		updatedMs := updatedMsOf[i]

		if updatedMs > 0 {
			p.scanMu.Lock()
			ent, ok := p.scanCache[id]
			p.scanMu.Unlock()
			if ok && ent.updatedMs == updatedMs && ent.ps != nil {
				out[i] = ent.ps
				continue
			}
		}

		fillRecent(db, ps)
		if updatedMs > 0 {
			p.scanMu.Lock()
			p.scanCache[id] = sessionCacheEntry{updatedMs: updatedMs, ps: ps}
			p.scanMu.Unlock()
		}
	}

	p.scanMu.Lock()
	for id := range p.scanCache {
		if _, ok := seen[id]; !ok {
			delete(p.scanCache, id)
		}
	}
	p.scanMu.Unlock()

	return out, nil
}

// fillRecent 把单 session 的 message/part 装配成 RecentMessages / RecentTools / ActivityDeltas，
// 并统计 user / assistant 消息数。
func fillRecent(db *sql.DB, ps *parsedSession) {
	partsByMsg := loadParts(db, ps.SessionID)

	rows, err := db.Query(`
		SELECT id, IFNULL(time_created,0), data
		FROM message
		WHERE session_id=?
		ORDER BY IFNULL(time_created,0), id
	`, ps.SessionID)
	if err != nil {
		return
	}
	defer func() { _ = rows.Close() }()

	msgs := make([]monitor.Message, 0, 64)
	tools := make([]monitor.Tool, 0, maxRecentTools)
	for rows.Next() {
		var (
			id        string
			createdMs int64
			rawData   []byte
		)
		if err := rows.Scan(&id, &createdMs, &rawData); err != nil {
			continue
		}
		md := parseMessageData(rawData)
		eventTime := fromEpochMs(createdMs)
		if eventTime.IsZero() {
			eventTime = fromEpochMs(md.Time.Created)
		}
		ts := monitor.LocalTime(eventTime)

		parts, toolNames := buildParts(partsByMsg[id])
		for _, tn := range toolNames {
			tools = append(tools, monitor.Tool{Name: tn, Timestamp: ts})
		}

		switch md.Role {
		case "user":
			ps.UserMessages++
			if len(parts) > 0 {
				msg := monitor.Message{ExternalMessageID: id, Role: "user", ContentParts: parts, Timestamp: ts}
				monitor.FinalizeMessage(&msg)
				msgs = append(msgs, msg)
			}
			common.AppendActivityDelta(&ps.ActivityDeltas, eventTime, id, common.ActivitySourceUserTurn, 0, 0, 1)
		case "assistant":
			ps.AssistantMessages++
			if ps.Model == "" && md.ModelID != "" {
				ps.Model = md.ModelID
			}
			if len(parts) > 0 {
				msg := monitor.Message{ExternalMessageID: id, Role: "assistant", ContentParts: parts, Timestamp: ts}
				monitor.FinalizeMessage(&msg)
				msgs = append(msgs, msg)
			}
			completed := fromEpochMs(md.Time.Completed)
			if completed.IsZero() {
				completed = eventTime
			}
			in := md.Tokens.Input + md.Tokens.Cache.Read + md.Tokens.Cache.Write
			out := md.Tokens.Output + md.Tokens.Reasoning
			common.AppendActivityDelta(&ps.ActivityDeltas, completed, id, common.ActivitySourceAssistantTurn, in, out, 1)
		}
	}

	if len(msgs) > maxRecentMessages {
		msgs = msgs[len(msgs)-maxRecentMessages:]
	}
	if len(tools) > maxRecentTools {
		tools = tools[len(tools)-maxRecentTools:]
	}
	ps.RecentMessages = msgs
	ps.RecentTools = tools
	if n := len(tools); n > 0 {
		ps.CurrentTool = tools[n-1].Name
	}
}

// loadParts 一次性读出某 session 全部 part，按 message_id 分组（保持 part 顺序）。
func loadParts(db *sql.DB, sessionID string) map[string][][]byte {
	rows, err := db.Query(`
		SELECT message_id, data
		FROM part
		WHERE session_id=?
		ORDER BY message_id, id
	`, sessionID)
	if err != nil {
		return nil
	}
	defer func() { _ = rows.Close() }()

	byMsg := make(map[string][][]byte)
	for rows.Next() {
		var (
			msgID   string
			rawData []byte
		)
		if err := rows.Scan(&msgID, &rawData); err != nil {
			continue
		}
		cp := make([]byte, len(rawData))
		copy(cp, rawData)
		byMsg[msgID] = append(byMsg[msgID], cp)
	}
	return byMsg
}

func fromEpochMs(ms int64) time.Time {
	if ms <= 0 {
		return time.Time{}
	}
	return time.UnixMilli(ms)
}
