package cursor

import (
	"context"
	"database/sql"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

// mustRealCursorDB 用 Cursor state.vscdb 的真实 DDL（key 上是 UNIQUE → BINARY autoindex）。
func mustRealCursorDB(t *testing.T, kv map[string]string) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "state.vscdb"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if _, err := db.Exec("CREATE TABLE cursorDiskKV (key TEXT UNIQUE ON CONFLICT REPLACE, value BLOB)"); err != nil {
		t.Fatalf("ddl: %v", err)
	}
	for k, v := range kv {
		if _, err := db.Exec("INSERT INTO cursorDiskKV(key,value) VALUES(?,?)", k, v); err != nil {
			t.Fatalf("insert %q: %v", k, err)
		}
	}
	return db
}

// 与改写前的 LIKE 查询逐行比对：同一批键上，区间查询取回的行必须完全相同。
func TestBubbleKeyRange_MatchesLegacyLike(t *testing.T) {
	const sidC = "cccccccc-cccc-cccc-cccc-cccccccccccc"
	kv := map[string]string{
		"bubbleId:" + sidA + ":b1":              `{"createdAt":"2026-06-29T11:00:00.000Z"}`,
		"bubbleId:" + sidA + ":b2":              `{"createdAt":"2026-06-29T11:30:00.000Z"}`,
		"bubbleId:" + sidA + ":":                `{"createdAt":"2026-06-29T10:00:00.000Z"}`, // 空 bubbleId：两种写法都命中
		"bubbleId:" + sidB + ":b1":              `{"createdAt":"2026-06-29T11:45:00.000Z"}`,
		"bubbleId:" + sidC + ":b1":              `{"createdAt":"2026-06-01T00:00:00.000Z"}`, // cutoff 之前
		"bubbleId:" + sidA:                      `{"createdAt":"2026-06-29T11:59:00.000Z"}`, // 缺 ':'，不是 sidA 的 bubble
		"bubbleId:" + sidA + ";x":               `{"createdAt":"2026-06-29T11:59:00.000Z"}`, // 区间上界之外
		"bubbleIdX" + sidA + ":b9":              `{"createdAt":"2026-06-29T11:59:00.000Z"}`,
		"composerData:" + sidA:                  `{"fullConversationHeadersOnly":[]}`,
		"checkpointId:" + sidA + ":c1":          `{"createdAt":"2026-06-29T11:59:00.000Z"}`,
		"messageRequestContext:" + sidA + ":b1": `{}`,
	}
	db := mustRealCursorDB(t, kv)

	legacyBubbles := func(sid string) map[string]string {
		rows, err := db.Query("SELECT key, value FROM cursorDiskKV WHERE key LIKE ?", "bubbleId:"+sid+":%")
		if err != nil {
			t.Fatalf("legacy query: %v", err)
		}
		defer rows.Close()
		out := map[string]string{}
		for rows.Next() {
			var k, v string
			if err := rows.Scan(&k, &v); err != nil {
				t.Fatalf("scan: %v", err)
			}
			out[strings.TrimPrefix(k, "bubbleId:"+sid+":")] = v
		}
		return out
	}

	for _, tc := range []struct {
		name string
		sid  string
		want int
	}{
		{"three bubbles incl. empty id", sidA, 3},
		{"single bubble", sidB, 1},
		{"old session still fetched", sidC, 1},
		{"unknown session", "dddddddd-dddd-dddd-dddd-dddddddddddd", 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := fetchBubbles(db, tc.sid)
			if len(got) != tc.want {
				t.Fatalf("fetchBubbles(%s) = %d rows, want %d: %v", tc.sid, len(got), tc.want, got)
			}
			if legacy := legacyBubbles(tc.sid); !reflect.DeepEqual(got, legacy) {
				t.Fatalf("fetchBubbles(%s) differs from legacy LIKE:\n got=%v\nwant=%v", tc.sid, got, legacy)
			}
		})
	}

	t.Run("recent sessions", func(t *testing.T) {
		cutoff := time.Date(2026, 6, 28, 0, 0, 0, 0, time.UTC)
		refs, err := queryRecentSessions(context.Background(), db, cutoff)
		if err != nil {
			t.Fatalf("queryRecentSessions: %v", err)
		}
		legacy := strings.Replace(recentSessionsSQL,
			"key >= '"+bubbleKeyLo+"' AND key < '"+bubbleKeyHi+"'", "key LIKE 'bubbleId:%'", 1)
		if legacy == recentSessionsSQL {
			t.Fatal("failed to derive legacy LIKE query")
		}
		rows, err := db.Query(legacy, cutoff.UTC().Format("2006-01-02T15:04:05.000Z"))
		if err != nil {
			t.Fatalf("legacy query: %v", err)
		}
		defer rows.Close()
		want := map[string]time.Time{}
		for rows.Next() {
			var sid, at string
			if err := rows.Scan(&sid, &at); err != nil {
				t.Fatalf("scan: %v", err)
			}
			want[sid] = parseISO(at)
		}
		got := refsBySid(refs)
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("queryRecentSessions differs from legacy LIKE:\n got=%v\nwant=%v", got, want)
		}
		// 整表查询两种写法都收所有 "bubbleId:" 开头的键（含上面缺 ':' / ';x' 两行，substr 仍截出 sidA），
		// 故 sidA 取 11:59；sidC 在 cutoff 之前被丢掉。
		if len(got) != 2 || !got[sidA].Equal(parseISO("2026-06-29T11:59:00.000Z")) {
			t.Fatalf("unexpected refs: %v", got)
		}
	})
}

// 查询计划守卫：取 bubble 的两条查询必须走 key 索引而不是 SCAN 整表；增量查询必须仍由 rowid 驱动。
func TestCursorQueryPlans(t *testing.T) {
	db := mustRealCursorDB(t, map[string]string{
		"bubbleId:" + sidA + ":b1": `{"createdAt":"2026-06-29T11:00:00.000Z"}`,
	})
	lo, hi := sessionBubbleKeyRange(sidA)
	for _, tc := range []struct {
		name string
		sql  string
		args []any
		want string
	}{
		{"fetchBubbles", fetchBubblesSQL, []any{lo, hi}, "USING INDEX sqlite_autoindex_cursorDiskKV_1"},
		{"recentSessions", recentSessionsSQL, []any{"2026-06-28T00:00:00.000Z"}, "USING INDEX sqlite_autoindex_cursorDiskKV_1"},
		{"bubblesAfter", bubblesAfterSQL, []any{int64(0)}, "USING INTEGER PRIMARY KEY"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			rows, err := db.Query("EXPLAIN QUERY PLAN "+tc.sql, tc.args...)
			if err != nil {
				t.Fatalf("explain: %v", err)
			}
			defer rows.Close()
			var plan []string
			for rows.Next() {
				var id, parent, notused int
				var detail string
				if err := rows.Scan(&id, &parent, &notused, &detail); err != nil {
					t.Fatalf("scan: %v", err)
				}
				plan = append(plan, detail)
			}
			joined := strings.Join(plan, " | ")
			if !strings.Contains(joined, tc.want) || strings.Contains(joined, "SCAN cursorDiskKV") {
				t.Fatalf("plan = %q, want %q and no full SCAN", joined, tc.want)
			}
		})
	}
}
