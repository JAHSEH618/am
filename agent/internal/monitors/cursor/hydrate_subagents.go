// 父 composer 引用的 Task 子 composer 可能不在 lookback 扫描结果里，按需补解析。
//
// gz
package cursor

import (
	"database/sql"
	"strings"
	"time"
)

func hydrateMissingSubagents(db *sql.DB, sessions []*parsedSession, workspaceCtx map[string]WorkspaceContext) []*parsedSession {
	if db == nil || len(sessions) == 0 {
		return sessions
	}
	byID := make(map[string]*parsedSession, len(sessions))
	for _, ps := range sessions {
		if ps != nil {
			byID[ps.SessionID] = ps
		}
	}
	var extra []*parsedSession
	for _, ps := range sessions {
		if ps == nil || ps.ParentComposerID != "" {
			continue
		}
		for _, cid := range ps.SubagentComposerIDs {
			cid = strings.TrimSpace(cid)
			if cid == "" || byID[cid] != nil {
				continue
			}
			child := buildParsedSession(db, cid, time.Time{}, workspaceCtx)
			if child == nil {
				continue
			}
			byID[cid] = child
			extra = append(extra, child)
		}
	}
	if len(extra) == 0 {
		return sessions
	}
	return append(sessions, extra...)
}
