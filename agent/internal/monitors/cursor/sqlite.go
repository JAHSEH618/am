// SQLite 发现与查询逻辑衍生自 lazyagent（MIT）：cursorDiskKV + composerData / bubbleId 键模型。
// gz
package cursor

import (
	"database/sql"
	"os"
	"path/filepath"
	"runtime"
	"time"

	_ "modernc.org/sqlite"
)

// recentWindow 在 v2.8 之前是包级常量 48h；现已迁移到 Provider.lookback struct 字段，
// 方便 reporter 在 bootstrap 模式下临时切到 BootstrapLookback。读取入口在 Provider.Snapshot。

func stateDBPath() string {
	if p := os.Getenv("AM_CURSOR_DB"); p != "" {
		return p
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	switch runtime.GOOS {
	case "darwin":
		return filepath.Join(home, "Library", "Application Support", "Cursor", "User", "globalStorage", "state.vscdb")
	case "windows":
		ad := os.Getenv("APPDATA")
		if ad == "" {
			return ""
		}
		return filepath.Join(ad, "Cursor", "User", "globalStorage", "state.vscdb")
	default:
		return filepath.Join(home, ".config", "Cursor", "User", "globalStorage", "state.vscdb")
	}
}

func walStats(dbPath string) (time.Time, int64) {
	info, err := os.Stat(dbPath + "-wal")
	if err != nil {
		if info, err := os.Stat(dbPath); err == nil {
			return info.ModTime(), info.Size()
		}
		return time.Time{}, 0
	}
	return info.ModTime(), info.Size()
}

// sessionRef 是 queryRecentSessions 的输出元素：(会话 ID, 该会话最后一条 bubble 的时间)。
//
// <p>v2.8 起替代旧的"全量 queryComposers + 392 次 getExactBubbleTimestamp 点查"扫描模式。
type sessionRef struct {
	sid    string
	lastAt time.Time
}

// queryRecentSessions 用一条 GROUP BY 查询拿到 cutoff 之后所有有活动的会话。
//
// <p>性能动机：cursor state.vscdb 在重度用户机器上 cursorDiskKV 可累积数百会话 / 数万 bubble。
// 旧实现先 SELECT 全部 composerData 元数据 → 然后对每个 composer 单独 QueryRow 查 lastBubble
// 的 createdAt → 再做时间窗过滤，N+1 模式下单次 collect 要 1-2 分钟。
//
// <p>v2.8 新实现：sqlite 端 GROUP BY substr(key,10,36) + MAX(createdAt) 一次出结果，
// 同时把 cutoff 推到 SQL 层 WHERE，不命中的 composer 不进入 Go 循环。
// 实测 75K bubble 的库 1-1.5 秒返回，整体 collect 从 ~100 秒降到 &lt;5 秒。
//
// <p>关于 substr(key, 10, 36)：bubbleId 键格式固定为 "bubbleId:&lt;UUID 36 字符&gt;:&lt;bubbleId&gt;"，
// "bubbleId:" 占前 9 字节，从第 10 位开始连续 36 字节就是 sid（UUID v4）。
// 用硬编码切片比 instr() 字符串搜索快几倍，cursor 的 sid 始终是标准 UUID 不会变。
//
// <p>cutoff 字符串格式选 "2006-01-02T15:04:05.000Z"：与 cursor JS 端 Date.toISOString() 输出
// 严格一致，ISO 8601 + UTC + 毫秒精度，可直接做 lex 比较代替时间戳数值比较。
// bubble.createdAt 大约 1.4% 缺失，缺的 row json_extract NULL 比较为 NULL 自动被丢弃。
func queryRecentSessions(db *sql.DB, cutoff time.Time) ([]sessionRef, error) {
	cutoffStr := cutoff.UTC().Format("2006-01-02T15:04:05.000Z")
	rows, err := db.Query(`
		SELECT substr(key, 10, 36) AS sid,
		       MAX(json_extract(value, '$.createdAt')) AS last_at
		FROM cursorDiskKV
		WHERE key LIKE 'bubbleId:%'
		  AND json_extract(value, '$.createdAt') > ?
		GROUP BY substr(key, 10, 36)
	`, cutoffStr)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []sessionRef
	for rows.Next() {
		var sid, lastAtStr string
		if err := rows.Scan(&sid, &lastAtStr); err != nil {
			continue
		}
		lastAt := parseISO(lastAtStr)
		if lastAt.IsZero() {
			continue
		}
		out = append(out, sessionRef{sid: sid, lastAt: lastAt})
	}
	return out, rows.Err()
}

func openStateDBRO(path string) (*sql.DB, error) {
	return sql.Open("sqlite", path+"?mode=ro")
}
