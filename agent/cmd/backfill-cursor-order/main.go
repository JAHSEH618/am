// 从 Cursor state.vscdb 生成回填 conversation_order / message_time 的 SQL。
//
// 用法：
//
//	go run ./cmd/backfill-cursor-order \
//	  -cursor-db "$HOME/Library/Application Support/Cursor/User/globalStorage/state.vscdb" \
//	  -session-id 14 | docker exec -i mysql mysql -uroot -p99129 am
//
// gz
package main

import (
	"database/sql"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"strings"
	"time"

	cur "github.com/am/aiwatch-agent/internal/monitors/cursor"
	_ "modernc.org/sqlite"
)

const mergedSubagentOrderBase = 50000

func main() {
	cursorDB := flag.String("cursor-db", defaultCursorDB(), "Cursor state.vscdb path")
	aiSessionID := flag.Int64("ai-session-id", 0, "ai_session.id (required)")
	parentComposerID := flag.String("composer-id", "", "Cursor composer UUID (defaults: lookup not supported, pass explicitly)")
	childComposerIDs := flag.String("child-composers", "", "comma-separated subagent composer UUIDs merged into parent")
	flag.Parse()

	if *aiSessionID <= 0 {
		log.Fatal("ai-session-id is required")
	}
	if strings.TrimSpace(*parentComposerID) == "" {
		log.Fatal("composer-id is required (ai_session.external_session_id)")
	}

	sqliteDB, err := sql.Open("sqlite", *cursorDB)
	if err != nil {
		log.Fatal(err)
	}
	defer sqliteDB.Close()

	updates := walkComposer(sqliteDB, strings.TrimSpace(*parentComposerID))
	childIDs := splitCSV(*childComposerIDs)
	for i, cid := range childIDs {
		base := mergedSubagentOrderBase + i*10000
		for extID, p := range walkComposer(sqliteDB, cid) {
			p.order = base + p.order
			updates[extID] = p
		}
	}

	fmt.Println("START TRANSACTION;")
	for extID, p := range updates {
		if p.messageTime.IsZero() {
			continue
		}
		fmt.Printf(
			"UPDATE ai_session_message SET conversation_order=%d, message_time='%s' WHERE ai_session_id=%d AND external_message_id='%s';\n",
			p.order,
			p.messageTime.Format("2006-01-02 15:04:05"),
			*aiSessionID,
			escapeSQL(extID),
		)
	}
	fmt.Println("COMMIT;")
	fmt.Fprintf(os.Stderr, "-- generated %d updates for ai_session_id=%d\n", len(updates), *aiSessionID)
}

func splitCSV(s string) []string {
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func escapeSQL(s string) string {
	return strings.ReplaceAll(s, "'", "''")
}

func defaultCursorDB() string {
	home, _ := os.UserHomeDir()
	return home + "/Library/Application Support/Cursor/User/globalStorage/state.vscdb"
}

type msgPatch struct {
	order       int
	messageTime time.Time
}

type headerRow struct {
	BubbleID string `json:"bubbleId"`
}

func walkComposer(db *sql.DB, composerID string) map[string]msgPatch {
	out := make(map[string]msgPatch)
	var raw string
	if err := db.QueryRow(`SELECT value FROM cursorDiskKV WHERE key=?`, "composerData:"+composerID).Scan(&raw); err != nil {
		return out
	}
	var composer struct {
		Headers []headerRow `json:"fullConversationHeadersOnly"`
		CreatedAt json.RawMessage `json:"createdAt"`
	}
	if err := json.Unmarshal([]byte(raw), &composer); err != nil {
		return out
	}
	var lastTS time.Time
	sessionAnchor := cur.ParseComposerCreatedAtMs(composer.CreatedAt)
	for i, h := range composer.Headers {
		order := i + 1
		var bubbleRaw string
		key := fmt.Sprintf("bubbleId:%s:%s", composerID, h.BubbleID)
		if err := db.QueryRow(`SELECT value FROM cursorDiskKV WHERE key=?`, key).Scan(&bubbleRaw); err != nil {
			continue
		}
		var bubble struct {
			CreatedAt      string `json:"createdAt"`
			Type           int    `json:"type"`
			Thinking       struct{ Text string `json:"text"` } `json:"thinking"`
			ToolFormerData struct{ Name string `json:"name"` } `json:"toolFormerData"`
		}
		if err := json.Unmarshal([]byte(bubbleRaw), &bubble); err != nil {
			continue
		}
		ts := cur.ResolveHeaderMessageTime(sessionAnchor, order, parseISO(bubble.CreatedAt), &lastTS)
		extID := composerID + ":" + h.BubbleID
		out[extID] = msgPatch{order: order, messageTime: ts}
		if bubble.Type == 2 && bubble.ToolFormerData.Name == "" {
			if strings.TrimSpace(bubble.Thinking.Text) != "" {
				out[extID+":thinking"] = msgPatch{order: order, messageTime: ts}
			}
		}
	}
	return out
}

func parseISO(s string) time.Time {
	if s == "" {
		return time.Time{}
	}
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		t, _ = time.Parse("2006-01-02T15:04:05.000Z", s)
	}
	if t.IsZero() {
		return t
	}
	return t.In(time.Local)
}
